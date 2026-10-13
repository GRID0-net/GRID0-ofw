/* Tests for ARP cache operations.
 * Verifies arp list init, set, get, has, and for_each.
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

static void test_arp_list_init(void)
{
    struct arp_item list[16];
    arp_list_init(list);
    CHECK(list[0].ip == 0, "ARP list init clears first entry");
    CHECK(list[15].ip == 0, "ARP list init clears last entry");
}

static void test_arp_set_get(void)
{
    struct packet_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    arp_list_init(ctx.arp_list);
    uint8_t mac[6] = {0x02, 0x01, 0x02, 0x03, 0x04, 0x05};
    uint8_t ip[4] = {192, 168, 2, 100};
    bool set = arp_set(&ctx, mac, ip);
    CHECK(set, "ARP set returns true");
    CHECK(arp_has_ip(&ctx, ip), "ARP has IP after set");
    uint8_t out_mac[6] = {0};
    bool got = arp_get_mac_by_ip(&ctx, out_mac, ip);
    CHECK(got, "ARP get MAC returns true");
    CHECK(memcmp(out_mac, mac, 6) == 0, "ARP get MAC matches set MAC");
}

static void test_arp_missing_ip(void)
{
    struct packet_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    arp_list_init(ctx.arp_list);
    uint8_t ip[4] = {192, 168, 2, 200};
    CHECK(!arp_has_ip(&ctx, ip), "ARP has IP returns false for missing IP");
    uint8_t out_mac[6] = {0};
    CHECK(!arp_get_mac_by_ip(&ctx, out_mac, ip), "ARP get MAC returns false for missing IP");
}

static void test_arp_update(void)
{
    struct packet_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    arp_list_init(ctx.arp_list);
    uint8_t mac1[6] = {0x02, 0x01, 0x02, 0x03, 0x04, 0x05};
    uint8_t mac2[6] = {0x02, 0x11, 0x12, 0x13, 0x14, 0x15};
    uint8_t ip[4] = {192, 168, 2, 100};
    arp_set(&ctx, mac1, ip);
    arp_set(&ctx, mac2, ip);
    uint8_t out_mac[6] = {0};
    arp_get_mac_by_ip(&ctx, out_mac, ip);
    CHECK(memcmp(out_mac, mac2, 6) == 0, "ARP update replaces MAC");
}

int main(void)
{
    test_arp_list_init();
    test_arp_set_get();
    test_arp_missing_ip();
    test_arp_update();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
