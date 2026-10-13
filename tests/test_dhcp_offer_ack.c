/* Tests for DHCP OFFER vs ACK distinction.
 * Verifies OFFER and ACK have correct message types and fields.
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
    memcpy(lp->zerotier_broadcast_ip, "\x0a\x93\x11\xff", 4);
}

static uint8_t get_msg_type(const uint8_t *dhcp, size_t len)
{
    size_t i = 240;
    while (i + 1 < len) {
        uint8_t opt = dhcp[i];
        if (opt == 255) return 0;
        if (opt == 0) { i++; continue; }
        if (opt == 53) return dhcp[i + 2];
        i += 2 + dhcp[i + 1];
    }
    return 0;
}

static void test_offer_type(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 254};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x11111111;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    CHECK(get_msg_type(dhcp, len) == 2, "OFFER has message type 2");
}

static void test_ack_type(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 254};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0x22222222;
    memcpy(req.chaddr, dst_mac, 6);
    size_t len = dhcp_build_payload(&lp, dst_mac, &req, 5, yiaddr, dhcp);
    CHECK(get_msg_type(dhcp, len) == 5, "ACK has message type 5");
}

static void test_xid_preserved(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t dhcp[DHCP_MIN_LEN];
    uint8_t yiaddr[4] = {10, 147, 17, 254};
    uint8_t dst_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    struct dhcp_request req;
    memset(&req, 0, sizeof(req));
    req.xid = 0xAABBCCDD;
    memcpy(req.chaddr, dst_mac, 6);
    dhcp_build_payload(&lp, dst_mac, &req, 2, yiaddr, dhcp);
    uint32_t xid = (dhcp[4] << 24) | (dhcp[5] << 16) | (dhcp[6] << 8) | dhcp[7];
    CHECK(xid == 0xAABBCCDD, "XID preserved in reply");
}

int main(void)
{
    test_offer_type();
    test_ack_type();
    test_xid_preserved();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
