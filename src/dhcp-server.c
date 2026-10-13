/* Minimal DHCP server for Automatic/DHCP Switch consoles.
 *
 * Single-phase M4: the Switch keeps a home-subnet address; our OFFER/ACK
 * sets Router to this PC's Wi-Fi IP so the PC NATs the Switch's internet
 * and routes 10.147.17.0/24 into ZeroTier. Lease logic shared by the
 * socket fast path (dhcp-sock.c) and the pcap injection path
 * (dhcp_server_consume below). See dhcp-server.h.
 */
#include "lan-play.h"
#include "dhcp-server.h"
#include "nintendo_oui.h"

#define DHCP_OP_BOOTREQUEST 1
#define DHCP_OP_BOOTREPLY 2
#define DHCP_HTYPE_ETHER 1

#define DHCP_OPT_MSG_TYPE 53
#define DHCP_OPT_SERVER_ID 54
#define DHCP_OPT_REQ_IP 50
#define DHCP_OPT_LEASE_TIME 51
#define DHCP_OPT_MASK 1
#define DHCP_OPT_ROUTER 3
#define DHCP_OPT_DNS 6
#define DHCP_OPT_BROADCAST 28
#define DHCP_OPT_CLASSLESS_ROUTE 121
#define DHCP_OPT_END 255

#define DHCP_MAGIC 0x63825363u

void dhcp_server_init(struct lan_play *lp)
{
    memset(&lp->dhcp_state, 0, sizeof(lp->dhcp_state));
}

static void dhcp_sweep_expired(struct dhcp_server_state *st, time_t now)
{
    for (int i = 0; i < DHCP_LEASE_COUNT; ++i) {
        if (st->leases[i].used && st->leases[i].expires <= now) {
            memset(&st->leases[i], 0, sizeof(st->leases[i]));
        }
    }
}

static struct dhcp_lease *dhcp_find_mac(struct dhcp_server_state *st, const uint8_t mac[6])
{
    for (int i = 0; i < DHCP_LEASE_COUNT; ++i) {
        if (st->leases[i].used && CMP_MAC(st->leases[i].mac, mac))
            return &st->leases[i];
    }
    return NULL;
}

static uint32_t ipv4_to_u32(const uint8_t ip[4])
{
    return ((uint32_t)ip[0] << 24) | ((uint32_t)ip[1] << 16) |
           ((uint32_t)ip[2] << 8) | ip[3];
}

static void u32_to_ipv4(uint32_t v, uint8_t ip[4])
{
    ip[0] = (v >> 24) & 0xff;
    ip[1] = (v >> 16) & 0xff;
    ip[2] = (v >> 8) & 0xff;
    ip[3] = v & 0xff;
}

static bool dhcp_parse_ipv4(const char *s, uint8_t out[4])
{
    for (int i = 0; i < 4; i++) {
        if (!s || *s < '0' || *s > '9') return false;
        unsigned v = 0;
        while (*s >= '0' && *s <= '9') {
            v = v * 10 + (unsigned)(*s - '0');
            if (v > 255) return false;
            s++;
        }
        out[i] = (uint8_t)v;
        if (i < 3) {
            if (*s != '.') return false;
            s++;
        }
    }
    return *s == '\0';
}

static void dhcp_get_router_ip(struct lan_play *lp, uint8_t out[4])
{
    if (options.dhcp_router && dhcp_parse_ipv4(options.dhcp_router, out)) return;
    if (lp->dhcp_router_known) {
        CPY_IPV4(out, lp->dhcp_router_ip);
        return;
    }
    for (int i = 0; i < 4; i++)
        out[i] = (uint8_t)((lp->wifi_ip[i] & lp->wifi_netmask[i]) | (i == 3 ? 1 : 0));
}

static void dhcp_build_ip_pool(struct lan_play *lp)
{
    struct dhcp_server_state *st = &lp->dhcp_state;
    uint8_t net[4];
    uint8_t bcast[4];
    CPY_IPV4(net, lp->zerotier_ip);
    net[3] = 0;
    CPY_IPV4(bcast, lp->zerotier_ip);
    bcast[3] = 255;
    const uint32_t b = ipv4_to_u32(bcast);
    const uint32_t n = ipv4_to_u32(net);
    st->ip_pool_count = 0;
    CPY_IPV4(st->ip_pool_zt_ip, lp->zerotier_ip);
    for (uint32_t cand = b - 1; cand > n + 1; --cand) {
        uint8_t ip[4];
        u32_to_ipv4(cand, ip);
        if (CMP_IPV4(ip, lp->zerotier_ip)) continue;
        if (arp_has_ip(&lp->packet_ctx, ip)) continue;
        if (st->ip_pool_count < 253) {
            CPY_IPV4(st->ip_pool[st->ip_pool_count], ip);
            st->ip_pool_count++;
        }
    }
    st->ip_pool_built = true;
}

static bool dhcp_pick_home_ip(struct lan_play *lp, const uint8_t mac[6], uint8_t out[4])
{
    struct dhcp_server_state *st = &lp->dhcp_state;
    const time_t now = time(NULL);
    struct dhcp_lease *own = dhcp_find_mac(st, mac);
    if (own && own->expires > now) {
        CPY_IPV4(out, own->ip);
        return true;
    }
    if (!st->ip_pool_built || !CMP_IPV4(st->ip_pool_zt_ip, lp->zerotier_ip)) {
        dhcp_build_ip_pool(lp);
    }
    for (int i = 0; i < st->ip_pool_count; ++i) {
        bool taken = false;
        for (int j = 0; j < DHCP_LEASE_COUNT; ++j) {
            if (st->leases[j].used && st->leases[j].expires > now &&
                CMP_IPV4(st->leases[j].ip, st->ip_pool[i])) {
                taken = true;
                break;
            }
        }
        if (taken) continue;
        CPY_IPV4(out, st->ip_pool[i]);
        return true;
    }
    return false;
}

static struct dhcp_lease *dhcp_claim(struct lan_play *lp, const uint8_t mac[6],
                                     const uint8_t ip[4], bool offered)
{
    struct dhcp_server_state *st = &lp->dhcp_state;
    const time_t now = time(NULL);
    struct dhcp_lease *lease = dhcp_find_mac(st, mac);
    if (!lease) {
        for (int i = 0; i < DHCP_LEASE_COUNT; ++i) {
            if (!st->leases[i].used) {
                lease = &st->leases[i];
                break;
            }
        }
        if (!lease) return NULL;
    }
    CPY_MAC(lease->mac, mac);
    CPY_IPV4(lease->ip, ip);
    lease->offered = offered;
    lease->expires = now + (offered ? DHCP_OFFER_TIME : DHCP_LEASE_TIME);
    lease->used = true;
    return lease;
}

/* Serialize one DHCP reply option; returns the new write offset. */
static size_t dhcp_opt(uint8_t *p, size_t off, uint8_t code, const void *data, size_t len)
{
    p[off++] = code;
    p[off++] = (uint8_t)len;
    memcpy(p + off, data, len);
    return off + len;
}

size_t dhcp_build_payload(struct lan_play *lp, const uint8_t *dst_mac,
                          const struct dhcp_request *req, uint8_t msg_type,
                          const uint8_t yiaddr[4], uint8_t *dhcp)
{
    if (!yiaddr) return 0; memset(dhcp, 0, DHCP_MIN_LEN);
    dhcp[0] = DHCP_OP_BOOTREPLY;
    dhcp[1] = DHCP_HTYPE_ETHER;
    dhcp[2] = 6;
    dhcp[3] = 0;
    WRITE_NET16(dhcp, 4, (uint16_t)(req->xid >> 16));
    WRITE_NET16(dhcp, 6, (uint16_t)(req->xid & 0xffff));
    WRITE_NET16(dhcp, 10, req->flags);
    if (yiaddr) CPY_IPV4(dhcp + 16, yiaddr);
    bool is_zt_offer = IS_SUBNET(yiaddr, lp->zerotier_ip, lp->zerotier_netmask);
    
    
    const uint8_t *siaddr_ip = is_zt_offer ? lp->zerotier_ip : lp->wifi_ip;
    CPY_IPV4(dhcp + 20, siaddr_ip);
    CPY_MAC(dhcp + 28, req->chaddr);
    WRITE_NET16(dhcp, 236, (uint16_t)(DHCP_MAGIC >> 16));
    WRITE_NET16(dhcp, 238, (uint16_t)(DHCP_MAGIC & 0xffff));

    size_t off = 240;
    off = dhcp_opt(dhcp, off, DHCP_OPT_MSG_TYPE, &msg_type, 1);
    /* Proxy ARP mode: use ZeroTier IP for server/gateway/DNS */
    
    
    
    const uint8_t *srv_ip = is_zt_offer ? lp->zerotier_ip : lp->wifi_ip;
    const uint8_t *netmask = is_zt_offer ? lp->zerotier_netmask : lp->wifi_netmask;
    const uint8_t *gw_ip = is_zt_offer ? lp->zerotier_ip : lp->wifi_ip;
    off = dhcp_opt(dhcp, off, DHCP_OPT_SERVER_ID, srv_ip, 4);
    off = dhcp_opt(dhcp, off, DHCP_OPT_MASK, netmask, 4);
    off = dhcp_opt(dhcp, off, DHCP_OPT_ROUTER, gw_ip, 4);
    off = dhcp_opt(dhcp, off, DHCP_OPT_DNS, gw_ip, 4);
    /* Option 121 test: always include route to ZeroTier via PC. */
    if (!is_zt_offer) {
        uint8_t routes[16];
        size_t roff = 0;
        uint8_t zt_net[4];
        int bits = 0;
        for (int i = 0; i < 4; i++) {
            zt_net[i] = lp->zerotier_ip[i] & lp->zerotier_netmask[i];
            for (int b = 0; b < 8; b++)
                if (lp->zerotier_netmask[i] & (0x80 >> b)) bits++;
        }
        const int octets = (bits + 7) / 8;
        routes[roff++] = (uint8_t)bits;
        memcpy(routes + roff, zt_net, (size_t)octets);
        roff += (size_t)octets;
        CPY_IPV4(routes + roff, lp->wifi_ip);
        roff += 4;
        routes[roff++] = 0;
        dhcp_get_router_ip(lp, routes + roff);
        roff += 4;
        off = dhcp_opt(dhcp, off, DHCP_OPT_CLASSLESS_ROUTE, routes, roff);
    }
    uint8_t bcast_addr[4];
    if (is_zt_offer) {
        for (int i = 0; i < 4; i++) bcast_addr[i] = (uint8_t)(lp->zerotier_ip[i] | ~lp->zerotier_netmask[i]);
    } else {
        CPY_IPV4(bcast_addr, lp->wifi_broadcast);
    }
    off = dhcp_opt(dhcp, off, DHCP_OPT_BROADCAST, bcast_addr, 4);
    uint8_t lease_be[4];
    uint32_t lease = DHCP_LEASE_TIME;
    lease_be[0] = (lease >> 24) & 0xff;
    lease_be[1] = (lease >> 16) & 0xff;
    lease_be[2] = (lease >> 8) & 0xff;
    lease_be[3] = lease & 0xff;
    off = dhcp_opt(dhcp, off, DHCP_OPT_LEASE_TIME, lease_be, 4);
    dhcp[off++] = DHCP_OPT_END;
    return DHCP_MIN_LEN;
}

static uint16_t dhcp_ip_id = 0;

int dhcp_send_reply(struct lan_play *lp, const uint8_t dst_mac[6],
                      const struct dhcp_request *req, uint8_t msg_type,
                      const uint8_t yiaddr[4])
{
    /* Ethernet is added by lan_play_dhcp_send; build the IPv4 packet here.
     * Replies always go out as IP/Ethernet broadcast: embedded clients
     * (including the Switch) may not receive unicast IP before they own
     * the offered address. The client filters on xid/chaddr. */
    uint8_t ip[ETHER_MTU];
    const size_t dhcp_len = DHCP_MIN_LEN;
    const size_t udp_len = UDP_OFF_END + dhcp_len;
    const size_t ip_len = IPV4_HEADER_LEN + udp_len;
    if (ip_len > sizeof(ip)) return -1;
    memset(ip, 0, ip_len);

    size_t built = dhcp_build_payload(lp, dst_mac, req, msg_type, yiaddr,
                       ip + IPV4_HEADER_LEN + UDP_OFF_END);
    if (built == 0) return -1;
    

    ip[IPV4_OFF_VER_LEN] = 0x45;
    WRITE_NET16(ip, IPV4_OFF_TOTAL_LEN, (uint16_t)ip_len);
    WRITE_NET16(ip, IPV4_OFF_ID, ++dhcp_ip_id);
    ip[IPV4_OFF_TTL] = 64;
    ip[IPV4_OFF_PROTOCOL] = IPV4_PROTOCOL_UDP;
    
    
    bool is_zt_src = yiaddr && IS_SUBNET(yiaddr, lp->zerotier_ip, lp->zerotier_netmask);
    const uint8_t *src_ip = is_zt_src ? lp->zerotier_ip : lp->wifi_ip;
    CPY_IPV4(ip + IPV4_OFF_SRC, src_ip);
    memset(ip + IPV4_OFF_DST, 0xff, 4);
    WRITE_NET16(ip, IPV4_OFF_CHECKSUM, 0);
    WRITE_NET16(ip, IPV4_OFF_CHECKSUM, ipv4_header_checksum(ip, IPV4_HEADER_LEN));

    uint8_t *udp = ip + IPV4_HEADER_LEN;
    WRITE_NET16(udp, UDP_OFF_SRCPORT, 67);
    WRITE_NET16(udp, UDP_OFF_DSTPORT, 68);
    WRITE_NET16(udp, UDP_OFF_LENGTH, (uint16_t)udp_len);
    WRITE_NET16(udp, UDP_OFF_CHECKSUM, 0);
    WRITE_NET16(udp, UDP_OFF_CHECKSUM, udp_checksum(ip, IPV4_HEADER_LEN, ip_len));

    static const uint8_t bcast_mac[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    return lan_play_dhcp_send(lp, bcast_mac, ip, (uint16_t)ip_len);
}

bool dhcp_parse_payload(const uint8_t *dhcp, size_t len, struct dhcp_request *req)
{
    memset(req, 0, sizeof(*req));
    if (len < 240) return false;
    if (dhcp[0] != DHCP_OP_BOOTREQUEST || dhcp[1] != DHCP_HTYPE_ETHER || dhcp[2] != 6)
        return false;
    if (READ_NET16(dhcp, 236) != (uint16_t)(DHCP_MAGIC >> 16) ||
        READ_NET16(dhcp, 238) != (uint16_t)(DHCP_MAGIC & 0xffff))
        return false;

    req->xid = ((uint32_t)READ_NET16(dhcp, 4) << 16) | READ_NET16(dhcp, 6);
    req->flags = READ_NET16(dhcp, 10);
    CPY_IPV4(req->ciaddr, dhcp + 12);
    CPY_MAC(req->chaddr, dhcp + 28);

    size_t off = 240;
    while (off + 1 < len) {
        uint8_t code = dhcp[off];
        if (code == DHCP_OPT_END) break;
        if (code == 0) {
            ++off;
            continue;
        }
        if (off + 2 > len) break;
        uint8_t olen = dhcp[off + 1];
        if (off + 2 + olen > len) break;
        const uint8_t *data = dhcp + off + 2;
        if (code == DHCP_OPT_MSG_TYPE && olen == 1) req->msg_type = data[0];
        else if (code == DHCP_OPT_REQ_IP && olen == 4) {
            CPY_IPV4(req->requested_ip, data);
            req->has_requested_ip = true;
        } else if (code == DHCP_OPT_SERVER_ID && olen == 4) {
            CPY_IPV4(req->server_id, data);
            req->has_server_id = true;
        }
        off += 2 + olen;
    }
    return req->msg_type != 0;
}

static bool dhcp_parse(const uint8_t *frame, size_t len, struct dhcp_request *req)
{
    if (len < ETHER_HEADER_LEN + IPV4_HEADER_LEN + UDP_OFF_END + 240) return false;
    if (READ_NET16(frame, ETHER_OFF_TYPE) != ETHER_TYPE_IPV4) return false;
    const uint8_t *ip = frame + ETHER_HEADER_LEN;
    const size_t hlen = (ip[IPV4_OFF_VER_LEN] & 0x0f) * 4;
    if ((ip[IPV4_OFF_VER_LEN] >> 4) != 4 || hlen < IPV4_HEADER_LEN) return false;
    const size_t total = READ_NET16(ip, IPV4_OFF_TOTAL_LEN);
    if (total < hlen + UDP_OFF_END || total > len - ETHER_HEADER_LEN) return false;
    if (ip[IPV4_OFF_PROTOCOL] != IPV4_PROTOCOL_UDP) return false;
    const uint8_t *udp = ip + hlen;
    if (READ_NET16(udp, UDP_OFF_DSTPORT) != 67) return false;
    const size_t ulen = READ_NET16(udp, UDP_OFF_LENGTH);
    if (ulen < UDP_OFF_END + 240 || hlen + ulen > total) return false;
    return dhcp_parse_payload(udp + UDP_OFF_END, hlen + ulen - UDP_OFF_END, req);
}

static void dhcp_log_mac_ip(const char *what, const uint8_t mac[6], const uint8_t ip[4])
{
    LLOG(LLOG_INFO, "DHCP: %s %u.%u.%u.%u for %02x:%02x:%02x:%02x:%02x:%02x",
         what, ip[0], ip[1], ip[2], ip[3],
         mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

/* Machine-readable event for the desktop UI, which scans relay output for
 * lines containing "GRID0_DHCP <kind> <ip> <mac>". Substring matching is
 * deliberate: the log prefix format is owned by base/llog.h. */
static void dhcp_event(const char *kind, const uint8_t mac[6], const uint8_t ip[4])
{
    LLOG(LLOG_INFO, "GRID0_DHCP %s %u.%u.%u.%u %02x:%02x:%02x:%02x:%02x:%02x",
         kind, ip[0], ip[1], ip[2], ip[3],
         mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

uint8_t dhcp_decide(struct lan_play *lp, const uint8_t *mac,
                    const struct dhcp_request *req, uint8_t yiaddr[4])
{
    struct dhcp_server_state *st = &lp->dhcp_state;
    dhcp_sweep_expired(st, time(NULL));

    if (!is_nintendo_mac(mac)) {
        st->ignored_non_nintendo++;
        return 0;
    }

    if (req->msg_type == DHCP_MSG_DISCOVER) {
        if (!dhcp_pick_home_ip(lp, mac, yiaddr)) {
            LLOG(LLOG_WARNING, "DHCP: address pool exhausted; DISCOVER ignored");
            return 0;
        }
        if (!dhcp_claim(lp, mac, yiaddr, true)) {
            LLOG(LLOG_WARNING, "DHCP: no free lease slot; DISCOVER ignored");
            return 0;
        }
        return DHCP_MSG_OFFER;
    }

    if (req->msg_type == DHCP_MSG_REQUEST) {
        struct dhcp_lease *lease = dhcp_find_mac(st, mac);
        const time_t now = time(NULL);
        const uint8_t *want = req->has_requested_ip ? req->requested_ip : req->ciaddr;
        const bool has_want = req->has_requested_ip || !CMP_IPV4(req->ciaddr, "\0\0\0\0");
        if (lease && lease->expires > now && has_want && CMP_IPV4(want, lease->ip)) {
            dhcp_claim(lp, mac, lease->ip, false);
            CPY_IPV4(yiaddr, lease->ip);
            return DHCP_MSG_ACK;
        }
        if (has_want) {
            st->lost_races++;
            dhcp_event("lost", mac, want);
        }
        return 0;
    }

    if (req->msg_type == DHCP_MSG_RELEASE) {
        struct dhcp_lease *lease = dhcp_find_mac(st, mac);
        if (lease) memset(lease, 0, sizeof(*lease));
        return 0;
    }

    return 0;
}

bool dhcp_is_our_lease(struct lan_play *lp, const uint8_t mac[6], const uint8_t ip[4])
{
    struct dhcp_lease *lease = dhcp_find_mac(&lp->dhcp_state, mac);
    return lease && lease->expires > time(NULL) && CMP_IPV4(lease->ip, ip);
}

void dhcp_account_reply(struct lan_play *lp, const uint8_t *mac, uint8_t msg,
                        const uint8_t yiaddr[4], int send_rc)
{
    struct dhcp_server_state *st = &lp->dhcp_state;
    if (send_rc != 0) {
        if (msg == DHCP_MSG_OFFER || msg == DHCP_MSG_ACK)
            LLOG(LLOG_WARNING, "DHCP: %s send failed", msg == DHCP_MSG_OFFER ? "OFFER" : "ACK");
        return;
    }
    if (msg == DHCP_MSG_OFFER) {
        st->offers_sent++;
        dhcp_log_mac_ip("offered", mac, yiaddr);
        dhcp_event("offered", mac, yiaddr);
    } else if (msg == DHCP_MSG_ACK) {
        st->acks_sent++;
        dhcp_log_mac_ip("assigned", mac, yiaddr);
        dhcp_event("assigned", mac, yiaddr);
    } else if (msg == DHCP_MSG_NAK) {
        st->naks_sent++;
    }
}

static bool dhcp_parse_server(const uint8_t *frame, size_t len)
{
    if (len < ETHER_HEADER_LEN + IPV4_HEADER_LEN + UDP_OFF_END + 240) return false;
    if (READ_NET16(frame, ETHER_OFF_TYPE) != ETHER_TYPE_IPV4) return false;
    const uint8_t *ip = frame + ETHER_HEADER_LEN;
    const size_t hlen = (ip[IPV4_OFF_VER_LEN] & 0x0f) * 4;
    if ((ip[IPV4_OFF_VER_LEN] >> 4) != 4 || hlen < IPV4_HEADER_LEN) return false;
    const size_t total = READ_NET16(ip, IPV4_OFF_TOTAL_LEN);
    if (total < hlen + UDP_OFF_END || total > len - ETHER_HEADER_LEN) return false;
    if (ip[IPV4_OFF_PROTOCOL] != IPV4_PROTOCOL_UDP) return false;
    const uint8_t *udp = ip + hlen;
    if (READ_NET16(udp, UDP_OFF_SRCPORT) != 67 || READ_NET16(udp, UDP_OFF_DSTPORT) != 68)
        return false;
    const size_t ulen = READ_NET16(udp, UDP_OFF_LENGTH);
    if (ulen < UDP_OFF_END + 240 || hlen + ulen > total) return false;
    return true;
}

static void dhcp_snoop_server(struct lan_play *lp, const uint8_t *payload, size_t len)
{
    if (lp->dhcp_router_known || len < 240) return;
    if (payload[0] != DHCP_OP_BOOTREPLY) return;
    if (READ_NET16(payload, 236) != (uint16_t)(DHCP_MAGIC >> 16) ||
        READ_NET16(payload, 238) != (uint16_t)(DHCP_MAGIC & 0xffff)) return;
    size_t off = 240;
    while (off + 1 < len) {
        const uint8_t code = payload[off];
        if (code == DHCP_OPT_END) break;
        if (code == 0) {
            ++off;
            continue;
        }
        if (off + 2 > len) break;
        const uint8_t olen = payload[off + 1];
        if (off + 2 + olen > len) break;
        if (code == DHCP_OPT_SERVER_ID && olen == 4) {
            const uint8_t *sip = payload + off + 2;
            static const uint8_t zero[4] = {0, 0, 0, 0};
            if (!CMP_IPV4(sip, zero) && !CMP_IPV4(sip, lp->wifi_ip) &&
                !CMP_IPV4(sip, lp->zerotier_ip)) {
                CPY_IPV4(lp->dhcp_router_ip, sip);
                lp->dhcp_router_known = true;
                LLOG(LLOG_INFO, "DHCP: learned router %u.%u.%u.%u from another server's reply",
                     sip[0], sip[1], sip[2], sip[3]);
            }
            return;
        }
        off += 2 + olen;
    }
}

bool dhcp_server_consume(struct lan_play *lp, const uint8_t *frame, size_t len)
{
    if (!options.dhcp_server || !lp->wifi_subnet_known) return false;

    if (dhcp_parse_server(frame, len)) {
        const uint8_t *ip = frame + ETHER_HEADER_LEN;
        const size_t hlen = (ip[IPV4_OFF_VER_LEN] & 0x0f) * 4;
        dhcp_snoop_server(lp, frame + ETHER_HEADER_LEN + hlen + UDP_OFF_END,
                          len - ETHER_HEADER_LEN - hlen - UDP_OFF_END);
        return true;
    }

    struct dhcp_request req;
    if (!dhcp_parse(frame, len, &req)) return false;

    /* A DHCP client packet is always consumed while the server runs: it must
     * never be relayed into ZeroTier or mistaken for game traffic. */
    if (lp->dhcp_sock_active) {
        /* The socket fast path already answered broadcasts; only unicast
         * renewals (which the socket cannot see) still need pcap injection. */
        const uint8_t *dst = frame + ETHER_HEADER_LEN + IPV4_OFF_DST;
        static const uint8_t bcast[4] = {255, 255, 255, 255};
        if (CMP_IPV4(dst, bcast) || CMP_IPV4(dst, lp->wifi_ip) ||
            CMP_IPV4(dst, lp->zerotier_ip))
            return true;
    }

    const uint8_t *mac = frame + ETHER_OFF_SRC;
    uint8_t yiaddr[4] = {0, 0, 0, 0};
    uint8_t msg = dhcp_decide(lp, mac, &req, yiaddr);
    if (msg) {
        int rc = dhcp_send_reply(lp, mac, &req, msg, yiaddr);
        dhcp_account_reply(lp, mac, msg, yiaddr, rc);
    }
    return true;
}
