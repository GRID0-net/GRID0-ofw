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
#else
static inline void winfw_set_hotspot_dhcp_block(bool enable) { (void)enable; }
static inline bool winfw_hotspot_dhcp_block_active(void) { return false; }
#endif
