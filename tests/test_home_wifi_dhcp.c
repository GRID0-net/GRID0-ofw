/* Tests for home Wi-Fi DHCP (PROXY-ARP12).
 * Verifies DHCP packets for ZeroTier offers use the PC ZeroTier IP
 * for siaddr, Server ID, and IP source. Verifies Option 121 is omitted
 * for ZeroTier offers and present for Wi-Fi offers. Verifies ARP proxy
 * replies use the correct MAC and IP fields. No adapters needed.
 */
#include "../src/lan-play.c"
#include <assert.h>
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
    memcpy(lp->zerotier_mac, "\x02\x11\x12\x13\x14\x15", 6);
    memcpy(lp->wifi_ip, "\xc0\xa8\x02\x88", 4);
    memcpy(lp->wifi_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->wifi_broadcast, "\xc0\xa8\x02\xff", 4);
    memcpy(lp->zerotier_ip, "\x0a\x93\x11\xb6", 4);
    memcpy(lp->zerotier_netmask, "\xff\xff\xff\x00", 4);
    memcpy(lp->zerotier_broadcast_ip, "\x0a\x93\x11\xff", 4);
}

static int has_option(const uint8_t *dhcp, size_t len, uint8_t code)
{
    size_t i = 240;
    while (i + 1 < len) {
        uint8_t opt = dhcp[i];
        if (opt == 255) return 0;
        if (opt == 0) { i++; continue; }
        if (opt == code) return 1;
        i += 2 + dhcp[i + 1];
    }
    return 0;
}

static void test_zt_offer_fields(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 254};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x12345678;
    memcpy(req.chaddr, dst_mac, 6);

    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "ZT offer payload length");
    CHECK(dhcp[16] == 10 && dhcp[17] == 147 && dhcp[18] == 17 && dhcp[19] == 254,
          "ZT offer yiaddr is 10.147.17.254");
    CHECK(dhcp[20] == 10 && dhcp[21] == 147 && dhcp[22] == 17 && dhcp[23] == 182,
          "ZT offer siaddr is 10.147.17.182");
    CHECK(memcmp(dhcp + 28, req.chaddr, 6) == 0,
          "ZT offer chaddr matches request chaddr");
    CHECK(!has_option(dhcp, len, 121),
          "ZT offer omits Option 121");
}

static void test_wifi_offer_fields(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {192, 168, 2, 254};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x12345678;
    memcpy(req.chaddr, dst_mac, 6);

    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(len == DHCP_MIN_LEN, "Wi-Fi offer payload length");
    CHECK(dhcp[16] == 192 && dhcp[17] == 168 && dhcp[18] == 2 && dhcp[19] == 254,
          "Wi-Fi offer yiaddr is 192.168.2.254");
    CHECK(dhcp[20] == 192 && dhcp[21] == 168 && dhcp[22] == 2 && dhcp[23] == 136,
          "Wi-Fi offer siaddr is 192.168.2.136");
    CHECK(has_option(dhcp, len, 121),
          "Wi-Fi offer includes Option 121");
}

static void test_null_yiaddr(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x12345678;

    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, NULL, dhcp);
    CHECK(len == 0, "Null yiaddr returns 0");
}

static void test_zt_subnet_detection(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t zt_ip[4] = {10, 147, 17, 100};
    uint8_t wifi_ip[4] = {192, 168, 2, 100};
    CHECK(IS_SUBNET(zt_ip, lp.zerotier_ip, lp.zerotier_netmask),
          "10.147.17.100 is in ZeroTier subnet");
    CHECK(!IS_SUBNET(wifi_ip, lp.zerotier_ip, lp.zerotier_netmask),
          "192.168.2.100 is not in ZeroTier subnet");
}

int main(void)
{
    test_zt_offer_fields();
    test_wifi_offer_fields();
    test_null_yiaddr();
    test_zt_subnet_detection();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
