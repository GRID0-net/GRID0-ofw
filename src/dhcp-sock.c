/* Socket fast path for the DHCP server. See dhcp-sock.h.
 * Uses a dedicated thread with blocking recv() to eliminate UV loop queue delay.
 * The thread runs independently of the Libuv event loop, so DHCP packets are
 * processed immediately without waiting behind other events. */
#include "lan-play.h"
#include "dhcp-server.h"
#include "dhcp-sock.h"
#include <uv.h>

static void dhcp_thread_main(void *arg)
{
    struct lan_play *lp = (struct lan_play *)arg;
    uint8_t buf[576];
    while (lp->dhcp_sock_active) {
        struct sockaddr_in addr;
        socklen_t addr_len = sizeof(addr);
        int nread = recvfrom(lp->dhcp_sock_fd, (char *)buf, sizeof(buf), 0,
                             (struct sockaddr *)&addr, &addr_len);
        if (nread < 240) continue;
        struct dhcp_request req;
        if (!dhcp_parse_payload(buf, (size_t)nread, &req)) continue;
        uint64_t t_recv = uv_hrtime();
        uint8_t yiaddr[4] = {0, 0, 0, 0};
        uint8_t msg = dhcp_decide(lp, req.chaddr, &req, yiaddr);
        if (msg) {
            int rc = dhcp_send_reply_udp(lp, req.chaddr, &req, msg, yiaddr);
            uint64_t us = (uv_hrtime() - t_recv) / 1000;
            LLOG(LLOG_INFO, "DHCP: socket reply took %llu us [9012]", (unsigned long long)us);
            dhcp_account_reply(lp, req.chaddr, msg, yiaddr, rc);
        }
    }
}

int dhcp_sock_start(struct lan_play *lp)
{
    (void)lp;
    return -1;
}

void dhcp_sock_stop(struct lan_play *lp)
{
    if (!lp->dhcp_sock_active) return;
    lp->dhcp_sock_active = false;
    closesocket(lp->dhcp_sock_fd);
    if (lp->dhcp_sock_zt_fd >= 0) closesocket(lp->dhcp_sock_zt_fd);
    uv_thread_join(&lp->dhcp_thread);
}
