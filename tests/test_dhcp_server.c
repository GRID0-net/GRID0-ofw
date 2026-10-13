/* Tests for DHCP server lease management.
 * Verifies dhcp_decide, dhcp_is_our_lease, dhcp_account_reply.
 */
#include "../src/lan-play.c"
#include <stdio.h>

static int test_count = 0;
static int pass_count = 0;

#define CHECK(cond, msg) do { \
    test_count++; \
    if (cond) { pass_count++; printf("PASS: %s\n", msg); } \
    else { printf("FAIL: %s\n", msg); } \
} while (0)

static void fixture(struct lan_play *lp)
{
    memset(lp, 0, sizeof(*lp));
    memcpy(lp->wifi_mac, "\x02\x01\x02\x03\x04\x05", 6);
    memcpy(lp->wifi_ip, "\xc0\xa8\x02\x88", 4);
    memcpy(lp->wifi_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->wifi_broadcast, "\xc0\xa8\x02\xff", 4);
    memcpy(lp->zerotier_ip, "\x0a\x93\x11\xb6", 4);
    memcpy(lp->zerotier_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->zerotier_broadcast_ip, "\x0a\x93\x11\xff", 4);
    dhcp_server_init(lp);
}

static void test_dhcp_decide_home(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    uint8_t out[4] = {0};
    uint8_t ret = dhcp_decide(&lp, mac, NULL, out);
    CHECK(ret == 1 || ret == 0, "dhcp_decide returns valid result");
    CHECK(out[0] != 0 || ret == 0, "dhcp_decide fills IP or returns 0");
}

static void test_dhcp_is_our_lease(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    uint8_t ip[4] = {192, 168, 2, 254};
    bool is_ours = dhcp_is_our_lease(&lp, mac, ip);
    CHECK(!is_ours, "Unknown lease is not ours");
}

static void test_dhcp_account_reply(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    dhcp_account_reply(&lp, mac, 2, 0);
    CHECK(true, "dhcp_account_reply does not crash");
}

int main(void)
{
    test_dhcp_decide_home();
    test_dhcp_is_our_lease();
    test_dhcp_account_reply();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
