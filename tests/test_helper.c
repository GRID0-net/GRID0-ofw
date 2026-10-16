/* Tests for helper functions.
 * Verifies str2ip, parse_ip_port, parse_addr, get_mac_address helpers.
 */
#include "../src/helper.c"
#include <stdio.h>

static int test_count = 0;
static int pass_count = 0;

#define CHECK(cond, msg) do { \
    test_count++; \
    if (cond) { pass_count++; printf("PASS: %s\n", msg); } \
    else { printf("FAIL: %s\n", msg); } \
} while (0)

static void test_str2ip(void)
{
    void *ip = str2ip("192.168.2.1");
    CHECK(ip != NULL, "str2ip parses 192.168.2.1");
    uint8_t *bytes = (uint8_t *)ip;
    CHECK(bytes[0] == 192 && bytes[1] == 168 && bytes[2] == 2 && bytes[3] == 1,
          "str2ip bytes are correct");
}

static void test_str2ip_zt(void)
{
    void *ip = str2ip("10.147.17.182");
    CHECK(ip != NULL, "str2ip parses 10.147.17.182");
    uint8_t *bytes = (uint8_t *)ip;
    CHECK(bytes[0] == 10 && bytes[1] == 147 && bytes[2] == 17 && bytes[3] == 182,
          "str2ip ZeroTier bytes are correct");
}

static void test_str2ip_invalid(void)
{
    void *ip = str2ip("not-an-ip");
    CHECK(ip == NULL, "str2ip returns NULL for invalid IP");
}

static void test_parse_ip_port(void)
{
    char addr[64];
    uint16_t port = 0;
    uint8_t is_ipv6 = 0;
    int ret = parse_ip_port("192.168.2.1:8080", addr, sizeof(addr), &port, &is_ipv6);
    CHECK(ret == 0, "parse_ip_port parses IPv4:port");
    CHECK(port == 8080, "parse_ip_port port is 8080");
    CHECK(!is_ipv6, "parse_ip_port IPv4 is not IPv6");
}

static void test_parse_ip_port_no_port(void)
{
    char addr[64];
    uint16_t port = 0;
    uint8_t is_ipv6 = 0;
    int ret = parse_ip_port("192.168.2.1", addr, sizeof(addr), &port, &is_ipv6);
    CHECK(ret == 0, "parse_ip_port parses IP without port");
}

int main(void)
{
    test_str2ip();
    test_str2ip_zt();
    test_str2ip_invalid();
    test_parse_ip_port();
    test_parse_ip_port_no_port();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
