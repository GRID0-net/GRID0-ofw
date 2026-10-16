/* Tests for IP subnet and destination classification.
 * Verifies ip_in_subnet, is_zerotier_destination,
 * is_nintendo_lan_broadcast.
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

static void test_ip_in_subnet(void)
{
    uint8_t ip1[4] = {192, 168, 2, 100};
    uint8_t net[4] = {192, 168, 2, 0};
    uint8_t mask[4] = {255, 255, 255, 0};
    CHECK(ip_in_subnet(ip1, net, mask), "192.168.2.100 is in 192.168.2.0/24");
    uint8_t ip2[4] = {192, 168, 3, 100};
    CHECK(!ip_in_subnet(ip2, net, mask), "192.168.3.100 is not in 192.168.2.0/24");
}

static void test_ip_in_subnet_16(void)
{
    uint8_t ip1[4] = {10, 147, 17, 100};
    uint8_t net[4] = {10, 147, 0, 0};
    uint8_t mask[4] = {255, 255, 0, 0};
    CHECK(ip_in_subnet(ip1, net, mask), "10.147.17.100 is in 10.147.0.0/16");
    uint8_t ip2[4] = {10, 148, 17, 100};
    CHECK(!ip_in_subnet(ip2, net, mask), "10.148.17.100 is not in 10.147.0.0/16");
}

static void test_zerotier_dest(void)
{
    struct lan_play lp;
    memset(&lp, 0, sizeof(lp));
    memcpy(lp.zerotier_ip, "\x0a\x93\x11\xb6", 4);
    memcpy(lp.zerotier_netmask, "\xff\xff\xff\x00", 4);
    uint8_t zt_ip[4] = {10, 147, 17, 26};
    uint8_t wifi_ip[4] = {192, 168, 2, 100};
    CHECK(is_zerotier_destination(&lp, zt_ip), "10.147.17.26 is ZeroTier dest");
    CHECK(!is_zerotier_destination(&lp, wifi_ip), "192.168.2.100 is not ZeroTier dest");
}

static void test_nintendo_broadcast(void)
{
    struct lan_play lp;
    memset(&lp, 0, sizeof(lp));
    memcpy(lp.zerotier_broadcast_ip, "\x0a\x93\x11\xff", 4);
    uint8_t bcast[4] = {10, 147, 17, 255};
    uint8_t normal[4] = {10, 147, 17, 26};
    CHECK(is_nintendo_lan_broadcast(&lp, bcast), "10.147.17.255 is Nintendo broadcast");
    CHECK(!is_nintendo_lan_broadcast(&lp, normal), "10.147.17.26 is not broadcast");
}

int main(void)
{
    test_ip_in_subnet();
    test_ip_in_subnet_16();
    test_zerotier_dest();
    test_nintendo_broadcast();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
