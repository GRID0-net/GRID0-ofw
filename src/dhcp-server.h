#ifndef _DHCP_SERVER_H_
#define _DHCP_SERVER_H_

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <time.h>

/* Minimal DHCP server for Automatic/DHCP Switch consoles.
 *
 * Single-phase M4 design: the Switch keeps a home-subnet address for the
 * whole session. Our OFFER sets the Router option to this PC's Wi-Fi IP, so
 * the PC becomes the Switch's gateway: 10.147.17.0/24 game traffic routes
 * into ZeroTier and everything else is NATed out the real uplink by the
 * user-space gateway in gateway.cpp. No hotspot mode, no router changes,
 * no second phase.
 *
 * Two answer paths share the lease logic below. The socket fast path
 * (dhcp-sock.c) hears DISCOVERs straight from a UDP/67 socket and wins the
 * race against home routers; the pcap path (dhcp_server_consume) injects
 * replies via Npcap and stays as fallback plus handles unicast renewals the
 * socket cannot see.
 *
 * Safety: the server only answers clients whose MAC carries a Nintendo
 * vendor prefix (see nintendo_oui.h), so the household router's other
 * clients can never receive a poisoned lease. It is off unless the
 * --dhcp flag is passed.
 */

struct lan_play;

#define DHCP_LEASE_COUNT 8
#define DHCP_LEASE_TIME 3600 /* seconds */
#define DHCP_OFFER_TIME 3600  /* seconds a DISCOVER offer is held (Option 2: longer to prevent sync issues) */
#define DHCP_MIN_LEN 300     /* BOOTP minimum payload */

#define DHCP_MSG_DISCOVER 1
#define DHCP_MSG_OFFER 2
#define DHCP_MSG_REQUEST 3
#define DHCP_MSG_ACK 5
#define DHCP_MSG_NAK 6
#define DHCP_MSG_RELEASE 7

/* True when this MAC holds an unexpired lease for this IP. Used by the
 * Switch learner to tell our own offer apart from the router's. */
bool dhcp_is_our_lease(struct lan_play *lp, const uint8_t mac[6],
                       const uint8_t ip[4]);

/* Parsed from one client packet. */
struct dhcp_request {
    uint32_t xid;
    uint16_t flags;
    uint8_t chaddr[6];
    uint8_t msg_type;
    uint8_t requested_ip[4];
    bool has_requested_ip;
    uint8_t server_id[4];
    bool has_server_id;
    uint8_t ciaddr[4];
};

struct dhcp_lease {
    uint8_t mac[6];
    uint8_t ip[4];
    time_t expires;
    bool offered; /* true while holding an unanswered OFFER */
    bool used;
};

struct dhcp_server_state {
    struct dhcp_lease leases[DHCP_LEASE_COUNT];
    uint64_t offers_sent;
    uint64_t acks_sent;
    uint64_t naks_sent;
    uint64_t ignored_non_nintendo;
    uint64_t lost_races;
    uint8_t ip_pool[253][4];
    int ip_pool_count;
    uint8_t ip_pool_zt_ip[4];
    bool ip_pool_built;
};

/* Parse a raw DHCP payload (no Ethernet/IP/UDP headers). */
bool dhcp_parse_payload(const uint8_t *payload, size_t len, struct dhcp_request *req);

/* Decide the reply for one request. Returns 0 for no reply, otherwise a
 * DHCP_MSG_* type, with the address to use in yiaddr. Runs lease
 * management, stats and UI events; the caller handles the transport. */
uint8_t dhcp_decide(struct lan_play *lp, const uint8_t *mac,
                    const struct dhcp_request *req, uint8_t yiaddr[4]);

/* Build one DHCP reply payload (BOOTP minimum length). Returns the length. */
size_t dhcp_build_payload(struct lan_play *lp, const uint8_t *dst_mac,
                          const struct dhcp_request *req, uint8_t msg_type,
                          const uint8_t yiaddr[4], uint8_t *out);

/* Send one DHCP reply via pcap injection (correct source IP). Returns 0 on
 * success. Used by the socket fast path for sending after fast receiving. */
int dhcp_send_reply(struct lan_play *lp, const uint8_t dst_mac[6],
                    const struct dhcp_request *req, uint8_t msg_type,
                    const uint8_t yiaddr[4]);

int dhcp_send_reply_udp(struct lan_play *lp, const uint8_t *dst_mac,
                        const struct dhcp_request *req, uint8_t msg_type,
                        const uint8_t yiaddr[4]);

/* Record a reply after the transport send. send_rc is 0 on success.
 * Updates counters and emits the UI events. */
void dhcp_account_reply(struct lan_play *lp, const uint8_t *mac, uint8_t msg,
                        const uint8_t yiaddr[4], int send_rc);

/* Inspect one captured Wi-Fi frame. Returns true when the frame was a DHCP
 * client packet and has been handled (the caller must not relay it). */
bool dhcp_server_consume(struct lan_play *lp, const uint8_t *frame, size_t len);

/* Reset lease state; called once from lan_play_init. */
void dhcp_server_init(struct lan_play *lp);

#endif // _DHCP_SERVER_H_
