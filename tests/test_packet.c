/* Tests for packet handling.
 * Verifies packet parsing, MAC handling, and frame construction.
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

static void test_packet_mac_copy(void)
{
    struct packet_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    uint8_t mac[6] = {0x02, 0x01, 0x02, 0x03, 0x04, 0x05};
    packet_set_mac(&ctx, mac);
    CHECK(ctx.mac == mac, "packet_set_mac stores MAC pointer");
}

static void test_packet_ip_copy(void)
{
    struct packet_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    uint8_t ip[4] = {192, 168, 2, 100};
    memcpy(ctx.ip, ip, 4);
    CHECK(memcmp(ctx.ip, ip, 4) == 0, "packet_ctx IP copy works");
}

static void test_ether_frame(void)
{
    uint8_t frame[14];
    memset(frame, 0, sizeof(frame));
    memcpy(frame, "\xff\xff\xff\xff\xff\xff", 6);
    memcpy(frame + 6, "\x02\x01\x02\x03\x04\x05", 6);
    WRITE_NET16(frame, 12, ETHER_TYPE_IPV4);
    CHECK(READ_NET16(frame, 12) == ETHER_TYPE_IPV4, "EtherType read/write works");
    CHECK(memcmp(frame, "\xff\xff\xff\xff\xff\xff", 6) == 0, "Ethernet dst MAC is broadcast");
}

static void test_arp_frame(void)
{
    uint8_t frame[42];
    memset(frame, 0, sizeof(frame));
    WRITE_NET16(frame, 12, ETHER_TYPE_ARP);
    CHECK(READ_NET16(frame, 12) == ETHER_TYPE_ARP, "ARP EtherType works");
}

int main(void)
{
    test_packet_mac_copy();
    test_packet_ip_copy();
    test_ether_frame();
    test_arp_frame();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
