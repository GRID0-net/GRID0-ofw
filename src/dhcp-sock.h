#pragma once

#include <stdbool.h>

struct lan_play;

/* Socket fast path for the DHCP server.
 *
 * Listens on UDP/67 directly instead of via the pcap capture path, so a
 * Switch DISCOVER is answered without the capture timer and Npcap round
 * trips. Measured ~0.3ms DISCOVER-to-OFFER on a laptop where the pcap path
 * loses the race to the home router. The reply payload is identical to the
 * pcap path's (see dhcp_build_payload); only the transport differs, and the
 * Switch accepts it the same way.
 *
 * Falls back to the pcap path when UDP/67 cannot be bound (Windows hotspot
 * ICS holds it, or another relay instance is running). Unicast renewals to
 * the fake gateway address are still answered by the pcap path, which sees
 * packets the socket cannot.
 */

/* Start the socket server on the relay's event loop. Returns 0 when the
 * fast path is active (lp->dhcp_sock_active set), -1 on failure. */
int dhcp_sock_start(struct lan_play *lp);

/* Stop the socket server; the pcap path takes over answering. */
void dhcp_sock_stop(struct lan_play *lp);
