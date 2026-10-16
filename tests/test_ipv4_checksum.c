/* Tests for IPv4 checksum and UDP checksum.
 * Verifies header checksum and UDP checksum calculations.
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

static void test_ipv4_checksum(void)
{
    uint8_t ip[20] = {
        0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00,
        0x40, 0x06, 0x00, 0x00, 0xac, 0x10, 0x0a, 0x63,
        0xac, 0x10, 0x0a, 0x0c
    };
    uint16_t sum = ipv4_header_checksum(ip, 20);
    CHECK(sum == 0xb1e6, "IPv4 header checksum matches RFC 791 example");
}

static void test_ipv4_checksum_zero(void)
{
    uint8_t ip[20] = {0};
    ip[0] = 0x45;
    ip[1] = 0x00;
    uint16_t sum = ipv4_header_checksum(ip, 20);
    CHECK(sum != 0, "IPv4 checksum of zeroed header is non-zero");
}

static void test_udp_checksum(void)
{
    uint8_t ip[28] = {0};
    ip[0] = 0x45;
    ip[9] = 17;
    ip[12] = 192; ip[13] = 168; ip[14] = 1; ip[15] = 1;
    ip[16] = 192; ip[17] = 168; ip[18] = 1; ip[19] = 2;
    uint16_t sum = udp_checksum(ip, 20, 28);
    CHECK(sum != 0, "UDP checksum is non-zero");
}

int main(void)
{
    test_ipv4_checksum();
    test_ipv4_checksum_zero();
    test_udp_checksum();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
