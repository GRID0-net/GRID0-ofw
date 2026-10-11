/* Socket fast path for the DHCP server. See dhcp-sock.h. */
#include "lan-play.h"
#include "dhcp-server.h"
#include "dhcp-sock.h"

static void dhcp_sock_alloc(uv_handle_t *handle, size_t suggested, uv_buf_t *buf)
{
    (void)handle;
    (void)suggested;
    buf->base = malloc(576);
    buf->len = buf->base ? 576 : 0;
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
                int rc = dhcp_send_reply(lp, req.chaddr, &req, msg, yiaddr);
                uint64_t us = (uv_hrtime() - t_recv) / 1000;
                LLOG(LLOG_INFO, "DHCP: socket reply took %llu us", (unsigned long long)us);
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
    uv_ip4_addr("0.0.0.0", 67, &addr);
    if (uv_udp_bind(sock, (const struct sockaddr *)&addr, 0) != 0)
        goto fail;
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
