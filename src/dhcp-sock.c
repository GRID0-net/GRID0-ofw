/* Socket fast path for the DHCP server. See dhcp-sock.h. */
#include "lan-play.h"
#include "dhcp-server.h"
#include "dhcp-sock.h"

struct dhcp_sock_send {
    uv_udp_send_t req;
    struct lan_play *lp;
    uint8_t msg;
    uint8_t mac[6];
    uint8_t yiaddr[4];
    uint8_t payload[DHCP_MIN_LEN];
};

static bool sock_bound_to_gateway = false;

static void dhcp_sock_alloc(uv_handle_t *handle, size_t suggested, uv_buf_t *buf)
{
    (void)handle;
    (void)suggested;
    buf->base = malloc(576);
    buf->len = buf->base ? 576 : 0;
}

static void dhcp_sock_sent(uv_udp_send_t *req, int status)
{
    struct dhcp_sock_send *s = (struct dhcp_sock_send *)req;
    dhcp_account_reply(s->lp, s->mac, s->msg, s->yiaddr, status);
    free(s);
}

static void dhcp_sock_reply(struct lan_play *lp, const struct dhcp_request *req,
                            uint8_t msg, const uint8_t yiaddr[4])
{
    struct dhcp_sock_send *s = malloc(sizeof(*s));
    if (!s) return;
    s->lp = lp;
    s->msg = msg;
    CPY_MAC(s->mac, req->chaddr);
    CPY_IPV4(s->yiaddr, yiaddr);
    dhcp_build_payload(lp, req->chaddr, req, msg, yiaddr, s->payload);

    struct sockaddr_in dst;
    uv_ip4_addr("255.255.255.255", 68, &dst);
    uv_buf_t buf = uv_buf_init((char *)s->payload, DHCP_MIN_LEN);
    if (uv_udp_send(&s->req, &lp->dhcp_sock, &buf, 1,
                    (const struct sockaddr *)&dst, dhcp_sock_sent) != 0)
        free(s);
}

static void dhcp_sock_recv(uv_udp_t *handle, ssize_t nread, const uv_buf_t *buf,
                           const struct sockaddr *addr, unsigned flags)
{
    struct lan_play *lp = handle->data;
    (void)addr;
    (void)flags;
    if (nread >= 240) {
        struct dhcp_request req;
        if (dhcp_parse_payload((const uint8_t *)buf->base, (size_t)nread, &req)) {
            uint64_t t_recv = uv_hrtime();
            uint8_t yiaddr[4] = {0, 0, 0, 0};
            uint8_t msg = dhcp_decide(lp, req.chaddr, &req, yiaddr);
            if (msg) {
                int rc;
                if (sock_bound_to_gateway) {
                    dhcp_sock_reply(lp, &req, msg, yiaddr);
                    rc = 0;
                } else {
                    rc = dhcp_send_reply(lp, req.chaddr, &req, msg, yiaddr);
                }
                uint64_t us = (uv_hrtime() - t_recv) / 1000;
                LLOG(LLOG_INFO, "DHCP: socket reply took %llu us", (unsigned long long)us);
                if (!sock_bound_to_gateway)
                    dhcp_account_reply(lp, req.chaddr, msg, yiaddr, rc);
            }
        }
    }
    free(buf->base);
}

int dhcp_sock_start(struct lan_play *lp)
{
    if (lp->dhcp_sock_active) return 0;
    if (!options.dhcp_server || !lp->wifi_subnet_known) return -1;

    uv_udp_t *sock = &lp->dhcp_sock;
    if (uv_udp_init(lp->loop, sock) != 0) return -1;
    sock->data = lp;

    struct sockaddr_in addr;
    char gateway_ip[16];
    snprintf(gateway_ip, sizeof(gateway_ip), "%u.%u.%u.%u",
             lp->packet_ctx.ip[0], lp->packet_ctx.ip[1],
             lp->packet_ctx.ip[2], lp->packet_ctx.ip[3]);
    uv_ip4_addr(gateway_ip, 67, &addr);
    if (uv_udp_bind(sock, (const struct sockaddr *)&addr, 0) == 0) {
        sock_bound_to_gateway = true;
        LLOG(LLOG_INFO, "DHCP: socket bound to gateway %s (fast send)", gateway_ip);
    } else {
        uv_ip4_addr("0.0.0.0", 67, &addr);
        if (uv_udp_bind(sock, (const struct sockaddr *)&addr, 0) != 0)
            goto fail;
        sock_bound_to_gateway = false;
        LLOG(LLOG_INFO, "DHCP: socket bound to 0.0.0.0 (pcap send fallback)");
    }
    if (uv_udp_set_broadcast(sock, 1) != 0)
        goto fail;
    if (uv_udp_recv_start(sock, dhcp_sock_alloc, dhcp_sock_recv) != 0)
        goto fail;

    lp->dhcp_sock_active = true;
    return 0;

fail:
    uv_close((uv_handle_t *)sock, NULL);
    return -1;
}

void dhcp_sock_stop(struct lan_play *lp)
{
    if (!lp->dhcp_sock_active) return;
    lp->dhcp_sock_active = false;
    uv_close((uv_handle_t *)&lp->dhcp_sock, NULL);
}
