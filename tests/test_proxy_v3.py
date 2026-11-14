import hashlib
import hmac as hmac_mod
import importlib.util
import ipaddress
import os
import shutil
import sys
import tempfile
import unittest
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PC_COPIES = [os.path.join(ROOT, "desktop", "proxy", "zttestproxyv228.py"),
             os.path.join(ROOT, "proxy", "zttestproxyv228.py")]
ANDROID_COPY = os.path.join(ROOT, "android", "app", "src", "main", "python", "zttestproxyv228.py")

KEY_A = bytes.fromhex("00112233445566778899aabbccddeeff")
KEY_B = bytes.fromhex("ffeeddccbbaa99887766554433221100")
WIKI_KEY = bytes.fromhex("78deee82d86875782c40b15278f37815")

WIKI_PAGE = """In LDN and LAN mode, Pia derives encryption keys using a game-specific key.

| Game | Key |
| --- | --- |
| Mario Kart 8 Deluxe | `ABCDEFGHIJKLMNOP` |
| Splatoon 2 | `ee182a63e216cdb1f51ad4bed8cf6508` |
| Splatoon 3 | `78deee82d86875782c40b15278f37815` |
| Super Mario Maker 2 | `667c18475889faab61f93ef1da180971` |
"""

_counter = [0]


def load_copy(src_path, beside_key=KEY_A, env_key=None, home_key=None):
    tmp = tempfile.mkdtemp(prefix="v3test")
    mod_dir = os.path.join(tmp, "mod")
    home = os.path.join(tmp, "home")
    os.makedirs(mod_dir)
    os.makedirs(home)
    dst = os.path.join(mod_dir, "zttestproxyv228.py")
    shutil.copy(src_path, dst)
    if beside_key is not None:
        with open(os.path.join(mod_dir, "game_key.txt"), "w") as fh:
            fh.write(beside_key.hex())
    if home_key is not None:
        with open(os.path.join(home, "game_key.txt"), "w") as fh:
            fh.write(home_key.hex())
    env_file = None
    if env_key is not None:
        env_file = os.path.join(tmp, "env_key.txt")
        with open(env_file, "w") as fh:
            fh.write(env_key.hex())
    old_env = dict(os.environ)
    os.environ["HOME"] = home
    if env_file:
        os.environ["GAME_KEY_FILE"] = env_file
    else:
        os.environ.pop("GAME_KEY_FILE", None)
    _counter[0] += 1
    name = "v3proxy_%d" % _counter[0]
    try:
        spec = importlib.util.spec_from_file_location(name, dst)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
    finally:
        os.environ.clear()
        os.environ.update(old_env)
    mod._test_tmp = tmp
    mod._test_mod_dir = mod_dir
    mod._test_home = home
    return mod


def reload_key(mod, beside=None, env=None, home=None):
    if beside is not None:
        path = os.path.join(mod._test_mod_dir, "game_key.txt")
        if beside is False:
            if os.path.exists(path):
                os.remove(path)
        else:
            with open(path, "w") as fh:
                fh.write(beside.hex())
    old_env = dict(os.environ)
    os.environ["HOME"] = mod._test_home
    home_file = os.path.join(mod._test_home, "game_key.txt")
    if home is None:
        if os.path.exists(home_file):
            os.remove(home_file)
    else:
        with open(home_file, "w") as fh:
            fh.write(home.hex())
    if env is None:
        os.environ.pop("GAME_KEY_FILE", None)
    else:
        env_path = os.path.join(mod._test_tmp, "env_key2.txt")
        with open(env_path, "w") as fh:
            fh.write(env.hex())
        os.environ["GAME_KEY_FILE"] = env_path
    try:
        return mod.load_game_key()
    finally:
        os.environ.clear()
        os.environ.update(old_env)


class FakeSocket:
    def __init__(self):
        self.sent = []

    def sendto(self, data, dest):
        self.sent.append((bytes(data), dest))
        return len(data)


SW_PHONE = "192.168.1.50"
SWITCH = "192.168.1.150"
ZT_PHONE = "10.147.17.50"
HOST = "10.147.17.99"


def make_relay(m):
    import time
    r = m.Relay.__new__(m.Relay)
    r.game_key = KEY_A
    r.game_key_mk8d = m.MK8D_KEY
    r.sw_ip, r.h_ip = SW_PHONE, ZT_PHONE
    r.sw_prefix = r.h_prefix = 24
    r.sw_me, r.h_me = m.ip4(SW_PHONE), m.ip4(ZT_PHONE)
    r.sw_bc, r.h_bc = m.bcast_of(SW_PHONE, 24), m.bcast_of(ZT_PHONE, 24)
    r.sw_net = ipaddress.IPv4Network("192.168.1.0/24")
    r.h_net = ipaddress.IPv4Network("10.147.17.0/24")
    r.delay = {"s2h": 0.0, "h2s": 0.0}
    r.only_switch = None
    r.switch = None
    r.host = None
    r.req_chals = []
    r.host_req_chals = []
    r.mk8d_req_chals = []
    r.mk8d_host_req_chals = []
    r.host_side = None
    r.last_sw_req = None
    r.last_sw_game = "splatoon3"
    r.last_sweep = 0.0
    r.key = None
    r.key_mk8d = None
    r.game = None
    r.tables = None
    r.ip_pairs = None
    r.log = []
    r.queue = []
    r.t0 = time.time()
    r.seen = {}
    r.cap = None
    r.d_sw, r.d_h, r.g_sw, r.g_h = FakeSocket(), FakeSocket(), FakeSocket(), FakeSocket()
    r.d_sw2, r.d_h2 = FakeSocket(), FakeSocket()
    r.gb_sw, r.gb_h = FakeSocket(), FakeSocket()
    return r


def make_browse_request(m, key, bc, chal=b"\x11" * 16, counter=b"\x22" * 8, pt=None):
    from Crypto.Cipher import AES
    if pt is None:
        pt = bytes(range(256))
    k = AES.new(key, AES.MODE_ECB).encrypt(chal)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter).encrypt_and_digest(pt)
    req = bytearray(325)
    req[0] = 0
    req[29:37] = counter
    req[37:53] = chal
    req[53:69] = tag
    req[69:325] = ct
    return bytes(req), chal, pt


def make_browse_reply(m, key, req_chal, bc, off=137, pt=b"\x33" * 16,
                      counter=b"\x44" * 8, rchal=b"\x55" * 16, host_ip=None):
    from Crypto.Cipher import AES
    k = hmac_mod.new(key, rchal + req_chal, hashlib.sha256).digest()[:16]
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter).encrypt_and_digest(pt)
    rep = bytearray(off + 58 + 25)
    rep[0] = 1
    rep[1:5] = (off - 5).to_bytes(4, "big")
    if host_ip is not None:
        rep[60:64] = m.ip4(host_ip)
        rep[120:124] = m.ip4(host_ip)
    rep[off + 2:off + 10] = counter
    rep[off + 10:off + 26] = rchal
    rep[off + 26:off + 42] = tag
    rep[off + 42:off + 58] = ct
    return bytes(rep), pt


def open_reply(m, key, rep, req_chal, bc):
    from Crypto.Cipher import AES
    off = 5 + int.from_bytes(rep[1:5], "big")
    cr = rep[off:off + 58]
    counter, rchal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:58]
    k = hmac_mod.new(key, rchal + req_chal, hashlib.sha256).digest()[:16]
    return AES.new(k, AES.MODE_GCM, nonce=bc + counter).decrypt_and_verify(ct, tag)


def game_header(m):
    hdr = bytearray(29)
    hdr[0:4] = m.MAGIC
    hdr[13:21] = b"\x66" * 8
    return bytes(hdr)


class TestKeyLoading(unittest.TestCase):
    def test_pc_copies_take_beside_key_at_import(self):
        for path in PC_COPIES:
            with self.subTest(path=path):
                m = load_copy(path, beside_key=KEY_A)
                self.assertEqual(m.GAME_KEY, KEY_A)

    def test_android_import_defers_and_loads_beside(self):
        m = load_copy(ANDROID_COPY, beside_key=KEY_A)
        self.assertIsNone(m.GAME_KEY)
        self.assertEqual(reload_key(m, beside=KEY_A), KEY_A)

    def test_env_key_beats_beside_and_home(self):
        for path in PC_COPIES + [ANDROID_COPY]:
            with self.subTest(path=path):
                m = load_copy(path, beside_key=KEY_A, env_key=KEY_B, home_key=KEY_A)
                self.assertEqual(reload_key(m, beside=KEY_A, env=KEY_B, home=KEY_A), KEY_B)

    def test_home_key_used_when_nothing_else(self):
        for path in PC_COPIES + [ANDROID_COPY]:
            with self.subTest(path=path):
                m = load_copy(path, beside_key=None, home_key=KEY_B)
                self.assertEqual(reload_key(m, beside=False, home=KEY_B), KEY_B)

    def test_malformed_beside_falls_through_to_home_pc(self):
        for path in PC_COPIES:
            with self.subTest(path=path):
                m = load_copy(path, beside_key=KEY_A, home_key=KEY_B)
                with open(os.path.join(m._test_mod_dir, "game_key.txt"), "w") as fh:
                    fh.write("not hex at all")
                self.assertEqual(reload_key(m, home=KEY_B), KEY_B)

    def test_no_key_anywhere_pc_fetch_fails_to_systemexit(self):
        for path in PC_COPIES:
            with self.subTest(path=path):
                m = load_copy(path, beside_key=None, home_key=KEY_B)
                orig = urllib.request.urlopen
                urllib.request.urlopen = lambda *a, **k: (_ for _ in ()).throw(OSError("offline"))
                try:
                    with self.assertRaises(SystemExit):
                        reload_key(m, beside=False)
                finally:
                    urllib.request.urlopen = orig

    def test_no_key_anywhere_android_systemexit(self):
        m = load_copy(ANDROID_COPY, beside_key=None)
        with self.assertRaises(SystemExit):
            reload_key(m, beside=False)


class TestKeyFetch(unittest.TestCase):
    def setUp(self):
        self.m = load_copy(PC_COPIES[0], beside_key=None, home_key=KEY_B)
        self.orig = urllib.request.urlopen

    def tearDown(self):
        urllib.request.urlopen = self.orig

    def serve(self, text):
        class Resp:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return text.encode()
        urllib.request.urlopen = lambda *a, **k: Resp()

    def test_fetch_picks_splatoon3_not_splatoon2(self):
        self.serve(WIKI_PAGE)
        self.assertEqual(self.m.fetch_game_key(), WIKI_KEY)

    def test_fetch_without_splatoon3_row_returns_none(self):
        self.serve("| Splatoon 2 | `ee182a63e216cdb1f51ad4bed8cf6508` |\n")
        self.assertIsNone(self.m.fetch_game_key())

    def test_fetch_network_error_returns_none(self):
        urllib.request.urlopen = lambda *a, **k: (_ for _ in ()).throw(OSError("down"))
        self.assertIsNone(self.m.fetch_game_key())

    def test_load_fetches_and_caches_beside_script(self):
        self.serve(WIKI_PAGE)
        key = reload_key(self.m, beside=False)
        self.assertEqual(key, WIKI_KEY)
        cached = os.path.join(self.m._test_mod_dir, "game_key.txt")
        with open(cached) as fh:
            self.assertEqual(fh.read().strip(), WIKI_KEY.hex())

    def test_fetch_uses_raw_wiki_url(self):
        seen = {}

        class Resp:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return WIKI_PAGE.encode()

        def fake(url, timeout=None):
            seen["url"] = url
            return Resp()

        urllib.request.urlopen = fake
        self.m.fetch_game_key()
        self.assertEqual(seen["url"],
                         "https://raw.githubusercontent.com/kinnay/NintendoClientsWiki/master/Pia-Game-Keys.md")


class TestAddressMath(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def test_bcast_24(self):
        self.assertEqual(self.m.bcast_of("192.168.1.50", 24), self.m.ip4("192.168.1.255"))

    def test_bcast_22_diamond_case(self):
        self.assertEqual(self.m.bcast_of("192.168.6.207", 22), self.m.ip4("192.168.7.255"))
        self.assertEqual(self.m.bcast_of("192.168.4.30", 22), self.m.ip4("192.168.7.255"))

    def test_bcast_16_and_25(self):
        self.assertEqual(self.m.bcast_of("10.20.30.40", 16), self.m.ip4("10.20.255.255"))
        self.assertEqual(self.m.bcast_of("192.168.1.100", 25), self.m.ip4("192.168.1.127"))
        self.assertEqual(self.m.bcast_of("192.168.1.200", 25), self.m.ip4("192.168.1.255"))

    def test_bcast_default_prefix_is_24(self):
        self.assertEqual(self.m.bcast_of("172.16.5.5"), self.m.ip4("172.16.5.255"))

    def test_address_pairs_shape(self):
        m = self.m
        pairs = m.address_pairs([(m.ip4(SWITCH), m.ip4(HOST))])
        self.assertIn((m.ip4(SWITCH), m.ip4(HOST)), pairs)
        self.assertIn((m.ip4(SWITCH)[::-1], m.ip4(HOST)[::-1]), pairs)
        self.assertTrue(all(o != n for o, n in pairs))
        port = m.GAME_PORT.to_bytes(2, "little")
        variant = m.ip4(SWITCH)[3:] + m.ip4(SWITCH)[2:3] + port
        self.assertTrue(any(o == variant for o, n in pairs))
        self.assertTrue(any(len(o) == 6 for o, n in pairs))

    def test_address_pairs_drops_identity(self):
        m = self.m
        self.assertEqual(m.address_pairs([(m.ip4(SWITCH), m.ip4(SWITCH))]), [])


class TestRewriteBits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def put(self, body_len, offset, ip, prefix_copy=True):
        x = 0
        x |= int.from_bytes(self.m.ip4(ip), "little") << offset
        if prefix_copy and offset >= 16:
            x |= int.from_bytes(self.m.ip4(ip)[2:4], "little") << (offset - 16)
        return x.to_bytes(body_len, "little")

    def get(self, body, offset):
        x = int.from_bytes(body, "little")
        return (x >> offset) & 0xFFFFFFFF

    def test_unaligned_ip_and_prefix_copy_rewritten(self):
        m = self.m
        body = self.put(80, 150, SWITCH)
        out = m.rewrite_bits(body, [(m.ip4(SWITCH), m.ip4(HOST))])
        self.assertEqual(self.get(out, 150), int.from_bytes(m.ip4(HOST), "little"))
        x = int.from_bytes(out, "little")
        self.assertEqual((x >> 134) & 0xFFFF, int.from_bytes(m.ip4(HOST)[2:4], "little"))

    def test_prefix_copy_left_alone_when_it_does_not_match(self):
        m = self.m
        body = bytearray(self.put(80, 64, SWITCH, prefix_copy=False))
        out = m.rewrite_bits(bytes(body), [(m.ip4(SWITCH), m.ip4(HOST))])
        self.assertEqual(self.get(out, 64), int.from_bytes(m.ip4(HOST), "little"))
        x = int.from_bytes(out, "little")
        self.assertEqual((x >> 48) & 0xFFFF, 0)

    def test_unknown_ip_unchanged(self):
        m = self.m
        body = self.put(80, 40, "203.0.113.7")
        self.assertEqual(m.rewrite_bits(body, [(m.ip4(SWITCH), m.ip4(HOST))]), body)

    def test_empty_pairs_and_short_body_unchanged(self):
        m = self.m
        body = self.put(80, 40, SWITCH)
        self.assertEqual(m.rewrite_bits(body, []), body)
        self.assertEqual(m.rewrite_bits(b"\x01\x02", [(m.ip4(SWITCH), m.ip4(HOST))]), b"\x01\x02")

    def test_two_ips_in_one_body(self):
        m = self.m
        b1 = self.put(120, 8, SWITCH)
        b2 = self.put(120, 300, ZT_PHONE)
        merged = (int.from_bytes(b1, "little") | int.from_bytes(b2, "little")).to_bytes(120, "little")
        out = m.rewrite_bits(merged, [(m.ip4(SWITCH), m.ip4(HOST)), (m.ip4(ZT_PHONE), m.ip4(SW_PHONE))])
        self.assertEqual(self.get(out, 8), int.from_bytes(m.ip4(HOST), "little"))
        self.assertEqual(self.get(out, 300), int.from_bytes(m.ip4(SW_PHONE), "little"))

    def test_other_bits_untouched(self):
        m = self.m
        body = bytearray(self.put(80, 150, SWITCH))
        body[0] = 0xAB
        body[79] = 0xCD
        out = m.rewrite_bits(bytes(body), [(m.ip4(SWITCH), m.ip4(HOST))])
        self.assertEqual(out[0], 0xAB)
        self.assertEqual(out[79], 0xCD)


class TestGameCrypto(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])
        cls.key = bytes(range(16, 32))

    def test_seal_open_round_trip_no_pad(self):
        m = self.m
        body = b"\x01\x02\x03\x04" + b"\x00" * 28
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), body)
        self.assertEqual(m.open_game(self.key, m.ip4(SWITCH), pkt), (body, False))

    def test_seal_open_round_trip_with_pad(self):
        m = self.m
        body = b"hello pia world, not aligned"
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), body)
        out = m.open_game(self.key, m.ip4(SWITCH), pkt)
        self.assertEqual(out[0], body)

    def test_open_detects_zstd_body(self):
        m = self.m
        body = m._CCTX.compress(b"room record payload" * 4)
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), body)
        out = m.open_game(self.key, m.ip4(SWITCH), pkt)
        self.assertTrue(out[1])

    def test_open_rejects_wrong_ip_wrong_key_garbage(self):
        m = self.m
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), b"\x00" * 32)
        self.assertIsNone(m.open_game(self.key, m.ip4(HOST), pkt))
        self.assertIsNone(m.open_game(KEY_B, m.ip4(SWITCH), pkt))
        self.assertIsNone(m.open_game(self.key, m.ip4(SWITCH), b"\x00" * 40))
        self.assertIsNone(m.open_game(self.key, m.ip4(SWITCH), pkt[:20]))
        bad = bytearray(pkt)
        bad[-1] ^= 1
        self.assertIsNone(m.open_game(self.key, m.ip4(SWITCH), bytes(bad)))

    def test_session_key_shape_and_value(self):
        m = self.m
        rep = bytearray(200)
        rep[105:137] = bytes(range(32))
        expect_param = bytearray(range(32))
        expect_param[-1] = (expect_param[-1] + 1) & 0xFF
        expect = hmac_mod.new(KEY_A, bytes(expect_param), hashlib.sha256).digest()[:16]
        self.assertEqual(m.session_key(KEY_A, bytes(rep)), expect)
        rep2 = bytearray(rep)
        rep2[110] ^= 1
        self.assertNotEqual(m.session_key(KEY_A, bytes(rep2)), expect)


class TestReseal(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def test_request_round_trip(self):
        m = self.m
        req, chal, pt = make_browse_request(m, KEY_A, m.ip4("192.168.1.255"))
        out, got_chal = m.reseal_request(KEY_A, req, m.ip4("192.168.1.255"), m.ip4("10.147.17.255"))
        self.assertEqual(got_chal, chal)
        back, _ = m.reseal_request(KEY_A, out, m.ip4("10.147.17.255"), m.ip4("192.168.1.255"))
        self.assertEqual(back, req)

    def test_request_rejects_wrong_shape(self):
        m = self.m
        with self.assertRaises(ValueError):
            m.reseal_request(KEY_A, b"\x00" * 100, m.ip4("192.168.1.255"), m.ip4("10.147.17.255"))
        req, chal, pt = make_browse_request(m, KEY_A, m.ip4("192.168.1.255"))
        bad = bytearray(req)
        bad[0] = 1
        with self.assertRaises(ValueError):
            m.reseal_request(KEY_A, bytes(bad), m.ip4("192.168.1.255"), m.ip4("10.147.17.255"))

    def test_request_tampered_tag_raises(self):
        m = self.m
        req, chal, pt = make_browse_request(m, KEY_A, m.ip4("192.168.1.255"))
        bad = bytearray(req)
        bad[60] ^= 1
        with self.assertRaises(Exception):
            m.reseal_request(KEY_A, bytes(bad), m.ip4("192.168.1.255"), m.ip4("10.147.17.255"))

    def test_reply_round_trip_and_host_rewrite_zones(self):
        m = self.m
        rep, pt = make_browse_reply(m, KEY_A, b"\x11" * 16, m.ip4("10.147.17.255"), host_ip=HOST)
        out = m.reseal_reply(KEY_A, rep, b"\x11" * 16, m.ip4("10.147.17.255"),
                             m.ip4("192.168.1.255"), m.ip4(HOST), m.ip4(SW_PHONE))
        self.assertEqual(open_reply(m, KEY_A, out, b"\x11" * 16, m.ip4("192.168.1.255")), pt)
        self.assertEqual(out[60:64], m.ip4(SW_PHONE))
        self.assertEqual(out[120:124], m.ip4(HOST))

    def test_reply_wrong_chal_raises(self):
        m = self.m
        rep, pt = make_browse_reply(m, KEY_A, b"\x11" * 16, m.ip4("10.147.17.255"))
        with self.assertRaises(Exception):
            m.reseal_reply(KEY_A, rep, b"\x99" * 16, m.ip4("10.147.17.255"),
                           m.ip4("192.168.1.255"), m.ip4(HOST), m.ip4(SW_PHONE))

    def test_reply_rejects_non_reply(self):
        m = self.m
        with self.assertRaises(ValueError):
            m.reseal_reply(KEY_A, b"\x00" * 200, b"\x11" * 16, m.ip4("10.147.17.255"),
                           m.ip4("192.168.1.255"), m.ip4(HOST), m.ip4(SW_PHONE))


class TestRelayGameFn(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])
        cls.key = bytes(range(32, 48))

    def test_relay_rewrites_table_addresses(self):
        m = self.m
        plain = b"\x07\x00\x00\x00" + m.ip4(SWITCH) + b"\x00" * 24 + m.ip4(SW_PHONE) + b"\x00" * 8
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), plain)
        table = m.address_pairs([(m.ip4(SWITCH), m.ip4(ZT_PHONE)), (m.ip4(SW_PHONE), m.ip4(HOST))])
        out, label, changed = m.relay_game(self.key, m.ip4(SWITCH), m.ip4(ZT_PHONE), pkt, table)
        self.assertTrue(changed)
        opened = m.open_game(self.key, m.ip4(ZT_PHONE), out)
        self.assertNotIn(m.ip4(SWITCH), opened[0])
        self.assertIn(m.ip4(ZT_PHONE), opened[0])
        self.assertIn(m.ip4(HOST), opened[0])

    def test_relay_bit_rewrite_inside_body(self):
        m = self.m
        x = int.from_bytes(b"\x07\x00\x00\x00" + b"\x00" * 60, "little")
        x |= int.from_bytes(m.ip4(SWITCH), "little") << 150
        x |= int.from_bytes(m.ip4(SWITCH)[2:4], "little") << 134
        plain = x.to_bytes(64, "little")
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), plain)
        out, label, changed = m.relay_game(self.key, m.ip4(SWITCH), m.ip4(ZT_PHONE), pkt, [],
                                           ip_pairs=[(m.ip4(SWITCH), m.ip4(HOST))])
        self.assertTrue(changed)
        opened = m.open_game(self.key, m.ip4(ZT_PHONE), out)[0]
        ox = int.from_bytes(opened, "little")
        self.assertEqual((ox >> 150) & 0xFFFFFFFF, int.from_bytes(m.ip4(HOST), "little"))

    def test_relay_compressed_round_trip(self):
        m = self.m
        plain = b"\x07\x00\x00\x00" + m.ip4(SWITCH) + b"room" * 20
        comp = m._CCTX.compress(plain)
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), comp)
        table = m.address_pairs([(m.ip4(SWITCH), m.ip4(ZT_PHONE))])
        out, label, changed = m.relay_game(self.key, m.ip4(SWITCH), m.ip4(ZT_PHONE), pkt, table)
        opened = m.open_game(self.key, m.ip4(ZT_PHONE), out)
        import zstandard as zstd
        self.assertEqual(zstd.ZstdDecompressor().decompress(opened[0], max_output_size=1 << 20),
                         plain.replace(m.ip4(SWITCH), m.ip4(ZT_PHONE)))

    def test_relay_garbage_returns_none(self):
        m = self.m
        self.assertIsNone(m.relay_game(self.key, m.ip4(SWITCH), m.ip4(ZT_PHONE), b"\x00" * 60, []))

    def test_no_bits_flag_skips_bit_rewrite(self):
        m = self.m
        x = int.from_bytes(b"\x07\x00\x00\x00" + b"\x00" * 60, "little")
        x |= int.from_bytes(m.ip4(SWITCH), "little") << 150
        plain = x.to_bytes(64, "little")
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), plain)
        m.NO_BITS = True
        try:
            out, label, changed = m.relay_game(self.key, m.ip4(SWITCH), m.ip4(ZT_PHONE), pkt, [],
                                               ip_pairs=[(m.ip4(SWITCH), m.ip4(HOST))])
        finally:
            m.NO_BITS = False
        self.assertFalse(changed)
        opened = m.open_game(self.key, m.ip4(ZT_PHONE), out)[0]
        ox = int.from_bytes(opened, "little")
        self.assertEqual((ox >> 150) & 0xFFFFFFFF, int.from_bytes(m.ip4(SWITCH), "little"))


def pia_record(proto, payload):
    mask = 2 | 4
    return bytes([mask]) + len(payload).to_bytes(2, "big") + bytes([proto]) + payload


class TestTrimAndSome(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def test_trim_cuts_after_full_join_record(self):
        m = self.m
        plain = pia_record(13, b"\x00" * 198) + pia_record(1, b"extra")
        self.assertEqual(m.trim_join_response(plain), pia_record(13, b"\x00" * 198))

    def test_trim_keeps_when_no_full_record(self):
        m = self.m
        plain = pia_record(13, b"\x00" * 50) + pia_record(1, b"extra")
        self.assertEqual(m.trim_join_response(plain), plain)
        plain2 = pia_record(1, b"\x00" * 198)
        self.assertEqual(m.trim_join_response(plain2), plain2)

    def test_trim_keeps_on_bad_mask(self):
        m = self.m
        plain = b"\xff" + b"\x00" * 40
        self.assertEqual(m.trim_join_response(plain), plain)

    def test_rewrite_some_only_listed_protos(self):
        m = self.m
        plain = pia_record(11, b"AAAA" + m.ip4(SWITCH)) + pia_record(7, b"BBBB" + m.ip4(SWITCH))
        table = [(m.ip4(SWITCH), m.ip4(HOST))]
        out = m.rewrite_some(plain, table, {11})
        self.assertIn(m.ip4(HOST), out)
        tail = pia_record(7, b"BBBB" + m.ip4(SWITCH))
        self.assertTrue(out.endswith(tail))


class TestCapture(unittest.TestCase):
    def test_save_and_close(self):
        m = load_copy(PC_COPIES[0])
        tmp = tempfile.mkdtemp()
        cap = m.Capture(os.path.join(tmp, "cap"))
        data = m.MAGIC + b"\x00" * 40
        cap.save("disc", data, "1.2.3.4", "5.6.7.8", 35000, 35000)
        cap.save("bogus", data, "1.2.3.4", "5.6.7.8", 1, 2)
        cap.save("hs-game", data, "1.2.3.4", "5.6.7.8", 49152, 49152)
        cap.close()
        with open(os.path.join(tmp, "cap.bin"), "rb") as fh:
            self.assertEqual(fh.read(), data + data)
        with open(os.path.join(tmp, "cap.txt")) as fh:
            lines = fh.read().strip().split("\n")
        self.assertEqual(len(lines), 2)
        self.assertIn("5.6.7.8", lines[1].split("\t")[3])


class TestRelayHandlers(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def setUp(self):
        self.r = make_relay(self.m)

    def test_leg_of(self):
        r = self.r
        self.assertEqual(r.leg_of(SWITCH), "sw")
        self.assertEqual(r.leg_of(HOST), "h")
        self.assertIsNone(r.leg_of("203.0.113.9"))
        self.assertIsNone(r.leg_of("not an ip"))

    def test_leg_of_wide_subnet(self):
        r = self.r
        r.sw_net = ipaddress.IPv4Network("192.168.4.0/22")
        self.assertEqual(r.leg_of("192.168.7.200"), "sw")
        self.assertIsNone(r.leg_of("192.168.8.1"))

    def test_dup_window(self):
        r = self.r
        self.assertFalse(r.dup(b"packet", (SWITCH, 35000)))
        self.assertTrue(r.dup(b"packet", (SWITCH, 35000)))
        self.assertFalse(r.dup(b"other", (SWITCH, 35000)))
        self.assertFalse(r.dup(b"packet", (HOST, 35000)))
        for k in list(r.seen):
            r.seen[k] -= 10
        self.assertFalse(r.dup(b"packet", (SWITCH, 35000)))

    def test_build_tables_contents(self):
        r = self.r
        r.switch = (SWITCH, 49152)
        r.host = (HOST, 49152)
        r.build_tables()
        self.assertIn((r.sw_me, self.m.ip4(HOST)), r.ip_pairs["s2h"])
        self.assertIn((self.m.ip4(SWITCH), r.h_me), r.ip_pairs["s2h"])
        self.assertIn((self.m.ip4(HOST), r.sw_me), r.ip_pairs["h2s"])
        self.assertIn((r.h_me, self.m.ip4(SWITCH)), r.ip_pairs["h2s"])
        self.assertIn((r.sw_me, self.m.ip4(HOST)), r.tables["s2h"])
        self.assertTrue(all(o != n for pairs in r.ip_pairs.values() for o, n in pairs))

    def test_on_disc_ignores_own_and_unknown(self):
        r = self.r
        req, chal, pt = make_browse_request(self.m, KEY_A, r.sw_bc)
        r.on_disc(req, (SW_PHONE, 35000))
        r.on_disc(req, ("203.0.113.9", 35000))
        self.assertEqual(r.d_h.sent, [])
        self.assertIsNone(r.switch)

    def browse_from_switch(self):
        req, chal, pt = make_browse_request(self.m, KEY_A, self.r.sw_bc)
        self.r.on_disc(req, (SWITCH, 35000))
        return req, chal, pt

    def test_browse_request_broadcast_resealed_and_sweep(self):
        r = self.r
        req, chal, pt = self.browse_from_switch()
        self.assertEqual(r.switch, (SWITCH, 49152))
        self.assertEqual(r.req_chals, [chal])
        self.assertIsNotNone(r.last_sw_req)
        dests = [d for _, d in r.d_h.sent]
        self.assertIn(("10.147.17.255", 35000), dests)
        probes = [d for d in dests if d[0] not in ("10.147.17.255",)]
        self.assertEqual(len(probes), 254)
        self.assertNotIn((ZT_PHONE, 35000), dests)
        self.assertIn((HOST, 35000), dests)
        first = r.d_h.sent[0][0]
        back, _ = self.m.reseal_request(KEY_A, first, r.h_bc, r.sw_bc)
        self.assertEqual(back, req)

    def test_browse_request_unicast_when_host_known_no_sweep(self):
        r = self.r
        r.host = (HOST, 49152)
        req, chal, pt = self.browse_from_switch()
        dests = [d for _, d in r.d_h.sent]
        self.assertEqual(sorted(dests), sorted([("10.147.17.255", 35000), (HOST, 35000)]))

    def test_sweep_throttled(self):
        import time
        r = self.r
        self.browse_from_switch()
        first_count = len(r.d_h.sent)
        req2, chal2, pt2 = make_browse_request(self.m, KEY_A, r.sw_bc, chal=b"\x77" * 16)
        r.on_disc(req2, ("192.168.1.151", 35000))
        self.assertEqual(len(r.d_h.sent), first_count + 1)
        r.last_sweep = time.time() - 10
        req3, chal3, pt3 = make_browse_request(self.m, KEY_A, r.sw_bc, chal=b"\x78" * 16)
        r.on_disc(req3, ("192.168.1.152", 35000))
        self.assertGreater(len(r.d_h.sent), first_count + 100)

    def test_host_teaching_resends_stored_request(self):
        r = self.r
        self.browse_from_switch()
        before = len(r.d_h.sent)
        teach = bytearray(100)
        teach[0] = 1
        r.on_disc(bytes(teach), (HOST, 35000))
        self.assertEqual(r.host, (HOST, 49152))
        new_sends = r.d_h.sent[before:]
        self.assertTrue(any(d == (HOST, 35000) and data == r.last_sw_req for data, d in new_sends))

    def pair_via_reply(self):
        r = self.r
        req, chal, pt = self.browse_from_switch()
        rep, rpt = make_browse_reply(self.m, KEY_A, chal, r.h_bc, host_ip=HOST)
        before = len(r.d_sw.sent)
        r.on_disc(rep, (HOST, 35000))
        return rep, rpt, before

    def test_browse_reply_pairs_and_forwards(self):
        r = self.r
        rep, rpt, before = self.pair_via_reply()
        self.assertEqual(r.host_side, "h")
        self.assertEqual(r.key, self.m.session_key(KEY_A, rep))
        self.assertIsNotNone(r.tables)
        self.assertEqual(len(r.d_sw.sent), before + 1)
        data, dest = r.d_sw.sent[-1]
        self.assertEqual(dest, (SWITCH, 35000))
        self.assertEqual(open_reply(self.m, KEY_A, data, r.req_chals[0], r.sw_bc), rpt)
        self.assertEqual(data[60:64], r.sw_me)

    def test_reply_with_no_recent_request_dropped(self):
        r = self.r
        rep, rpt = make_browse_reply(self.m, KEY_A, b"\x11" * 16, r.h_bc, host_ip=HOST)
        r.switch = (SWITCH, 49152)
        r.on_disc(rep, (HOST, 35000))
        self.assertEqual(r.d_sw.sent, [])
        self.assertIsNone(r.key)

    def test_host_request_and_switch_reply_reverse_roles(self):
        r = self.r
        r.switch = (SWITCH, 49152)
        req, chal, pt = make_browse_request(self.m, KEY_A, r.h_bc)
        r.on_disc(req, (HOST, 35000))
        self.assertEqual(r.host, (HOST, 49152))
        self.assertEqual(r.host_req_chals, [chal])
        dests = [d for _, d in r.d_sw.sent]
        self.assertIn(("192.168.1.255", 35000), dests)
        self.assertIn((SWITCH, 35000), dests)
        rep, rpt = make_browse_reply(self.m, KEY_A, chal, r.sw_bc, host_ip=SWITCH)
        r.on_disc(rep, (SWITCH, 35000))
        self.assertEqual(r.host_side, "sw")
        self.assertEqual(r.key, self.m.session_key(KEY_A, rep))
        data, dest = r.d_h.sent[-1]
        self.assertEqual(dest, (HOST, 35000))
        self.assertEqual(open_reply(self.m, KEY_A, data, chal, r.h_bc), rpt)

    def test_game_dropped_before_pairing(self):
        r = self.r
        pkt = self.m.seal_game(b"\x00" * 16, self.m.ip4(SWITCH), game_header(self.m), b"\x00" * 32)
        r.on_game(pkt, (SWITCH, 49152), "s2h")
        self.assertTrue(any(e.get("ev") == "drop_nokey" for e in r.log))
        self.assertEqual(r.g_h.sent, [])

    def test_game_flows_both_directions_after_pairing(self):
        r = self.r
        self.pair_via_reply()
        key = r.key
        plain = b"\x07\x00\x00\x00" + self.m.ip4(SWITCH) + b"\x00" * 28
        pkt = self.m.seal_game(key, self.m.ip4(SWITCH), game_header(self.m), plain)
        r.on_game(pkt, (SWITCH, 49152), "s2h")
        self.assertEqual(len(r.g_h.sent), 1)
        out, dest = r.g_h.sent[0]
        self.assertEqual(dest, (HOST, 49152))
        opened = self.m.open_game(key, r.h_me, out)
        self.assertIsNotNone(opened)
        plain2 = b"\x07\x00\x00\x00" + self.m.ip4(HOST) + b"\x00" * 28
        pkt2 = self.m.seal_game(key, self.m.ip4(HOST), game_header(self.m), plain2)
        r.on_game(pkt2, (HOST, 49152), "h2s")
        self.assertEqual(len(r.g_sw.sent), 1)
        out2, dest2 = r.g_sw.sent[0]
        self.assertEqual(dest2, (SWITCH, 49152))
        self.assertIsNotNone(self.m.open_game(key, r.sw_me, out2))

    def test_game_unauthenticated_not_forwarded(self):
        r = self.r
        self.pair_via_reply()
        junk = self.m.MAGIC + b"\x00" * 96
        r.on_game(junk, (SWITCH, 49152), "s2h")
        self.assertEqual(r.g_h.sent, [])
        self.assertTrue(any(e.get("ev") == "rx_unauth" for e in r.log))

    def test_game_sock_routes_by_source_subnet(self):
        r = self.r
        self.pair_via_reply()
        key = r.key
        plain = b"\x07\x00\x00\x00" + b"\x00" * 32
        pkt = self.m.seal_game(key, self.m.ip4(SWITCH), game_header(self.m), plain)
        r.on_game_sock(pkt, (SWITCH, 49152), "h")
        self.assertEqual(len(r.g_h.sent), 1)


class TestCrossCopy(unittest.TestCase):
    def test_pc_copies_identical(self):
        with open(PC_COPIES[0], "rb") as a, open(PC_COPIES[1], "rb") as b:
            self.assertEqual(a.read(), b.read())

    def test_all_copies_expose_core_api(self):
        names = ["load_game_key", "bcast_of", "ip4", "address_pairs", "rewrite", "rewrite_bits",
                 "open_game", "seal_game", "session_key", "reseal_request", "reseal_reply",
                 "relay_game", "trim_join_response", "Capture", "Relay"]
        for path in PC_COPIES + [ANDROID_COPY]:
            with self.subTest(path=path):
                m = load_copy(path)
                for n in names:
                    self.assertTrue(hasattr(m, n), n)
                self.assertEqual(m.DISCOVERY_PORT, 35000)
                self.assertEqual(m.GAME_PORT, 49152)

    def test_android_copy_has_no_network_fetch(self):
        with open(ANDROID_COPY) as fh:
            self.assertNotIn("urllib", fh.read())

    def test_pc_copies_have_fetch(self):
        for path in PC_COPIES:
            with self.subTest(path=path):
                m = load_copy(path)
                self.assertTrue(hasattr(m, "fetch_game_key"))


IP_ADDR_FIXTURE = """1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    inet 127.0.0.1/8 scope host lo
    inet6 ::1/128 scope host noprefixroute
2: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP group default qlen 1000
    inet 192.168.1.50/24 brd 192.168.1.255 scope global wlan0
    inet6 fe80::1ff:fe00:1/64 scope link noprefixroute
3: ztun0: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 2800 qdisc fq_codel state UNKNOWN group default qlen 500
    inet 10.147.17.50/24 brd 10.147.17.255 scope global ztun0
4: veth0@eth0: <BROADCAST,MULTICAST> mtu 1500 qdisc noop state DOWN group default
    inet 172.20.0.2/16 brd 172.20.255.255 scope global veth0
"""


class DeadSocket:
    def __init__(self, *a, **k):
        pass

    def connect(self, *a):
        raise OSError("dead")

    def fileno(self):
        raise OSError("dead")

    def getsockname(self):
        raise OSError("dead")

    def close(self):
        pass


class TestDiscoveryParsing(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def setUp(self):
        self.m._IP_ADDR_CACHE.clear()
        self.m._IP_ADDR_CACHE["out"] = IP_ADDR_FIXTURE

    def tearDown(self):
        self.m._IP_ADDR_CACHE.clear()

    def test_iface_addrs_parsing(self):
        addrs = self.m._iface_addrs()
        self.assertIn(("wlan0", "192.168.1.50"), addrs)
        self.assertIn(("ztun0", "10.147.17.50"), addrs)
        self.assertIn(("veth0", "172.20.0.2"), addrs)
        self.assertTrue(all(":" not in ip for _, ip in addrs))

    def test_detect_finds_zerotier_address(self):
        import socket as sock_mod
        orig = sock_mod.socket
        sock_mod.socket = DeadSocket
        try:
            self.assertEqual(self.m.detect_zerotier_ip(), "10.147.17.50")
        finally:
            sock_mod.socket = orig

    def test_detect_prefers_peer_subnet_over_zerotier_prefix(self):
        import socket as sock_mod
        orig = sock_mod.socket
        sock_mod.socket = DeadSocket
        try:
            self.assertEqual(self.m.detect_zerotier_ip("192.168.1.99"), "192.168.1.50")
        finally:
            sock_mod.socket = orig

    def test_detect_falls_back_to_default(self):
        import socket as sock_mod
        self.m._IP_ADDR_CACHE["out"] = "2: wlan0: <UP> mtu 1500\n    inet 192.168.1.50/24 scope global wlan0\n"
        orig = sock_mod.socket
        sock_mod.socket = DeadSocket
        try:
            self.assertEqual(self.m.detect_zerotier_ip(), "10.147.17.190")
        finally:
            sock_mod.socket = orig

    def test_get_zerotier_iface(self):
        self.assertEqual(self.m.get_zerotier_iface("10.147.17.50"), "ztun0")
        self.assertIsNone(self.m.get_zerotier_iface("10.147.17.200"))

    def test_get_wifi_ip_parses_first_inet(self):
        import subprocess
        fixture = ("2: wlan0: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc noqueue state UP\n"
                   "    inet 192.168.5.23/24 brd 192.168.5.255 scope global wlan0\n"
                   "    inet6 fe80::abcd/64 scope link\n")
        orig = subprocess.check_output
        subprocess.check_output = lambda *a, **k: fixture
        try:
            self.assertEqual(self.m.get_wifi_ip(), "192.168.5.23")
        finally:
            subprocess.check_output = orig


class TestMainEnv(unittest.TestCase):
    def run_main(self, env, argv):
        m = load_copy(PC_COPIES[0])
        captured = {}

        class FakeRelay:
            def __init__(self, a):
                captured["args"] = a

            def run(self, out_path):
                captured["events"] = out_path

        orig_relay = m.Relay
        orig_argv = sys.argv
        old_env = dict(os.environ)
        m.Relay = FakeRelay
        sys.argv = argv
        for k in ("PHONE_IP", "ZT_IP", "SWITCH_PREFIX", "HOST_PREFIX", "SWITCH_IP", "HOST_ZT",
                  "DELAY_S2H_MS", "DELAY_H2S_MS", "CAPTURE", "EVENTS"):
            os.environ.pop(k, None)
        os.environ.update(env)
        try:
            m.main()
        finally:
            captured["no_bits"] = m.NO_BITS
            m.Relay = orig_relay
            sys.argv = orig_argv
            os.environ.clear()
            os.environ.update(old_env)
            m.NO_BITS = False
        return m, captured

    def test_env_plumbing(self):
        m, cap = self.run_main({
            "PHONE_IP": "192.168.1.50", "ZT_IP": "10.147.17.50",
            "SWITCH_PREFIX": "22", "HOST_PREFIX": "16",
            "SWITCH_IP": "192.168.4.30", "HOST_ZT": "10.147.17.99",
            "DELAY_S2H_MS": "25", "DELAY_H2S_MS": "0",
            "EVENTS": "my_events.jsonl",
        }, ["zttestproxyv228.py"])
        a = cap["args"]
        self.assertEqual(a.switch_ip, "192.168.1.50")
        self.assertEqual(a.host_ip, "10.147.17.50")
        self.assertEqual(a.switch_prefix, 22)
        self.assertEqual(a.host_prefix, 16)
        self.assertEqual(a.switch_peer, "192.168.4.30")
        self.assertEqual(a.host_peer, "10.147.17.99")
        self.assertEqual(a.delay_s2h, 25.0)
        self.assertEqual(a.delay_h2s, 0.0)
        self.assertIsNone(a.capture)
        self.assertEqual(cap["events"], "my_events.jsonl")
        self.assertFalse(cap["no_bits"])

    def test_env_defaults_and_no_bit_flag(self):
        m, cap = self.run_main({}, ["zttestproxyv228.py", "--no-bit-rewrite"])
        a = cap["args"]
        self.assertIsNone(a.switch_ip)
        self.assertIsNone(a.host_ip)
        self.assertEqual(a.switch_prefix, 24)
        self.assertEqual(a.host_prefix, 24)
        self.assertTrue(cap["no_bits"])
        self.assertTrue(cap["events"].startswith("relay_events_"))


class TestRelayMore(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def setUp(self):
        self.r = make_relay(self.m)

    def test_delayed_send_queues_then_drains(self):
        import heapq
        r = self.r
        r.delay["s2h"] = 5.0
        r.send(r.d_h, b"data", (HOST, 35000), "s2h", kind="browse_req")
        self.assertEqual(r.d_h.sent, [])
        self.assertEqual(len(r.queue), 1)
        due, _, sock, data, dest, direction, meta = heapq.heappop(r.queue)
        r._tx(sock, data, dest, direction, meta)
        self.assertEqual(r.d_h.sent, [(b"data", (HOST, 35000))])
        self.assertTrue(any(e.get("ev") == "tx" for e in r.log))

    def test_tx_failure_noted_not_raised(self):
        r = self.r

        class BadSocket:
            def sendto(self, data, dest):
                raise OSError(101, "network unreachable")

        r._tx(BadSocket(), b"x", (HOST, 35000), "s2h", {})
        errs = [e for e in r.log if e.get("ev") == "txerr"]
        self.assertEqual(len(errs), 1)
        self.assertEqual(errs[0]["err"], 101)

    def test_only_switch_filter(self):
        r = self.r
        r.only_switch = "192.168.1.200"
        req, chal, pt = make_browse_request(self.m, KEY_A, r.sw_bc)
        r.on_disc(req, (SWITCH, 35000))
        self.assertIsNone(r.switch)
        self.assertEqual(r.d_h.sent, [])
        r.on_disc(req, ("192.168.1.200", 35000))
        self.assertEqual(r.switch[0], "192.168.1.200")

    def test_console_change_rebuilds_tables(self):
        r = self.r
        r.host = (HOST, 49152)
        req, chal, pt = make_browse_request(self.m, KEY_A, r.sw_bc)
        r.on_disc(req, (SWITCH, 35000))
        rep, rpt = make_browse_reply(self.m, KEY_A, chal, r.h_bc, host_ip=HOST)
        r.on_disc(rep, (HOST, 35000))
        self.assertEqual(r.switch[0], SWITCH)
        req2, chal2, pt2 = make_browse_request(self.m, KEY_A, r.sw_bc, chal=b"\x42" * 16)
        r.on_disc(req2, ("192.168.1.151", 35000))
        self.assertEqual(r.switch[0], "192.168.1.151")
        self.assertIn((self.m.ip4("192.168.1.151"), r.h_me), r.ip_pairs["s2h"])

    def test_req_chals_capped_at_eight_and_deduped(self):
        r = self.r
        r.host = (HOST, 49152)
        for i in range(10):
            req, chal, pt = make_browse_request(self.m, KEY_A, r.sw_bc,
                                                chal=bytes([i]) * 16, counter=bytes([i]) * 8)
            r.on_disc(req, (SWITCH, 35000))
        self.assertEqual(len(r.req_chals), 8)
        self.assertEqual(r.req_chals[0], bytes([9]) * 16)
        self.assertNotIn(bytes([0]) * 16, r.req_chals)
        self.assertNotIn(bytes([1]) * 16, r.req_chals)
        req, chal, pt = make_browse_request(self.m, KEY_A, r.sw_bc,
                                            chal=bytes([5]) * 16, counter=b"\x09" * 8)
        r.on_disc(req, (SWITCH, 35000))
        self.assertEqual(len(r.req_chals), 8)
        self.assertEqual(r.req_chals[0], bytes([5]) * 16)
        self.assertEqual(r.req_chals.count(bytes([5]) * 16), 1)

    def test_switch_reply_without_host_ignored(self):
        r = self.r
        r.switch = (SWITCH, 49152)
        req, chal, pt = make_browse_request(self.m, KEY_A, r.h_bc)
        r.host_req_chals = [chal]
        rep, rpt = make_browse_reply(self.m, KEY_A, chal, r.sw_bc, host_ip=SWITCH)
        r.on_disc(rep, (SWITCH, 35000))
        self.assertEqual(r.d_h.sent, [])
        self.assertIsNone(r.key)

    def test_host_request_without_switch_broadcast_only(self):
        r = self.r
        req, chal, pt = make_browse_request(self.m, KEY_A, r.h_bc)
        r.on_disc(req, (HOST, 35000))
        self.assertEqual([d for _, d in r.d_sw.sent], [("192.168.1.255", 35000)])
        self.assertEqual(r.host_req_chals, [chal])


class TestMoreFunctions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])
        cls.key = bytes(range(48, 64))

    def test_rewrite_applies_pairs_in_order(self):
        self.assertEqual(self.m.rewrite(b"AAAA", [(b"AA", b"BB"), (b"BB", b"CC")]), b"CCCC")
        self.assertEqual(self.m.rewrite(b"AAAA", [(b"ZZ", b"BB")]), b"AAAA")

    def test_empty_body_round_trip(self):
        m = self.m
        pkt = m.seal_game(self.key, m.ip4(SWITCH), game_header(m), b"")
        self.assertEqual(m.open_game(self.key, m.ip4(SWITCH), pkt), (b"", False))

    def test_session_key_param_wrap(self):
        m = self.m
        rep = bytearray(200)
        rep[105:137] = bytes([7] * 31 + [255])
        param = bytearray(rep[105:137])
        param[-1] = 0
        expect = hmac_mod.new(KEY_A, bytes(param), hashlib.sha256).digest()[:16]
        self.assertEqual(m.session_key(KEY_A, bytes(rep)), expect)

    def test_reseal_request_wrong_broadcast_raises(self):
        m = self.m
        req, chal, pt = make_browse_request(m, KEY_A, m.ip4("192.168.1.255"))
        with self.assertRaises(Exception):
            m.reseal_request(KEY_A, req, m.ip4("10.147.17.255"), m.ip4("192.168.1.255"))

    def test_trim_cut_after_last_full_record(self):
        m = self.m
        plain = (pia_record(13, b"\x00" * 50) + pia_record(13, b"\x00" * 198)
                 + pia_record(1, b"extra"))
        self.assertEqual(m.trim_join_response(plain),
                         pia_record(13, b"\x00" * 50) + pia_record(13, b"\x00" * 198))

    def test_trim_truncated_record_unchanged(self):
        m = self.m
        plain = bytes([2 | 4]) + (500).to_bytes(2, "big") + bytes([13]) + b"\x00" * 10
        self.assertEqual(m.trim_join_response(plain), plain)
        self.assertEqual(m.trim_join_response(b""), b"")
        self.assertEqual(m.trim_join_response(bytes([0])), bytes([0]))

    def test_rewrite_some_all_field_widths(self):
        m = self.m
        payload = b"DATA" + m.ip4(SWITCH)
        mask = 1 | 2 | 4 | 8 | 16
        header = bytes([mask]) + b"\x07" + len(payload).to_bytes(2, "big") + bytes([11]) + b"\x01" + b"\x02"
        plain = header + payload
        out = m.rewrite_some(plain, [(m.ip4(SWITCH), m.ip4(HOST))], {11})
        self.assertEqual(out[:len(header)], header)
        self.assertEqual(out[len(header):], b"DATA" + m.ip4(HOST))
        out2 = m.rewrite_some(plain, [(m.ip4(SWITCH), m.ip4(HOST))], {7})
        self.assertEqual(out2, plain)

    def test_address_pairs_two_inputs(self):
        m = self.m
        pairs = m.address_pairs([(m.ip4(SWITCH), m.ip4(HOST)), (m.ip4(SW_PHONE), m.ip4(ZT_PHONE))])
        self.assertIn((m.ip4(SWITCH), m.ip4(HOST)), pairs)
        self.assertIn((m.ip4(SW_PHONE), m.ip4(ZT_PHONE)), pairs)

    def test_bcast_prefix_edges(self):
        m = self.m
        self.assertEqual(m.bcast_of("192.168.1.50", 32), m.ip4("192.168.1.50"))
        self.assertEqual(m.bcast_of("192.168.1.50", 0), m.ip4("255.255.255.255"))

    def test_env_key_missing_file_falls_through_pc(self):
        m = load_copy(PC_COPIES[0], beside_key=KEY_A)
        old_env = dict(os.environ)
        os.environ["GAME_KEY_FILE"] = os.path.join(m._test_tmp, "does_not_exist.txt")
        os.environ["HOME"] = m._test_home
        try:
            self.assertEqual(m.load_game_key(), KEY_A)
        finally:
            os.environ.clear()
            os.environ.update(old_env)

    def test_fetch_uppercase_hex(self):
        import urllib.request
        orig = urllib.request.urlopen

        class Resp:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return b"| Splatoon 3 | `78DEEE82D86875782C40B15278F37815` |\n"

        urllib.request.urlopen = lambda *a, **k: Resp()
        try:
            self.assertEqual(self.m.fetch_game_key(), WIKI_KEY)
        finally:
            urllib.request.urlopen = orig

    def test_capture_counters(self):
        m = self.m
        tmp = tempfile.mkdtemp()
        cap = m.Capture(os.path.join(tmp, "cap2"))
        data = m.MAGIC + b"\x00" * 40
        cap.save("sw-game", data, "1.2.3.4", "5.6.7.8", 49152, 49152)
        cap.save("disc", data, "1.2.3.4", "5.6.7.8", 35000, 35000)
        self.assertEqual(cap.n, 2)
        self.assertEqual(cap.off, 2 * len(data))
        cap.close()
        with open(os.path.join(tmp, "cap2.txt")) as fh:
            lines = fh.read().strip().split("\n")
        self.assertEqual(lines[0].split("\t")[3], "1.2.3.4")
        self.assertEqual(lines[1].split("\t")[-1], "1")


class TestUpdateSources(unittest.TestCase):
    def read(self, rel):
        with open(os.path.join(ROOT, rel)) as fh:
            return fh.read()

    def test_pc_checks_releases_latest(self):
        src = self.read("desktop/window.cpp")
        self.assertIn("https://api.github.com/repos/GRID0-net/GRID0-ofw/releases/latest", src)
        self.assertNotIn("desktop/version.json", src)

    def test_android_checks_releases_latest(self):
        src = self.read("android/app/src/main/java/com/grid0/ofw/MainActivity.java")
        self.assertIn("https://api.github.com/repos/GRID0-net/GRID0-ofw/releases/latest", src)
        self.assertNotIn("version.json", src)


FIXTURES = os.path.join(ROOT, "tests", "fixtures")


def read_fixture(name):
    with open(os.path.join(FIXTURES, name), "rb") as fh:
        return fh.read()


def make_mk8d_browse_request(m, bc, chal=b"\x21" * 16, counter=b"\x22" * 8, pt=None):
    from Crypto.Cipher import AES
    if pt is None:
        pt = bytes(range(256))
    k = AES.new(m.MK8D_KEY, AES.MODE_ECB).encrypt(chal)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter).encrypt_and_digest(pt)
    req = bytearray(873)
    req[0] = 0
    req[1:5] = (0x23A).to_bytes(4, "big")
    req[575] = 1
    req[576] = 1
    req[577:585] = counter
    req[585:601] = chal
    req[601:617] = tag
    req[617:873] = ct
    return bytes(req), chal


def make_mk8d_browse_reply(m, req_chal, bc, host_ip=None, param=None,
                           rchal=b"\x55" * 16, counter=b"\x44" * 8, pt=b"\x33" * 16):
    from Crypto.Cipher import AES
    if param is None:
        param = bytes(range(32, 64))
    rep = bytearray(1361)
    rep[0] = 1
    rep[1:5] = (1298).to_bytes(4, "big")
    if host_ip is not None:
        rep[436:440] = m.ip4(host_ip)
    rep[1271:1303] = param
    k = hmac_mod.new(m.MK8D_KEY, rchal + req_chal, hashlib.sha256).digest()[:16]
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter).encrypt_and_digest(pt)
    rep[1305:1313] = counter
    rep[1313:1329] = rchal
    rep[1329:1345] = tag
    rep[1345:1361] = ct
    return bytes(rep), pt


class TestMK8D(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def test_detect_game_shapes(self):
        m = self.m
        req, chal, pt = make_browse_request(m, KEY_A, m.ip4("192.168.1.255"))
        self.assertEqual(m.detect_game(req), "splatoon3")
        mreq, mchal = make_mk8d_browse_request(m, m.ip4("192.168.1.255"))
        self.assertEqual(m.detect_game(mreq), "mk8d")
        self.assertIsNone(m.detect_game(b"\x00" * 100))
        self.assertIsNone(m.detect_game(b"\x01" + b"\x00" * 872))

    def test_real_capture_request_reseals(self):
        m = self.m
        req = read_fixture("mk8d_browse_request_wifi.bin")
        self.assertEqual(m.detect_game(req), "mk8d")
        out, chal = m.reseal_request(m.MK8D_KEY, req, m.ip4("192.168.2.255"),
                                     m.ip4("10.147.17.255"), m.CHAL_OFF["mk8d"])
        self.assertNotEqual(out, req)
        back, _ = m.reseal_request(m.MK8D_KEY, out, m.ip4("10.147.17.255"),
                                   m.ip4("192.168.2.255"), m.CHAL_OFF["mk8d"])
        self.assertEqual(back, req)

    def test_real_capture_wrong_key_and_wrong_broadcast_fail(self):
        m = self.m
        req = read_fixture("mk8d_browse_request_wifi.bin")
        with self.assertRaises(Exception):
            m.reseal_request(WIKI_KEY, req, m.ip4("192.168.2.255"),
                             m.ip4("10.147.17.255"), m.CHAL_OFF["mk8d"])
        with self.assertRaises(Exception):
            m.reseal_request(m.MK8D_KEY, req, m.ip4("10.147.17.255"),
                             m.ip4("192.168.2.255"), m.CHAL_OFF["mk8d"])

    def test_zt_capture_still_bound_to_wifi_broadcast(self):
        m = self.m
        req = read_fixture("mk8d_browse_request_zt.bin")
        self.assertEqual(m.detect_game(req), "mk8d")
        out, chal = m.reseal_request(m.MK8D_KEY, req, m.ip4("192.168.2.255"),
                                     m.ip4("192.168.1.255"), m.CHAL_OFF["mk8d"])
        back, _ = m.reseal_request(m.MK8D_KEY, out, m.ip4("192.168.1.255"),
                                   m.ip4("192.168.2.255"), m.CHAL_OFF["mk8d"])
        self.assertEqual(back, req)

    def test_session_key_uses_mk8d_param_offset(self):
        m = self.m
        param = bytes(range(32, 64))
        rep, pt = make_mk8d_browse_reply(m, b"\x21" * 16, m.ip4("10.147.17.255"), param=param)
        self.assertEqual(len(rep), 1361)
        self.assertEqual(m.mk8d_param_off(rep), 1271)
        inc = bytearray(param)
        inc[-1] = (inc[-1] + 1) & 0xFF
        expect = hmac_mod.new(m.MK8D_KEY, bytes(inc), hashlib.sha256).digest()[:16]
        self.assertEqual(m.session_key(m.MK8D_KEY, rep, m.mk8d_param_off(rep)), expect)
        fake = bytearray(200)
        fake[1:5] = (1297).to_bytes(4, "big")
        self.assertEqual(m.mk8d_param_off(bytes(fake)), 1270)

    def test_reply_reseal_round_trip_and_zones(self):
        m = self.m
        rep, pt = make_mk8d_browse_reply(m, b"\x21" * 16, m.ip4("10.147.17.255"), host_ip=HOST)
        out = m.reseal_reply(m.MK8D_KEY, rep, b"\x21" * 16, m.ip4("10.147.17.255"),
                             m.ip4("192.168.1.255"), m.ip4(HOST), m.ip4(SW_PHONE),
                             m.mk8d_param_off(rep))
        self.assertEqual(open_reply(m, m.MK8D_KEY, out, b"\x21" * 16, m.ip4("192.168.1.255")), pt)
        self.assertEqual(out[436:440], m.ip4(SW_PHONE))
        self.assertEqual(out[1271:1303], rep[1271:1303])

    def test_game_packet_round_trip(self):
        m = self.m
        key = bytes(range(48, 64))
        hdr = bytearray(36)
        hdr[0:4] = m.MAGIC
        hdr[4] = 2
        hdr[12:20] = b"\x66" * 8
        body = b"\x07\x00\x00\x00" + b"\x00" * 28
        pkt = m.seal_game_mk8d(key, m.ip4(SWITCH), bytes(hdr), body)
        self.assertEqual(m.open_game_mk8d(key, m.ip4(SWITCH), pkt), body)
        self.assertIsNone(m.open_game_mk8d(key, m.ip4(HOST), pkt))
        self.assertIsNone(m.open_game_mk8d(KEY_B, m.ip4(SWITCH), pkt))

    def test_all_copies_expose_mk8d_api(self):
        for path in PC_COPIES + [ANDROID_COPY]:
            with self.subTest(path=path):
                m = load_copy(path)
                for n in ("detect_game", "open_game_mk8d", "seal_game_mk8d", "relay_game_mk8d", "mk8d_param_off"):
                    self.assertTrue(hasattr(m, n), n)
                self.assertEqual(m.MK8D_KEY, b"ABCDEFGHIJKLMNOP")
                self.assertEqual(m.MK8D_PORT, 30000)


class TestMK8DRelay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_copy(PC_COPIES[0])

    def setUp(self):
        self.r = make_relay(self.m)

    def pair_mk8d(self):
        m, r = self.m, self.r
        req, chal = make_mk8d_browse_request(m, r.sw_bc)
        r.on_disc(req, (SWITCH, 30000))
        rep, pt = make_mk8d_browse_reply(m, chal, r.h_bc, host_ip=HOST)
        r.on_disc(rep, (HOST, 30000))
        return req, chal, rep, pt

    def test_request_forwarded_on_mk8d_port(self):
        m, r = self.m, self.r
        req, chal = make_mk8d_browse_request(m, r.sw_bc)
        r.on_disc(req, (SWITCH, 30000))
        self.assertEqual(r.game, "mk8d")
        self.assertEqual(r.mk8d_req_chals, [chal])
        self.assertEqual(r.req_chals, [])
        self.assertEqual(r.d_h.sent, [])
        dests = [d for _, d in r.d_h2.sent]
        self.assertIn(("10.147.17.255", 30000), dests)
        first = r.d_h2.sent[0][0]
        back, _ = m.reseal_request(m.MK8D_KEY, first, r.h_bc, r.sw_bc, m.CHAL_OFF["mk8d"])
        self.assertEqual(back, req)

    def test_reply_sets_mk8d_session_key_only(self):
        m, r = self.m, self.r
        req, chal, rep, pt = self.pair_mk8d()
        self.assertEqual(r.key_mk8d, m.session_key(m.MK8D_KEY, rep, m.mk8d_param_off(rep)))
        self.assertIsNone(r.key)
        self.assertEqual(r.host_side, "h")
        data, dest = r.d_sw2.sent[-1]
        self.assertEqual(dest, (SWITCH, 30000))
        self.assertEqual(open_reply(m, m.MK8D_KEY, data, chal, r.sw_bc), pt)

    def test_game_flows_after_mk8d_pairing(self):
        m, r = self.m, self.r
        self.pair_mk8d()
        key = r.key_mk8d
        hdr = bytearray(36)
        hdr[0:4] = m.MAGIC
        hdr[4] = 2
        hdr[12:20] = b"\x77" * 8
        body = m.ip4(SWITCH) + b"\x00" * 28
        pkt = m.seal_game_mk8d(key, m.ip4(SWITCH), bytes(hdr), body)
        r.on_game(pkt, (SWITCH, 49153), "s2h")
        self.assertEqual(len(r.g_h.sent), 1)
        out, dest = r.g_h.sent[0]
        self.assertEqual(dest, ("10.147.17.255", 49152))
        opened = m.open_game_mk8d(key, r.h_me, out)
        self.assertIsNotNone(opened)
        self.assertEqual(opened[:4], r.h_me)
        r.on_game(pkt, (SWITCH, 49153), "s2h")
        self.assertEqual(len(r.g_h.sent), 1)
        plain = m.MAGIC + b"\x01" + b"\x00" * 40
        r.on_game(plain, (SWITCH, 49153), "s2h")
        self.assertEqual(r.g_h.sent[-1][0], plain)
        self.assertEqual(r.g_h.sent[-1][1], ("10.147.17.255", 49152))


if __name__ == "__main__":
    unittest.main(verbosity=2)
