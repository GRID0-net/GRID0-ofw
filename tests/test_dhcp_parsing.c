/* Tests for DHCP parsing.
 * Verifies dhcp_parse_payload handles DISCOVER, REQUEST, RELEASE.
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

static void build_dhcp(uint8_t *dhcp, uint32_t xid, uint8_t msg_type, const uint8_t *chaddr)
{
    memset(dhcp, 0, DHCP_MIN_LEN);
    dhcp[0] = 1;
    dhcp[1] = 1;
    dhcp[2] = 6;
    dhcp[3] = 0;
    dhcp[4] = (xid >> 24) & 0xFF;
    dhcp[5] = (xid >> 16) & 0xFF;
    dhcp[6] = (xid >> 8) & 0xFF;
    dhcp[7] = xid & 0xFF;
    memcpy(dhcp + 28, chaddr, 6);
    dhcp[236] = 99;
    dhcp[237] = 130;
    dhcp[238] = 83;
    dhcp[239] = 99;
    dhcp[240] = 53;
    dhcp[241] = 1;
    dhcp[242] = msg_type;
    dhcp[243] = 255;
}

static void test_parse_discover(void)
{
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t chaddr[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    build_dhcp(dhcp, 0x12345678, 1, chaddr);
    struct dhcp_request req;
    bool ok = dhcp_parse_payload(dhcp, DHCP_MIN_LEN, &req);
    CHECK(ok, "DISCOVER parses");
    CHECK(req.msg_type == 1, "DISCOVER msg type is 1");
    CHECK(req.xid == 0x12345678, "DISCOVER xid matches");
    CHECK(memcmp(req.chaddr, chaddr, 6) == 0, "DISCOVER chaddr matches");
}

static void test_parse_request(void)
{
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t chaddr[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    build_dhcp(dhcp, 0x87654321, 3, chaddr);
    struct dhcp_request req;
    bool ok = dhcp_parse_payload(dhcp, DHCP_MIN_LEN, &req);
    CHECK(ok, "REQUEST parses");
    CHECK(req.msg_type == 3, "REQUEST msg type is 3");
}

static void test_parse_release(void)
{
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t chaddr[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    build_dhcp(dhcp, 0x11111111, 7, chaddr);
    struct dhcp_request req;
    bool ok = dhcp_parse_payload(dhcp, DHCP_MIN_LEN, &req);
    CHECK(ok, "RELEASE parses");
    CHECK(req.msg_type == 7, "RELEASE msg type is 7");
}

static void test_parse_short(void)
{
    uint8_t dhcp[100];
    memset(dhcp, 0, sizeof(dhcp));
    struct dhcp_request req;
    bool ok = dhcp_parse_payload(dhcp, sizeof(dhcp), &req);
    CHECK(!ok, "Short packet fails parse");
}

static void test_parse_bad_cookie(void)
{
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t chaddr[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    build_dhcp(dhcp, 0x12345678, 1, chaddr);
    dhcp[236] = 0;
    struct dhcp_request req;
    bool ok = dhcp_parse_payload(dhcp, DHCP_MIN_LEN, &req);
    CHECK(!ok, "Bad magic cookie fails parse");
}

int main(void)
{
    test_parse_discover();
    test_parse_request();
    test_parse_release();
    test_parse_short();
    test_parse_bad_cookie();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
