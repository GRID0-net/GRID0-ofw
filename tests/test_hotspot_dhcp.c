/* Tests for hotspot mode DHCP.
 * Verifies DHCP in hotspot mode offers 10.147.17.x addresses
 * with the PC hotspot IP as gateway and server.
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

static void fixture_hotspot(struct lan_play *lp)
{
    memset(lp, 0, sizeof(*lp));
    memcpy(lp->wifi_mac, "\x02\x01\x02\x03\x04\x05", 6);
    memcpy(lp->wifi_ip, "\x0a\x93\x11\x01", 4);
    memcpy(lp->wifi_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->wifi_broadcast, "\x0a\x93\x11\xff", 4);
    memcpy(lp->zerotier_ip, "\x0a\x93\x11\xb6", 4);
    memcpy(lp->zerotier_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->zerotier_broadcast_ip, "\x0a\x93\x11\xff", 4);
}

static void test_hotspot_offer(void)
{
    struct lan_play lp;
    fixture_hotspot(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 100};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x33333333;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "Hotspot offer payload length");
    CHECK(dhcp[16] == 10 && dhcp[17] == 147 && dhcp[18] == 17 && dhcp[19] == 100,
          "Hotspot offer yiaddr is 10.147.17.100");
    CHECK(dhcp[20] == 10 && dhcp[21] == 147 && dhcp[22] == 17 && dhcp[23] == 182,
          "Hotspot offer siaddr is ZeroTier IP");
}

static void test_hotspot_subnet(void)
{
    struct lan_play lp;
    fixture_hotspot(&lp);
    uint8_t ip1[4] = {10, 147, 17, 50};
    uint8_t ip2[4] = {192, 168, 2, 50};
    CHECK(IS_SUBNET(ip1, lp.zerotier_ip, lp.zerotier_netmask),
          "10.147.17.50 is in ZeroTier subnet");
    CHECK(!IS_SUBNET(ip2, lp.zerotier_ip, lp.zerotier_netmask),
          "192.168.2.50 is not in ZeroTier subnet");
}

int main(void)
{
    test_hotspot_offer();
    test_hotspot_subnet();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
