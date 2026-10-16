/* Tests for SHA1.
 * Verifies SHA1 hash of known test vectors.
 */
#include "../src/sha1.c"
#include <stdio.h>

static int test_count = 0;
static int pass_count = 0;

#define CHECK(cond, msg) do { \
    test_count++; \
    if (cond) { pass_count++; printf("PASS: %s\n", msg); } \
    else { printf("FAIL: %s\n", msg); } \
} while (0)

static void test_sha1_empty(void)
{
    SHA1_CTX ctx;
    uint8_t digest[20];
    SHA1Init(&ctx);
    SHA1Final(digest, &ctx);
    uint8_t expected[20] = {
        0xda, 0x39, 0xa3, 0xee, 0x5e, 0x6b, 0x4b, 0x0d,
        0x32, 0x55, 0xbf, 0xef, 0x95, 0x60, 0x18, 0x90,
        0xaf, 0xd8, 0x07, 0x09
    };
    CHECK(memcmp(digest, expected, 20) == 0, "SHA1 of empty string matches");
}

static void test_sha1_abc(void)
{
    SHA1_CTX ctx;
    uint8_t digest[20];
    SHA1Init(&ctx);
    SHA1Update(&ctx, (uint8_t *)"abc", 3);
    SHA1Final(digest, &ctx);
    uint8_t expected[20] = {
        0xa9, 0x99, 0x3e, 0x36, 0x47, 0x06, 0x81, 0x6a,
        0xba, 0x3e, 0x25, 0x71, 0x78, 0x50, 0xc2, 0x6c,
        0x9c, 0xd0, 0xd8, 0x9d
    };
    CHECK(memcmp(digest, expected, 20) == 0, "SHA1 of abc matches");
}

static void test_sha1_long(void)
{
    SHA1_CTX ctx;
    uint8_t digest[20];
    SHA1Init(&ctx);
    SHA1Update(&ctx, (uint8_t *)"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", 56);
    SHA1Final(digest, &ctx);
    uint8_t expected[20] = {
        0x84, 0x98, 0x3e, 0x44, 0x1c, 0x3b, 0xd2, 0x6e,
        0xba, 0xae, 0x4a, 0xa1, 0xf9, 0x51, 0x29, 0xe5,
        0xe5, 0x46, 0x70, 0xf1
    };
    CHECK(memcmp(digest, expected, 20) == 0, "SHA1 of 56 char string matches");
}

int main(void)
{
    test_sha1_empty();
    test_sha1_abc();
    test_sha1_long();
    printf("\n%d/%d tests passed\n", pass_count, test_count);
    return pass_count == test_count ? 0 : 1;
}
