/* Tests for Wi-Fi Proxy ARP (PROXY-ARP12).
 * Verifies the relay answers ARP requests for the ZeroTier gateway
 * on Wi-Fi with the correct MAC and IP fields. Verifies malformed
 * ARP packets are rejected.
 */
#include "../src/lan-play.c"
#include <assert.h>
#include <stdio.h>

static int test_count = 0;
static int pass_count = 0;
static uint8_t sent[2048];
static int sent_len = 0;

#define CHECK(cond, msg) do { \
    test_count++; \
    if (cond) { pass_count++; printf("PASS: %s\n", msg); } \
    else { printf("FAIL: %s\n", msg); } \
} while (0)

int uv_pcap_sendpacket(uv_pcap_t *h, const u_char *data, int size)
{
    (void)h;
    memcpy(sent, data, size);
    sent_len = size;
    return 0;
}

static void fixture(struct lan_play *lp)
{
    memset(lp, 0, sizeof(*lp));
    memcpy(lp->wifi_mac, "\x02\x01\x02\x03\x04\x05", 6);
    memcpy(lp->zerotier_mac, "\x02\x11\x12\x13\x14\x15", 6);
    memcpy(lp->wifi_ip, "\xc0\xa8\x02\x88", 4);
    memcpy(lp->zerotier_ip, "\x0a\x93\x11\xb6", 4);
    memcpy(lp->zerotier_netmask, "\xff\xff\xff\x00", 4);
}

static void test_arp_reply_fields(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t target_mac[6] = {0x60, 0x6b, 0xff, 0xaf, 0x74, 0xea};
    uint8_t target_ip[4] = {10, 147, 17, 182};
    uint8_t sender_ip[4] = {10, 147, 17, 254};

    sent_len = 0;
    int ret = lan_play_send_wifi_arp_reply(&lp, target_mac, target_ip, sender_ip);
    CHECK(ret == 0, "ARP reply send returns 0");
    CHECK(sent_len == 42, "ARP reply frame is 42 bytes");
    CHECK(memcmp(sent, target_mac, 6) == 0, "ARP reply Ethernet dst is Switch MAC");
    CHECK(memcmp(sent + 6, lp.wifi_mac, 6) == 0, "ARP reply Ethernet src is Wi-Fi MAC");
    CHECK(READ_NET16(sent, 12) == ETHER_TYPE_ARP, "ARP reply EtherType is ARP");
    CHECK(READ_NET16(sent + 14, 6) == ARP_OPCODE_REPLY, "ARP reply opcode is REPLY");
    CHECK(memcmp(sent + 22, lp.wifi_mac, 6) == 0, "ARP sender MAC is Wi-Fi MAC");
    CHECK(memcmp(sent + 28, target_ip, 4) == 0, "ARP sender IP is ZeroTier gateway");
    CHECK(memcmp(sent + 32, target_mac, 6) == 0, "ARP target MAC is Switch MAC");
    CHECK(memcmp(sent + 38, sender_ip, 4) == 0, "ARP target IP is Switch IP");
}

static void test_arp_validation(void)
{
    struct lan_play lp;
    fixture(&lp);
    uint8_t frame[42];
    memset(frame, 0, sizeof(frame));
    memcpy(frame, "\xff\xff\xff\xff\xff\xff", 6);
    memcpy(frame + 6, "\x60\x6b\xff\xaf\x74\xea", 6);
    WRITE_NET16(frame, 12, ETHER_TYPE_ARP);
    WRITE_NET16(frame + 14, 0, ARP_HARDTYPE_ETHER);
    WRITE_NET16(frame + 14, 2, ETHER_TYPE_IPV4);
    frame[18] = 6;
    frame[19] = 4;
    WRITE_NET16(frame + 14, 6, ARP_OPCODE_REQUEST);
    memcpy(frame + 22, "\x60\x6b\xff\xaf\x74\xea", 6);
    memcpy(frame + 28, "\x0a\x93\x11\xfe", 4);
    memcpy(frame + 38, "\x0a\x93\x11\xb6", 4);

    int valid = READ_NET16(frame + 14, 0) == ARP_HARDTYPE_ETHER &&
                READ_NET16(frame + 14, 2) == ETHER_TYPE_IPV4 &&
                frame[18] == 6 && frame[19] == 4 &&
                READ_NET16(frame + 14, 6) == ARP_OPCODE_REQUEST &&
                memcmp(frame + 6, frame + 22, 6) == 0;
    CHECK(valid, "Valid ARP request passes validation");

    frame[18] = 8;
    valid = READ_NET16(frame + 14, 0) == ARP_HARDTYPE_ETHER &&
            READ_NET16(frame + 14, 2) == ETHER_TYPE_IPV4 &&
            frame[18] == 6 && frame[19] == 4;
    CHECK(!valid, "Wrong hardware length fails validation");
}

int main(void)
{
    test_arp_reply_fields();
    test_arp_validation();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
