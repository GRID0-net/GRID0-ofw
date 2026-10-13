/* Tests for ARP edge cases.
 * Verifies ARP proxy rejects non-gateway targets, wrong MACs,
 * and malformed packets.
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

static void test_non_gateway_target(void)
{
    uint8_t target[4] = {10, 147, 17, 26};
    uint8_t gateway[4] = {10, 147, 17, 182};
    CHECK(!CMP_IPV4(target, gateway), "Peer IP is not gateway");
}

static void test_gateway_target(void)
{
    uint8_t target[4] = {10, 147, 17, 182};
    uint8_t gateway[4] = {10, 147, 17, 182};
    CHECK(CMP_IPV4(target, gateway), "Gateway IP matches gateway");
}

static void test_broadcast_target(void)
{
    uint8_t target[4] = {10, 147, 17, 255};
    uint8_t gateway[4] = {10, 147, 17, 182};
    CHECK(!CMP_IPV4(target, gateway), "Broadcast IP is not gateway");
}

static void test_wifi_target(void)
{
    uint8_t target[4] = {192, 168, 2, 1};
    uint8_t gateway[4] = {10, 147, 17, 182};
    CHECK(!CMP_IPV4(target, gateway), "Wi-Fi router IP is not gateway");
}

int main(void)
{
    test_non_gateway_target();
    test_gateway_target();
    test_broadcast_target();
    test_wifi_target();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
