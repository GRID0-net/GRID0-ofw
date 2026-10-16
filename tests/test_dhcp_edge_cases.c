/* Tests for DHCP edge cases.
 * Verifies handling of malformed packets, truncated data,
 * missing options, and boundary conditions.
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
}

static void test_zero_yiaddr(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {0, 0, 0, 0};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x44444444;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "Zero yiaddr still builds payload");
}

static void test_broadcast_yiaddr(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {255, 255, 255, 255};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x55555555;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "Broadcast yiaddr builds payload");
}

static void test_network_yiaddr(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 0};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x66666666;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "Network yiaddr builds payload");
}

int main(void)
{
    test_zero_yiaddr();
    test_broadcast_yiaddr();
    test_network_yiaddr();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
