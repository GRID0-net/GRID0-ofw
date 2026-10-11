/* Socket fast path for the DHCP server. See dhcp-sock.h. */
#include "lan-play.h"
#include "dhcp-server.h"
#include "dhcp-sock.h"

#ifndef _WIN32
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <arpa/inet.h>
#include <unistd.h>
#endif

struct dhcp_sock_send {
    uv_udp_send_t req;
    struct lan_play *lp;
    uint8_t msg;
    uint8_t mac[6];
    uint8_t yiaddr[4];
    uint8_t payload[DHCP_MIN_LEN];
};

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

#ifndef _WIN32
static int dhcp_raw_fd = -1;

static uint16_t dhcp_checksum(const uint8_t *buf, size_t len)
{
    uint32_t sum = 0;
    for (size_t i = 0; i + 1 < len; i += 2)
        sum += ((uint16_t)buf[i] << 8) | buf[i + 1];
    if (len & 1) sum += (uint16_t)buf[len - 1] << 8;
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

static void dhcp_raw_reply(struct lan_play *lp, const struct dhcp_request *req,
                           uint8_t msg, const uint8_t yiaddr[4])
{
    if (dhcp_raw_fd < 0) {
        dhcp_raw_fd = socket(AF_INET, SOCK_RAW, IPPROTO_UDP);
        if (dhcp_raw_fd < 0) return;
        int one = 1;
        if (setsockopt(dhcp_raw_fd, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one)) != 0) {
            close(dhcp_raw_fd);
            dhcp_raw_fd = -1;
            return;
        }
        int bcast = 1;
        setsockopt(dhcp_raw_fd, SOL_SOCKET, SO_BROADCAST, &bcast, sizeof(bcast));
    }

    uint8_t pkt[576];
    memset(pkt, 0, sizeof(pkt));
    uint8_t *ip = pkt;
    uint8_t *udp = pkt + 20;
    uint8_t *dhcp = pkt + 28;

    dhcp_build_payload(lp, req->chaddr, req, msg, yiaddr, lp->packet_ctx.ip, dhcp);

    ip[0] = 0x45;
    uint16_t total = 20 + 8 + DHCP_MIN_LEN;
    ip[2] = total >> 8;
    ip[3] = total & 0xff;
    ip[8] = 64;
    ip[9] = 17;
    CPY_IPV4(ip + 12, lp->packet_ctx.ip);
    static const uint8_t bcast_ip[4] = {255, 255, 255, 255};
    CPY_IPV4(ip + 16, bcast_ip);
    uint16_t csum = dhcp_checksum(ip, 20);
    ip[10] = csum >> 8;
    ip[11] = csum & 0xff;

    udp[0] = 0;
    udp[1] = 67;
    udp[2] = 0;
    udp[3] = 68;
    uint16_t ulen = 8 + DHCP_MIN_LEN;
    udp[4] = ulen >> 8;
    udp[5] = ulen & 0xff;

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr.s_addr = htonl(0xffffffff);
    int rc = sendto(dhcp_raw_fd, pkt, total, 0, (struct sockaddr *)&dst, sizeof(dst));
    dhcp_account_reply(lp, req->chaddr, msg, yiaddr, rc < 0 ? -1 : 0);
}
#endif

static void dhcp_sock_reply(struct lan_play *lp, const struct dhcp_request *req,
                            uint8_t msg, const uint8_t yiaddr[4])
{
#ifndef _WIN32
    dhcp_raw_reply(lp, req, msg, yiaddr);
#else
    struct dhcp_sock_send *s = malloc(sizeof(*s));
    if (!s) return;
    s->lp = lp;
    s->msg = msg;
    CPY_MAC(s->mac, req->chaddr);
    CPY_IPV4(s->yiaddr, yiaddr);
    dhcp_build_payload(lp, req->chaddr, req, msg, yiaddr, lp->wifi_ip, s->payload);

    struct sockaddr_in dst;
    uv_ip4_addr("255.255.255.255", 68, &dst);
    uv_buf_t buf = uv_buf_init((char *)s->payload, DHCP_MIN_LEN);
    if (uv_udp_send(&s->req, &lp->dhcp_sock, &buf, 1,
                    (const struct sockaddr *)&dst, dhcp_sock_sent) != 0)
        free(s);
#endif
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
                dhcp_sock_reply(lp, &req, msg, yiaddr);
                uint64_t us = (uv_hrtime() - t_recv) / 1000;
                LLOG(LLOG_INFO, "DHCP: socket reply took %llu us", (unsigned long long)us);
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
    uv_ip4_addr("0.0.0.0", 67, &addr);
    if (uv_udp_bind(sock, (const struct sockaddr *)&addr, 0) != 0)
        goto fail;
    if (uv_udp_set_broadcast(sock, 1) != 0)
        goto fail;
    if (uv_udp_recv_start(sock, dhcp_sock_alloc, dhcp_sock_recv) != 0)
        goto fail;

    lp->dhcp_sock_active = true;
    LLOG(LLOG_INFO, "DHCP: socket fast path active on UDP/67 (server-id = Wi-Fi IP)");
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
