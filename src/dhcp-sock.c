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
            int rc = dhcp_send_reply(lp, req.chaddr, &req, msg, yiaddr);
            uint64_t us = (uv_hrtime() - t_recv) / 1000;
            LLOG(LLOG_INFO, "DHCP: socket reply took %llu us", (unsigned long long)us);
            dhcp_account_reply(lp, req.chaddr, msg, yiaddr, rc);
        }
    }
}

int dhcp_sock_start(struct lan_play *lp)
{
    if (lp->dhcp_sock_active) return 0;
    if (!options.dhcp_server || !lp->wifi_subnet_known) return -1;

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const char *)&opt, sizeof(opt));
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, (const char *)&opt, sizeof(opt));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(67);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        closesocket(fd);
        return -1;
    }
    lp->dhcp_sock_fd = fd;
    lp->dhcp_sock_active = true;
    if (uv_thread_create(&lp->dhcp_thread, dhcp_thread_main, lp) != 0) {
        closesocket(fd);
        lp->dhcp_sock_active = false;
        return -1;
    }
    return 0;
}

void dhcp_sock_stop(struct lan_play *lp)
{
    if (!lp->dhcp_sock_active) return;
    lp->dhcp_sock_active = false;
    closesocket(lp->dhcp_sock_fd);
    uv_thread_join(&lp->dhcp_thread);
}
