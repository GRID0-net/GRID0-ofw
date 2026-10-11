#pragma once
#include <stdbool.h>

#ifdef _WIN32
/* Manage the Windows Firewall rule that blocks the PC's own hotspot DHCP
 * server (outbound UDP/67) so the relay's DHCP server is the only one
 * answering the Switch. Enabling does delete-then-add, which also cleans
 * up duplicates left by manual PowerShell use or a crash.
 * The relay's own DHCP answers travel via Npcap injection and are not
 * affected by the firewall. Requires admin (the relay already needs it
 * for Npcap though). */
void winfw_set_hotspot_dhcp_block(bool enable);
/* True if the block rule currently exists. */
bool winfw_hotspot_dhcp_block_active(void);
/* Inbound allow for the relay's socket DHCP server (UDP/67). The pcap path
 * bypasses the firewall via Npcap injection, but the socket fast path goes
 * through the host stack. Requires admin (the relay already needs it). */
void winfw_set_dhcp_allow(bool enable);
#else
static inline void winfw_set_hotspot_dhcp_block(bool enable) { (void)enable; }
static inline bool winfw_hotspot_dhcp_block_active(void) { return false; }
static inline void winfw_set_dhcp_allow(bool enable) { (void)enable; }
#endif
