#!/usr/bin/env python3
# GRID0 proxy v31
# Relays Switch LAN play over ZeroTier through this device.
import errno, hashlib, heapq, hmac, ipaddress, json, os, select, signal, socket, subprocess, sys, time

def _zstd_available():
    try:
        import zstandard
        return True
    except ImportError:
        pass
    try:
        from compression import zstd
        return True
    except ImportError:
        return False

def _ensure_deps():
    missing = []
    try:
        import Crypto
    except ImportError:
        missing.append("pycryptodome")
    if not _zstd_available():
        missing.append("zstandard")
    if not missing:
        return
    print("installing the packages the proxy needs: " + ", ".join(missing), flush=True)
    if subprocess.run([sys.executable, "-m", "pip", "install"] + missing).returncode != 0:
        subprocess.run([sys.executable, "-m", "pip", "install", "--user"] + missing)

_ensure_deps()
from Crypto.Cipher import AES
try:
    import zstandard as _zstd_pkg
except ImportError:
    _zstd_pkg = None
if _zstd_pkg is not None:
    _CCTX = _zstd_pkg.ZstdCompressor(level=3, write_content_size=True, write_checksum=False)
    def _zstd_compress(data):
        return _CCTX.compress(data)
    def _zstd_decompress(raw):
        return _zstd_pkg.ZstdDecompressor().decompress(raw, max_output_size=1 << 20)
else:
    from compression import zstd as _zstd_std
    def _zstd_compress(data):
        return _zstd_std.compress(data, level=3)
    def _zstd_decompress(raw):
        dctx = _zstd_std.ZstdDecompressor()
        out = dctx.decompress(raw, max_length=(1 << 20) + 1)
        while not dctx.eof and len(out) <= (1 << 20):
            chunk = dctx.decompress(b"", max_length=(1 << 20) + 1 - len(out))
            if not chunk:
                break
            out += chunk
        if not dctx.eof:
            raise ValueError("zstd payload truncated or over the size cap")
        return out

DISCOVERY_PORT, GAME_PORT = 35000, 49152
GAME_PORTS = (49152, 49153, 49154, 49155)
# One local game port per remote player in the room, so the Switch sees every
# player as its own station (phone IP + port) instead of all of them at once
# on the phone's single address.
SLOT_PORTS = tuple(49152 + i for i in range(8))
MK8D_PORT = 30000
DISCOVERY_PORTS = (DISCOVERY_PORT, MK8D_PORT)
GAME_NAMES = {"splatoon3": "Splatoon 3", "mk8d": "Mario Kart 8 Deluxe", "splatoon2": "Splatoon 2", "mkw": "Mario Kart World"}
CHAL_OFF = {"splatoon3": 27, "mk8d": 575, "splatoon2": 575, "mkw": 37}
PARAM_OFF = {"splatoon3": 105, "mk8d": 1270, "mkw": 105}
MAGIC = bytes.fromhex("32ab9864")

def detect_game(data):
    if len(data) == 325 and data[0] == 0:
        return "splatoon3"
    if len(data) == 873 and data[0] == 0 and int.from_bytes(data[1:5], "big") == 0x23A:
        return "mk8d"
    if len(data) == 352 and data[0] == 0 and int.from_bytes(data[1:5], "big") == 0x20 and data[37] == 5:
        return "mkw"
    return None
ZSTD_MAGIC = bytes.fromhex("28b52ffd")

def fetch_game_key(game="Splatoon 3"):
    import re, urllib.request
    try:
        with urllib.request.urlopen("https://raw.githubusercontent.com/kinnay/NintendoClientsWiki/master/Pia-Game-Keys.md", timeout=10) as resp:
            text = resp.read().decode("utf-8", "replace")
    except Exception:
        return None
    for line in text.splitlines():
        if game in line:
            m = re.search(r"`([^`]+)`", line)
            if not m:
                continue
            tok = m.group(1).strip()
            if len(tok) == 32:
                try:
                    key = bytes.fromhex(tok)
                except ValueError:
                    continue
                print(f"fetched the {game} game key from NintendoClientsWiki", flush=True)
                return key
            if len(tok) == 16:
                print(f"fetched the {game} game key from NintendoClientsWiki", flush=True)
                return tok.encode()
    return None

def load_game_key():
    try:
        import proxy_crypto_prototype as _m
        return bytes(getattr(_m, "GAME_KEY", None) or _m.GAME_KEY_SPLATOON3)
    except Exception:
        pass
    paths = [os.path.join(os.path.dirname(os.path.abspath(__file__)), "game_key.txt"),
             os.path.expanduser("~/game_key.txt")]
    env_path = os.environ.get("GAME_KEY_FILE")
    if env_path:
        paths.insert(0, env_path)
    for p in paths:
        try:
            with open(p) as fh:
                return bytes.fromhex(fh.read().strip())
        except Exception:
            continue
    key = fetch_game_key()
    if key:
        for p in paths:
            try:
                with open(p, "w") as fh:
                    fh.write(key.hex())
                break
            except Exception:
                continue
        return key
    raise SystemExit("game key not found: no game_key.txt and the fetch from NintendoClientsWiki failed")

GAME_KEY = load_game_key()

def load_game_key_mk8d():
    paths = [os.path.join(os.path.dirname(os.path.abspath(__file__)), "game_key_mk8d.txt"),
             os.path.expanduser("~/game_key_mk8d.txt")]
    env_path = os.environ.get("GAME_KEY_MK8D_FILE")
    if env_path:
        paths.insert(0, env_path)
    for p in paths:
        try:
            with open(p) as fh:
                raw = fh.read().strip()
            if len(raw) == 32:
                return bytes.fromhex(raw)
            if len(raw) == 16:
                return raw.encode()
        except Exception:
            continue
    key = fetch_game_key("Mario Kart 8")
    if key:
        for p in paths:
            try:
                with open(p, "w") as fh:
                    fh.write(key.hex())
                break
            except Exception:
                continue
        return key
    return None

GAME_KEY_MK8D = load_game_key_mk8d()

def load_game_key_splatoon2():
    paths = [os.path.join(os.path.dirname(os.path.abspath(__file__)), "game_key_splatoon2.txt"),
             os.path.expanduser("~/game_key_splatoon2.txt")]
    env_path = os.environ.get("GAME_KEY_SPLATOON2_FILE")
    if env_path:
        paths.insert(0, env_path)
    for p in paths:
        try:
            with open(p) as fh:
                raw = fh.read().strip()
            if len(raw) == 32:
                return bytes.fromhex(raw)
            if len(raw) == 16:
                return raw.encode()
        except Exception:
            continue
    key = fetch_game_key("Splatoon 2")
    if key:
        for p in paths:
            try:
                with open(p, "w") as fh:
                    fh.write(key.hex())
                break
            except Exception:
                continue
        return key
    return None

GAME_KEY_SPLATOON2 = load_game_key_splatoon2()

def load_game_key_mkw():
    paths = [os.path.join(os.path.dirname(os.path.abspath(__file__)), "game_key_mkw.txt"),
             os.path.expanduser("~/game_key_mkw.txt")]
    env_path = os.environ.get("GAME_KEY_MKW_FILE")
    if env_path:
        paths.insert(0, env_path)
    for p in paths:
        try:
            with open(p) as fh:
                raw = fh.read().strip()
            if len(raw) == 32:
                return bytes.fromhex(raw)
            if len(raw) == 16:
                return raw.encode()
        except Exception:
            continue
    key = fetch_game_key("Mario Kart World")
    if key:
        for p in paths:
            try:
                with open(p, "w") as fh:
                    fh.write(key.hex())
                break
            except Exception:
                continue
        return key
    return bytes.fromhex("70ec0c2688380ca5b73fe46a7c7597cf")

GAME_KEY_MKW = load_game_key_mkw()

def get_wifi_ip():
    import subprocess
    try:
        out = subprocess.check_output(["ip", "addr", "show", "wlan0"], text=True, timeout=2,
                                      stderr=subprocess.DEVNULL)
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("inet "):
                return line.split()[1].split("/")[0]
    except Exception:
        pass
    try:
        import fcntl, struct
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        ip = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", b"wlan0"[:15]))[20:24])
        s.close()
        return ip
    except Exception:
        pass
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "192.168.2.100"
    finally:
        s.close()
    return ip

_IP_ADDR_CACHE = {}

def _ip_addr_show():
    if "out" in _IP_ADDR_CACHE:
        return _IP_ADDR_CACHE["out"]
    try:
        import subprocess
        out = subprocess.check_output(["ip", "addr", "show"], text=True, timeout=2,
                                      stderr=subprocess.DEVNULL)
    except Exception:
        out = ""
    _IP_ADDR_CACHE["out"] = out
    return out

def _iface_addrs():
    """[(iface_name, ipv4), ...] parsed from one `ip addr show` snapshot."""
    res, cur = [], None
    for line in _ip_addr_show().splitlines():
        stripped = line.strip()
        if ": " in stripped and "<" in stripped:
            parts = stripped.split(":")
            if len(parts) >= 2:
                cur = parts[1].strip().split()[0].split("@")[0]
        elif stripped.startswith("inet ") and cur:
            res.append((cur, stripped.split()[1].split("/")[0]))
    return res

def detect_zerotier_ip(host_peer=None):
    cands = []
    if host_peer:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((host_peer, GAME_PORT))
            cands.append(s.getsockname()[0])
        except Exception:
            pass
        finally:
            s.close()
    for dev in (b"tun0", b"tun1"):
        try:
            import fcntl, struct
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            cands.append(socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", dev[:15]))[20:24]))
            s.close()
        except Exception:
            pass
    for name, ip in _iface_addrs():
        cands.append(ip)
    if host_peer:
        net = host_peer.rsplit(".", 1)[0] + "."
        for c in cands:
            if c.startswith(net):
                return c
    for c in cands:
        if c.startswith("10.147.17."):
            return c
    if cands:
        print("warning: no ZeroTier address found, check ZeroTier is connected", flush=True)
    return "10.147.17.190"

def get_zerotier_iface(zt_ip):
    for name, ip in _iface_addrs():
        if ip == zt_ip:
            return name
    return None

def ip4(s):
    return ipaddress.IPv4Address(s).packed

def bcast_of(ip, prefix=24):
    return ipaddress.IPv4Network(f"{ip}/{prefix}", strict=False).broadcast_address.packed

def address_pairs(pairs, port=GAME_PORT):
    a = [(o, n) for o, n in pairs]
    a += [(o[3:] + o[2:3] + port.to_bytes(2, "little"), n[3:] + n[2:3] + port.to_bytes(2, "little")) for o, n in pairs]
    a += [(o[::-1], n[::-1]) for o, n in list(a)]
    return [(o, n) for o, n in a if o != n]

def station_forms(ip, port):
    # The ways a Pia station address shows up in Splatoon 3 game data:
    # little-endian IP + tail + port, big-endian IP + port, the tail+port pair
    # in both byte orders, and the member-list entry (IP padded to 16 bytes, then
    # the port).
    tail = ip[3:] + ip[2:3]
    p_le, p_be = port.to_bytes(2, "little"), port.to_bytes(2, "big")
    return [ip[::-1] + tail + p_le, ip + p_be, tail + p_le, p_be + ip[2:4], ip + bytes(12) + p_be]

def slot_tables(slots, host_ip, sw, sw_me, h_me):
    # slots: remote station IP (packed) -> local port on the Switch leg.
    h2s, s2h = [], []
    for r, port in slots.items():
        h2s += list(zip(station_forms(r, GAME_PORT), station_forms(sw_me, port)))
        s2h += list(zip(station_forms(sw_me, port), station_forms(r, GAME_PORT)))
    for r in slots:
        h2s += [(r, sw_me), (r[::-1], sw_me[::-1])]
    if host_ip:
        s2h += [(sw_me, host_ip), (sw_me[::-1], host_ip[::-1])]
    h2s += address_pairs([(h_me, sw)])
    s2h += address_pairs([(sw, h_me)])
    tables = {"s2h": [(o, n) for o, n in s2h if o != n], "h2s": [(o, n) for o, n in h2s if o != n]}
    s2h_ip = [(sw_me, r, port, GAME_PORT) for r, port in slots.items()]
    s2h_ip += ([(sw_me, host_ip)] if host_ip else []) + [(sw, h_me)]
    h2s_ip = [(r, sw_me, GAME_PORT, port) for r, port in slots.items()]
    h2s_ip += [(r, sw_me) for r in slots] + [(h_me, sw)]
    keep = lambda pr: pr[0] != pr[1] or (len(pr) > 2 and pr[2] != pr[3])
    pairs = {"s2h": [pr for pr in s2h_ip if keep(pr)], "h2s": [pr for pr in h2s_ip if keep(pr)]}
    return tables, pairs

def find_stations(plain, prefix3):
    # Remote station IPs named in the plaintext, in either address layout.
    found = set()
    rev = prefix3[::-1]
    i = plain.find(rev, 1)
    while i >= 1:
        last = plain[i - 1]
        if plain[i + 3:i + 5] == bytes([last, prefix3[2]]):
            found.add(prefix3 + bytes([last]))
        i = plain.find(rev, i + 1)
    i = plain.find(prefix3)
    while i >= 0:
        if i + 4 <= len(plain):
            found.add(plain[i:i + 4])
        i = plain.find(prefix3, i + 1)
    return found

def rewrite(body, table):
    for o, n in table:
        body = body.replace(o, n)
    return body

def id_fragments(pairs):
    out = []
    for o, n in pairs:
        for p in GAME_PORTS:
            out.append((o[2:4] + p.to_bytes(2, "big"), n[2:4] + p.to_bytes(2, "big")))
    return [(o, n) for o, n in out if o != n]

def open_game(key, src_ip, d):
    if len(d) < 29 or d[:4] != MAGIC:
        return None
    try:
        p = AES.new(key, AES.MODE_GCM, nonce=src_ip + d[13:21], mac_len=8).decrypt_and_verify(d[29:], d[21:29])
    except ValueError:
        return None
    pad = d[5] >> 4
    if pad:
        p = p[:-pad]
    return p, p[:4] == ZSTD_MAGIC

def seal_game(key, my_ip, hdr, body):
    pad = (-len(body)) % 16
    h = bytearray(hdr[:29])
    h[5] = (pad << 4) | (hdr[5] & 0x0F)
    c = AES.new(key, AES.MODE_GCM, nonce=my_ip + bytes(h[13:21]), mac_len=8)
    ct, tag = c.encrypt_and_digest(body + b"\xff" * pad)
    h[21:29] = tag
    return bytes(h) + ct

def open_game_mk8d(key, src_ip, d):
    if len(d) < 36 or d[:4] != MAGIC or d[4] != 2:
        return None
    try:
        return AES.new(key, AES.MODE_GCM, nonce=src_ip + d[5:6] + d[13:20]).decrypt_and_verify(d[36:], d[20:36])
    except ValueError:
        return None

def seal_game_mk8d(key, my_ip, hdr, body):
    h = bytearray(hdr[:36])
    c = AES.new(key, AES.MODE_GCM, nonce=my_ip + bytes(h[5:6]) + bytes(h[13:20]))
    ct, tag = c.encrypt_and_digest(body)
    h[20:36] = tag
    return bytes(h) + ct

def station_forms_mk8d(ip, port):
    tail = ip[3:] + ip[2:3]
    p_le, p_be = port.to_bytes(2, "little"), port.to_bytes(2, "big")
    return [ip + p_be, tail + p_le, p_be + tail, ip[2:4] + p_be]

def slot_tables_mk8d(slots, ports, host_ip, sw, sw_me, h_me):
    # slots: remote station IP (packed) -> local port on the Switch leg.
    # ports: remote station IP (packed) -> the real ports it uses.
    h2s, s2h = [], []
    for r, slot in slots.items():
        mine = station_forms_mk8d(sw_me, slot)
        for rp in ports.get(r) or {GAME_PORT}:
            theirs = station_forms_mk8d(r, rp)
            h2s += list(zip(theirs, mine))
            s2h += list(zip(mine, theirs))
        h2s += [(r, sw_me), (r[::-1], sw_me[::-1])]
    if host_ip:
        s2h += [(sw_me, host_ip), (sw_me[::-1], host_ip[::-1])]
    h2s += address_pairs([(h_me, sw)]) + id_fragments([(h_me, sw)])
    s2h += address_pairs([(sw, h_me)]) + id_fragments([(sw, h_me)])
    return {"s2h": [(o, n) for o, n in s2h if o != n], "h2s": [(o, n) for o, n in h2s if o != n]}

def relay_game_mk8d(key, src_ip, my_ip, d, table, on_plain=None):
    plain = open_game_mk8d(key, src_ip, d)
    if plain is None:
        return None
    if on_plain is not None:
        upd = on_plain(plain)
        if upd is not None:
            table = upd
    new = rewrite(plain, table)
    return seal_game_mk8d(key, my_ip, d, new), plain[:4].hex(), new != plain

_FIELDS = ((1, 1), (2, 2), (4, 1), (8, 1), (16, 1))

def trim_join_response(plain):
    pos, f, cut, seen = 0, {1: 0, 2: 0, 4: 0, 8: 0, 16: 0}, None, False
    while pos < len(plain):
        mask = plain[pos]
        if mask & ~31:
            return plain
        q = pos + 1
        for bit, w in _FIELDS:
            if mask & bit:
                f[bit] = int.from_bytes(plain[q:q + w], "big"); q += w
        end = q + f[2]
        if end > len(plain):
            return plain
        if f[4] == 13:
            seen |= f[2] == 198
            cut = end
        pos = end
    return plain[:cut] if seen and cut is not None and cut < len(plain) else plain

TRIM_JOIN = False
NO_BITS = False
REWRITE_PROTOS = None

def rewrite_some(plain, table, protos):
    out, pos, f = bytearray(plain), 0, {1: 0, 2: 0, 4: 0, 8: 0, 16: 0}
    while pos < len(plain):
        mask = plain[pos]
        if mask & ~31:
            break
        q = pos + 1
        for bit, w in _FIELDS:
            if mask & bit:
                f[bit] = int.from_bytes(plain[q:q + w], "big"); q += w
        end = q + f[2]
        if end > len(plain):
            break
        if f[4] in protos:
            out[q:end] = rewrite(plain[q:end], table)
        pos = end
    return bytes(out)

def _bits_port(port):
    return None if port is None else int.from_bytes(port.to_bytes(2, "big"), "little")

def rewrite_bits(body, ip_pairs):
    # Bit-packed station records (Broadcast Reliable): a 16-bit port, then a
    # 16-bit copy of the address's last two octets, then the address itself.
    # Pairs are (old, new) or (old, new, old_port, new_port); a pair with ports
    # only matches a record carrying old_port, and moves it to new_port.
    if len(body) < 4 or not ip_pairs:
        return body
    n = len(body) * 8
    x = int.from_bytes(body, "little")
    pats = []
    for pr in ip_pairs:
        o, nw = pr[0], pr[1]
        op, np_ = (pr[2], pr[3]) if len(pr) > 2 else (None, None)
        pats.append((int.from_bytes(o, "little"), int.from_bytes(nw, "little"),
                     int.from_bytes(o[2:4], "little"), int.from_bytes(nw[2:4], "little"),
                     _bits_port(op), _bits_port(np_)))
    first = {p[0] for p in pats}
    mask, m16, i, hit = 0xFFFFFFFF, 0xFFFF, 0, False
    while i <= n - 32:
        w = (x >> i) & mask
        if w in first:
            for p, q, p16, q16, pp, qp in pats:
                if w != p:
                    continue
                if pp is not None and (i < 32 or (x >> (i - 32)) & m16 != pp):
                    continue
                x = (x & ~(mask << i)) | (q << i)
                if i >= 16 and (x >> (i - 16)) & m16 == p16:
                    x = (x & ~(m16 << (i - 16))) | (q16 << (i - 16))
                if qp is not None and i >= 32:
                    x = (x & ~(m16 << (i - 32))) | (qp << (i - 32))
                hit = True
                break
            i += 32
        else:
            i += 1
    return x.to_bytes(len(body), "little") if hit else body

def relay_game(key, src_ip, my_ip, d, table, ip_pairs=None, on_plain=None):
    o = open_game(key, src_ip, d)
    if o is None:
        return None
    raw, compressed = o
    plain = _zstd_decompress(raw) if compressed else raw
    if on_plain is not None:
        upd = on_plain(plain)
        if upd is not None:
            table, ip_pairs = upd
    new = rewrite(plain, table) if REWRITE_PROTOS is None else rewrite_some(plain, table, REWRITE_PROTOS)
    if ip_pairs and not NO_BITS:
        new = rewrite_bits(new, ip_pairs)
    if TRIM_JOIN:
        new = trim_join_response(new)
    changed = new != plain
    body = (_zstd_compress(new) if compressed else new) if changed else raw
    return seal_game(key, my_ip, d, body), plain[:4].hex(), changed

def session_key(game_key, reply, off=105):
    param = bytearray(reply[off:off + 32])
    param[-1] = (param[-1] + 1) & 0xFF
    return hmac.new(game_key, bytes(param), hashlib.sha256).digest()[:16]

def splatoon_host_name(rep):
    try:
        if len(rep) > 41:
            n = rep[39]
            if 1 <= n <= 32 and rep[40] == 1 and 41 + n <= len(rep):
                name = rep[41:41 + n].decode("utf-8")
                if name and all(c.isprintable() for c in name):
                    return name
        chars = []
        for i in range(141, min(len(rep) - 1, 141 + 40), 2):
            code = rep[i] | (rep[i + 1] << 8)
            if code == 0:
                break
            chars.append(chr(code))
        name = "".join(chars)
        if name and all(c.isprintable() for c in name):
            return name
    except Exception:
        pass
    return None

def mk8d_host_name(rep):
    try:
        if len(rep) > 80:
            raw = rep[48:80]
            end = raw.find(b"\x00")
            if end >= 1:
                name = raw[:end].decode("utf-8")
                if name and all(c.isprintable() for c in name):
                    return name
    except Exception:
        pass
    return None

def splatoon2_host_name(rep):
    try:
        base = 5 + 0x1D2
        for i in range(16):
            off = base + i * 50
            if off + 42 > len(rep):
                break
            if rep[off] != 1:
                continue
            raw = bytes(rep[off + 2:off + 42])
            if rep[off + 1] == 2:
                name = raw.decode("utf-16-le", "ignore").split("\x00")[0]
            else:
                name = raw.split(b"\x00")[0].decode("utf-8", "ignore")
            name = name.strip()
            if name and all(c.isprintable() for c in name):
                return name
    except Exception:
        pass
    return None

def room_players(game, rep):
    try:
        if game == "mkw":
            n = rep[543] if len(rep) > 543 else 0
            return n if 1 <= n <= 24 else None
        if game == "splatoon3":
            n = rep[35] if len(rep) > 35 else 0
        elif len(rep) >= 39:
            n = int.from_bytes(rep[37:39], "big")
        else:
            n = 0
    except Exception:
        return None
    return n if 1 <= n <= 16 else None

SCAN_HEADER = bytes.fromhex("00000000160000000000000000000a000400060100c2500fc20000")

def make_scan_request(game_key, bc, counter):
    chal = os.urandom(16)
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter.to_bytes(8, "big")).encrypt_and_digest(os.urandom(256))
    return SCAN_HEADER + b"\x03\x01" + counter.to_bytes(8, "big") + chal + tag + ct, chal

def rekey_request(game_key, req, bc, counter):
    if len(req) != 325 or req[0] != 0:
        raise ValueError("not a browse request")
    cr = req[27:]
    k_old = AES.new(game_key, AES.MODE_ECB).encrypt(cr[10:26])
    pt = AES.new(k_old, AES.MODE_GCM, nonce=bc + cr[2:10]).decrypt_and_verify(cr[42:], cr[26:42])
    chal = os.urandom(16)
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter.to_bytes(8, "big")).encrypt_and_digest(pt)
    return req[:29] + counter.to_bytes(8, "big") + chal + tag + ct, chal

def rekey_request_mk8d(game_key, req, bc, counter):
    if len(req) != 873 or req[0] != 0:
        raise ValueError("not an mk8d browse request")
    cr = req[575:]
    k_old = AES.new(game_key, AES.MODE_ECB).encrypt(cr[10:26])
    pt = AES.new(k_old, AES.MODE_GCM, nonce=bc + cr[2:10]).decrypt_and_verify(cr[42:], cr[26:42])
    chal = os.urandom(16)
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + counter.to_bytes(8, "big")).encrypt_and_digest(pt)
    return req[:577] + counter.to_bytes(8, "big") + chal + tag + ct, chal

def mk8d_param_off(rep):
    size = int.from_bytes(rep[1:5], "big")
    if 32 <= size <= len(rep) - 5:
        return 5 + size - 32
    return PARAM_OFF["mk8d"]

def reseal_request(game_key, req, old_bc, new_bc, chal_off=27):
    if len(req) != chal_off + 298 or req[0] != 0:
        raise ValueError("not a browse request")
    cr = req[chal_off:]
    counter, chal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:]
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    nct, ntag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    return req[:chal_off + 26] + ntag + nct, chal


def reseal_request_pinned(game_key, req, old_bc, new_bc, chal_off, pin):
    if len(req) != chal_off + 298 or req[0] != 0:
        raise ValueError("not a browse request")
    cr = req[chal_off:]
    counter, chal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:]
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    k2 = AES.new(game_key, AES.MODE_ECB).encrypt(pin)
    nct, ntag = AES.new(k2, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    out = bytearray(req)
    out[chal_off + 10:chal_off + 26] = pin
    out[chal_off + 26:chal_off + 42] = ntag
    out[chal_off + 42:] = nct
    return bytes(out), chal

def reseal_reply(game_key, rep, req_chal, old_bc, new_bc, old_host, new_host, param_off=105, frag=False):
    if rep[0] != 1:
        raise ValueError("not a browse reply")
    off = 5 + int.from_bytes(rep[1:5], "big")
    cr = rep[off:off + 58]
    counter, rchal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:58]
    k = hmac.new(game_key, rchal + req_chal, hashlib.sha256).digest()[:16]
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    nct, ntag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    out = bytearray(rep)
    pats = [(old_host, new_host), (old_host[::-1], new_host[::-1])]
    if frag:
        pats += id_fragments([(old_host, new_host)])
    for o, n in pats:
        i = 0
        while (i := bytes(out).find(o, i)) >= 0:
            if not (param_off <= i < param_off + 32 or off <= i < off + 58):
                out[i:i + len(o)] = n
            i += len(o)
    out[off + 26:off + 42] = ntag
    out[off + 42:off + 58] = nct
    return bytes(out)


def reseal_reply_rekey(game_key, rep, chal_in, chal_out, old_bc, new_bc, old_host, new_host, param_off=105, frag=False):
    if rep[0] != 1:
        raise ValueError("not a browse reply")
    off = 5 + int.from_bytes(rep[1:5], "big")
    cr = rep[off:off + 58]
    counter, rchal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:58]
    k_in = hmac.new(game_key, rchal + chal_in, hashlib.sha256).digest()[:16]
    pt = AES.new(k_in, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    k_out = hmac.new(game_key, rchal + chal_out, hashlib.sha256).digest()[:16]
    nct, ntag = AES.new(k_out, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    out = bytearray(rep)
    pats = [(old_host, new_host), (old_host[::-1], new_host[::-1])]
    if frag:
        pats += id_fragments([(old_host, new_host)])
    for o, n in pats:
        i = 0
        while (i := bytes(out).find(o, i)) >= 0:
            if not (param_off <= i < param_off + 32 or off <= i < off + 58):
                out[i:i + len(o)] = n
            i += len(o)
    out[off + 26:off + 42] = ntag
    out[off + 42:off + 58] = nct
    return bytes(out)

MKW_SETTING_PLAIN = bytes.fromhex("0000000000000000001a0018000e0400c3f00006e00000000000000000000000")

def mkw_host_name(rep):
    try:
        if len(rep) > 105:
            n = int.from_bytes(rep[36:40], "little")
            if 1 <= n <= 64 and 41 + n <= len(rep):
                enc = rep[40]
                raw = bytes(rep[41:41 + n])
                name = raw.decode("utf-16-le", "ignore") if enc == 2 else raw.decode("utf-8", "ignore")
                name = name.split("\x00")[0].strip()
                if name and all(c.isprintable() for c in name):
                    return name
    except Exception:
        pass
    return None

def reseal_request_mkw(game_key, req, old_bc, new_bc):
    if len(req) != 352 or req[0] != 0 or req[37] != 5:
        raise ValueError("not an mkw browse request")
    counter, chal = req[40:48], req[48:64]
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(req[96:], req[64:80])
    st = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(req[5:37], req[80:96])
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    sct, stag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(st)
    return req[:5] + sct + req[37:64] + tag + stag + ct, chal

def rekey_request_mkw(game_key, req, bc, counter):
    if len(req) != 352 or req[0] != 0 or req[37] != 5:
        raise ValueError("not an mkw browse request")
    old_counter, old_chal = req[40:48], req[48:64]
    k_old = AES.new(game_key, AES.MODE_ECB).encrypt(old_chal)
    pt = AES.new(k_old, AES.MODE_GCM, nonce=bc + old_counter).decrypt_and_verify(req[96:], req[64:80])
    st = AES.new(k_old, AES.MODE_GCM, nonce=bc + old_counter).decrypt_and_verify(req[5:37], req[80:96])
    chal = os.urandom(16)
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    ctr = counter.to_bytes(8, "big")
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + ctr).encrypt_and_digest(pt)
    sct, stag = AES.new(k, AES.MODE_GCM, nonce=bc + ctr).encrypt_and_digest(st)
    return req[:5] + sct + req[37:40] + ctr + chal + tag + stag + ct, chal

def make_scan_request_mkw(game_key, bc, counter):
    chal = os.urandom(16)
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    ctr = counter.to_bytes(8, "big")
    sct, stag = AES.new(k, AES.MODE_GCM, nonce=bc + ctr).encrypt_and_digest(MKW_SETTING_PLAIN)
    ct, tag = AES.new(k, AES.MODE_GCM, nonce=bc + ctr).encrypt_and_digest(os.urandom(256))
    return b"\x00\x00\x00\x00\x20" + sct + b"\x05\x01\x01" + ctr + chal + tag + stag + ct, chal

def reseal_reply_mkw(game_key, rep, req_chal, old_bc, new_bc, old_host, new_host):
    if rep[0] != 1:
        raise ValueError("not a browse reply")
    off = 5 + int.from_bytes(rep[1:5], "big")
    if off + 75 > len(rep):
        raise ValueError("not an mkw browse reply")
    counter, rchal = rep[off + 3:off + 11], rep[off + 11:off + 27]
    k = hmac.new(game_key, rchal + req_chal, hashlib.sha256).digest()[:16]
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(rep[off + 59:off + 75], rep[off + 27:off + 43])
    nct, ntag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    out = bytearray(rep)
    for o, n in [(old_host, new_host), (old_host[::-1], new_host[::-1])]:
        i = 0
        while True:
            i = bytes(out).find(o, i)
            if i < 0:
                break
            if i < off:
                out[i:i + len(o)] = n
            i += len(o)
    out[off + 27:off + 43] = ntag
    out[off + 59:off + 75] = nct
    return bytes(out)

def udp_bind(ip, port, broadcast=False, bind_devices=None):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if broadcast:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    if bind_devices:
        if isinstance(bind_devices, str):
            bind_devices = [bind_devices]
        for dev in bind_devices:
            if not dev:
                continue
            try:
                s.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_BINDTODEVICE", 25), dev.encode())
                print(f"host device: {dev}", flush=True)
                break
            except Exception:
                pass
    try:
        s.bind((ip, port))
    except OSError as e:
        if ip == "0.0.0.0":
            raise
        print(f"warning: cannot bind {ip}:{port}, using all interfaces", flush=True)
        s.bind(("0.0.0.0", port))
    s.setblocking(False)
    return s

# capture files for later analysis
class Capture:
    def __init__(self, base):
        self.bin = open(base + ".bin", "wb", buffering=0)
        self.txt = open(base + ".txt", "w", buffering=1)
        self.off = 0
        self.n = 0

    def save(self, kind, data, src, dst, sport, dport):
        if kind not in ("disc", "sw-game", "hs-game"):
            return
        rec_src = src if kind != "hs-game" else dst
        rec_dst = dst if kind != "hs-game" else src
        proto = ""
        if kind != "disc" and len(data) >= 33:
            proto = "%02x%02x" % (data[32], data[33]) if data[:4] == MAGIC else ""
        self.txt.write("\t".join([kind, "x", "x", rec_src, rec_dst, "x", "x", "x", "x",
                                  str(sport), str(dport), str(self.n)]) + "\n")
        self.bin.write(data)
        self.off += len(data)
        self.n += 1

    def close(self):
        try:
            self.bin.close(); self.txt.close()
        except Exception:
            pass

class Relay:
    def __init__(self, a):
        self.scan_only = bool(getattr(a, "scan_only", False))
        self.game_key = GAME_KEY
        self.game_key_mk8d = GAME_KEY_MK8D
        self.game_key_splatoon2 = GAME_KEY_SPLATOON2
        self.game_key_mkw = GAME_KEY_MKW
        self.sw_ip = a.switch_ip or get_wifi_ip()
        self.h_ip = a.host_ip or detect_zerotier_ip(a.host_peer)
        self.sw_prefix = a.switch_prefix
        self.h_prefix = a.host_prefix
        self.sw_me, self.h_me = ip4(self.sw_ip), ip4(self.h_ip)
        self.sw_bc, self.h_bc = bcast_of(self.sw_ip, self.sw_prefix), bcast_of(self.h_ip, self.h_prefix)
        self.sw_net = ipaddress.IPv4Network(f"{self.sw_ip}/{self.sw_prefix}", strict=False)
        self.h_net = ipaddress.IPv4Network(f"{self.h_ip}/{self.h_prefix}", strict=False)
        self.delay = {"s2h": a.delay_s2h / 1000, "h2s": a.delay_h2s / 1000}
        self.only_switch = a.switch_peer
        self.switch = None
        self.host = (a.host_peer, GAME_PORT) if a.host_peer else None
        self.peer = None
        self.rooms = {}
        self.sessions = {}
        self.req_chals = []
        self.host_req_chals = []
        self.mk8d_req_chals = []
        self.mk8d_host_req_chals = []
        self.splatoon2_req_chals = []
        self.splatoon2_host_req_chals = []
        self.mkw_req_chals = []
        self.mkw_host_req_chals = []
        self.pin_s2h = os.urandom(16)
        self.pin_h2s = os.urandom(16)
        self.host_side = None
        self.last_sw_req = None
        self.last_sweep = 0.0
        self.scan_chals = []
        self.scan_counter = 0
        self.last_scan = 0.0
        self.stash_req = None
        self.stash_mk8d = None
        self.scan_chals_mk8d = []
        self.stash_splatoon2 = None
        self.scan_chals_splatoon2 = []
        self.stash_mkw = None
        self.scan_chals_mkw = []
        self.room_noted = {}
        self.room_names = {}
        self.room_counts = {}
        self.key = None
        self.key_mk8d = None
        self.key_splatoon2 = None
        self.key_mkw = None
        self.mk8d_state = False
        self.splatoon2_state = False
        self.mkw_state = False
        self.game = None
        self.last_sw_game = "splatoon3"
        self.tables = None
        self.sw_game_port = None
        self.h_game_port = None
        self.log = []
        self.queue = []
        self.t0 = time.time()
        self.seen = {}
        print(f"switch leg: {self.sw_ip} (broadcast {socket.inet_ntoa(self.sw_bc)})", flush=True)
        print(f"peer leg: {self.h_ip} (broadcast {socket.inet_ntoa(self.h_bc)})", flush=True)
        if self.host:
            print(f"peer: {self.host[0]}", flush=True)
        print("ready", flush=True)
        zt_cands = []
        for cand in (os.environ.get("ZT_IFACE"), get_zerotier_iface(self.h_ip), "tun0", "tun1"):
            if cand and cand not in zt_cands:
                zt_cands.append(cand)
        self.d_sw = udp_bind("0.0.0.0", DISCOVERY_PORT, broadcast=True)
        self.d_h = udp_bind(self.h_ip, DISCOVERY_PORT, broadcast=True, bind_devices=zt_cands)
        self.d_sw2 = udp_bind("0.0.0.0", MK8D_PORT, broadcast=True)
        self.d_h2 = udp_bind(self.h_ip, MK8D_PORT, broadcast=True, bind_devices=zt_cands)
        self.g_sw = udp_bind(self.sw_ip, GAME_PORT, broadcast=True)
        self.g_h = udp_bind(self.h_ip, GAME_PORT, broadcast=True, bind_devices=zt_cands)
        self.gb_sw = udp_bind(socket.inet_ntoa(self.sw_bc), GAME_PORT, broadcast=True)
        self.gb_h = udp_bind(socket.inet_ntoa(self.h_bc), GAME_PORT, broadcast=True, bind_devices=zt_cands)
        self.gx = []
        for p in GAME_PORTS[1:]:
            self.gx.append((udp_bind(self.sw_ip, p, broadcast=True), "sw"))
            self.gx.append((udp_bind(socket.inet_ntoa(self.sw_bc), p, broadcast=True), "sw"))
            self.gx.append((udp_bind(self.h_ip, p, broadcast=True, bind_devices=zt_cands), "h"))
            self.gx.append((udp_bind(socket.inet_ntoa(self.h_bc), p, broadcast=True, bind_devices=zt_cands), "h"))
        self.sw_slot_socks = {GAME_PORT: self.g_sw}
        for s, leg in self.gx:
            if leg == "sw" and s.getsockname()[0] == self.sw_ip:
                self.sw_slot_socks[s.getsockname()[1]] = s
        for p in SLOT_PORTS:
            if p not in self.sw_slot_socks:
                s = udp_bind(self.sw_ip, p, broadcast=True)
                self.sw_slot_socks[p] = s
                self.gx.append((s, "sw"))
        self.h_slot_socks = {GAME_PORT: self.g_h}
        for s, leg in self.gx:
            if leg == "h" and s.getsockname()[0] == self.h_ip:
                self.h_slot_socks[s.getsockname()[1]] = s
        self.slots = {}
        self.station_ports = {}
        self.cap = Capture(a.capture) if a.capture else None

    def note(self, **kw):
        kw["t"] = round(time.time() - self.t0, 6)
        self.log.append(kw)
        evf = getattr(self, "_evf", None)
        if evf is not None:
            try:
                evf.write(json.dumps(kw) + "\n")
            except Exception:
                pass

    def session_peer(self):
        if self.host_side == "sw":
            return self.peer or self.host
        return self.host or self.peer

    def tables_for(self, peer_ip):
        sw, h = ip4(self.switch[0]), ip4(peer_ip)
        tables = {
            "s2h": address_pairs([(self.sw_me, h), (sw, self.h_me)]),
            "h2s": address_pairs([(h, self.sw_me), (self.h_me, sw)]),
        }
        ip_pairs = {
            "s2h": [(o, n) for o, n in [(self.sw_me, h), (sw, self.h_me)] if o != n],
            "h2s": [(o, n) for o, n in [(h, self.sw_me), (self.h_me, sw)] if o != n],
        }
        tables_mk8d = {
            "s2h": tables["s2h"] + id_fragments([(self.sw_me, h), (sw, self.h_me)]),
            "h2s": tables["h2s"] + id_fragments([(h, self.sw_me), (self.h_me, sw)]),
        }
        return tables, ip_pairs, tables_mk8d

    def slot_of(self, ip):
        return getattr(self, "slots", {}).get(ip)

    def owner_of_port(self, port):
        for ip, p in getattr(self, "slots", {}).items():
            if p == port:
                return ip
        return None

    def add_station(self, ip):
        slots = self.__dict__.setdefault("slots", {})
        if ip in slots or ip == self.h_ip:
            return False
        used = set(slots.values())
        free = [p for p in SLOT_PORTS if p not in used and p in getattr(self, "sw_slot_socks", {GAME_PORT: None})]
        if not free:
            return False
        slots[ip] = free[0]
        print(f"station: {ip} mapped to {self.sw_ip}:{free[0]}", flush=True)
        self.note(ev="station", ip=ip, port=free[0])
        return True

    def rebuild_slot_tables(self):
        peer = self.session_peer()
        if not self.switch or not peer:
            return
        slots = {ip4(ip): p for ip, p in self.slots.items()}
        t, pr = slot_tables(slots, ip4(peer[0]), ip4(self.switch[0]), self.sw_me, self.h_me)
        self.tables, self.ip_pairs = t, pr

    def learn_stations(self, src_ip, plain):
        changed = self.add_station(src_ip)
        net = getattr(self, "h_net", None)
        for raw in find_stations(plain, self.h_me[:3]):
            ip = socket.inet_ntoa(raw)
            if net is not None and (ipaddress.IPv4Address(ip) in (net.network_address, net.broadcast_address)):
                continue
            changed = self.add_station(ip) or changed
        if changed:
            self.rebuild_slot_tables()
            return self.tables["h2s"], self.ip_pairs["h2s"]
        return None

    def rebuild_mk8d_tables(self):
        peer = self.session_peer()
        if not self.switch or not peer:
            return
        raw_slots = getattr(self, "slots", {})
        if not raw_slots:
            return
        slots = {ip4(ip): p for ip, p in raw_slots.items()}
        ports = {}
        for ip in raw_slots:
            ps = {GAME_PORT}
            sp = getattr(self, "station_ports", {}).get(ip)
            if sp:
                ps.add(sp)
            cp = self.__dict__.get("claim_ports", {}).get(ip)
            if cp:
                ps.add(cp)
            ports[ip4(ip)] = ps
        self.tables_mk8d = slot_tables_mk8d(slots, ports, ip4(peer[0]),
                                             ip4(self.switch[0]), self.sw_me, self.h_me)

    def learn_stations_mk8d(self, src_ip, plain, game):
        changed = self.add_station(src_ip)
        if game == "splatoon2" and len(plain) >= 0x33 and plain[0x29:0x2d] == ip4(src_ip) \
                and plain[0x2f:0x33] == ip4(src_ip):
            claims = self.__dict__.setdefault("claim_ports", {})
            cp = (plain[0x2d] << 8) | plain[0x2e]
            if claims.get(src_ip) != cp:
                claims[src_ip] = cp
                changed = True
        net = getattr(self, "h_net", None)
        for raw in find_stations(plain, self.h_me[:3]):
            ip = socket.inet_ntoa(raw)
            if net is not None and (ipaddress.IPv4Address(ip) in (net.network_address, net.broadcast_address)):
                continue
            changed = self.add_station(ip) or changed
        if changed:
            self.rebuild_mk8d_tables()
            return self.tables_mk8d["h2s"]
        return None

    def build_tables(self):
        peer = self.session_peer()
        self.tables, self.ip_pairs, self.tables_mk8d = self.tables_for(peer[0])
        slots = self.__dict__.setdefault("slots", {})
        others = {ip: p for ip, p in slots.items() if ip != peer[0] and p != GAME_PORT}
        slots.clear()
        slots[peer[0]] = GAME_PORT
        slots.update(others)
        self.rebuild_slot_tables()
        self.rebuild_mk8d_tables()
        if self.host_side == "sw":
            print(f"paired: Switch {self.switch[0]} is hosting, peer {peer[0]} joined", flush=True)
        elif self.host_side == "h":
            print(f"paired: Switch {self.switch[0]} with host {peer[0]}", flush=True)
        else:
            print(f"paired: Switch {self.switch[0]} with peer {peer[0]}", flush=True)

    def send_extra_ports(self, sock, data, dest_ip, skip_port):
        for p in GAME_PORTS:
            if p == skip_port:
                continue
            try:
                sock.sendto(data, (dest_ip, p))
            except OSError:
                pass

    def send(self, sock, data, dest, direction, **meta):
        due = time.time() + self.delay.get(direction, 0)
        if due <= time.time():
            self._tx(sock, data, dest, direction, meta)
        else:
            heapq.heappush(self.queue, (due, id(data), sock, data, dest, direction, meta))

    def _tx(self, sock, data, dest, direction, meta):
        try:
            sock.sendto(data, dest)
            self.note(ev="tx", dir=direction, dst=dest[0], len=len(data), **meta)
        except OSError as e:
            self._tx_warn = getattr(self, "_tx_warn", 0) + 1
            if self._tx_warn == 1 or self._tx_warn % 50 == 0:
                print(f"warning: send to {dest} failed ({e})", flush=True)
            self.note(ev="txerr", dir=direction, err=e.errno, **meta)

    # identical datagrams inside 1.5 s are handled once
    def dup(self, data, src):
        now = time.time()
        self.seen = {k: v for k, v in self.seen.items() if now - v < 1.5}
        k = (src, hashlib.sha256(data).digest())
        if k in self.seen:
            return True
        self.seen[k] = now
        return False

    def leg_of(self, src_ip):
        try:
            a = ipaddress.IPv4Address(src_ip)
        except ValueError:
            return None
        if a in self.h_net:
            return "h"
        if a in self.sw_net:
            return "sw"
        return None

    def disc_note(self, msg):
        self._disc_warn = getattr(self, "_disc_warn", 0) + 1
        if self._disc_warn == 1 or self._disc_warn % 50 == 0:
            print(msg, flush=True)

    def key_for(self, game):
        if game == "splatoon3":
            return self.game_key
        if game == "splatoon2":
            return self.game_key_splatoon2
        if game == "mkw":
            return self.game_key_mkw
        return self.game_key_mk8d

    def chals_for(self, direction, game):
        if direction == "s2h":
            if game == "splatoon3":
                return self.req_chals
            if game == "splatoon2":
                return self.splatoon2_req_chals
            if game == "mkw":
                return self.mkw_req_chals
            return self.mk8d_req_chals
        if game == "splatoon3":
            return self.host_req_chals
        if game == "splatoon2":
            return self.splatoon2_host_req_chals
        if game == "mkw":
            return self.mkw_host_req_chals
        return self.mk8d_host_req_chals

    def add_chal(self, direction, game, chal):
        lst = self.chals_for(direction, game)
        lst[:] = ([chal] + [c for c in lst if c != chal])[:8]

    def note_game(self, game):
        if game != self.game:
            self.game = game
            print(f"game: {GAME_NAMES[game]}", flush=True)

    def port_for(self, game):
        return MK8D_PORT if game in ("mk8d", "splatoon2") else DISCOVERY_PORT

    def sock_for(self, leg, game):
        if leg == "h":
            return self.d_h2 if game in ("mk8d", "splatoon2") else self.d_h
        return self.d_sw2 if game in ("mk8d", "splatoon2") else self.d_sw

    def take_session_key(self, game, data):
        off = mk8d_param_off(data) if game in ("mk8d", "splatoon2") else PARAM_OFF[game]
        k = session_key(self.key_for(game), data, off)
        if game == "mk8d":
            if k != self.key_mk8d:
                self.key_mk8d = k
                self.mk8d_state = False
                print("session started", flush=True)
        elif game == "splatoon2":
            if k != self.key_splatoon2:
                self.key_splatoon2 = k
                self.splatoon2_state = False
                print("session started", flush=True)
        elif game == "mkw":
            if k != self.key_mkw:
                self.key_mkw = k
                self.mkw_state = False
                print("session started", flush=True)
        elif k != self.key:
            self.key = k
            print("session started", flush=True)

    def on_disc(self, data, src):
        if src[0] in (self.sw_ip, self.h_ip):
            return
        leg = self.leg_of(src[0])
        if leg is None or self.dup(data, src):
            return
        if leg == "sw":
            self.on_disc_switch(data, src)
        else:
            self.on_disc_host(data, src)

    def on_disc_switch(self, data, src):
        if self.only_switch and src[0] != self.only_switch:
            return
        if len(data) >= 64 and data[0] == 1:
            return self.on_reply_from_switch(data, src)
        game = detect_game(data)
        if game is None:
            return
        out, chal = None, None
        for g in (("mk8d", "splatoon2") if game == "mk8d" else (game,)):
            if not self.key_for(g):
                continue
            try:
                if g == "splatoon3":
                    out, chal = reseal_request(self.key_for(g), data, self.sw_bc, self.h_bc, CHAL_OFF[g])
                elif g == "mkw":
                    out, chal = reseal_request_mkw(self.key_for(g), data, self.sw_bc, self.h_bc)
                else:
                    out, chal = reseal_request_pinned(self.key_for(g), data, self.sw_bc, self.h_bc, CHAL_OFF[g], self.pin_s2h)
                game = g
                break
            except Exception:
                continue
        if out is None:
            if self.cap:
                self.cap.save("disc", data, src[0], socket.inet_ntoa(self.sw_bc), src[1], self.port_for(game))
            self.disc_note(f"note: a browse request from {src} could not be opened")
            return
        self.note_game(game)
        if self.switch and self.switch[0] != src[0]:
            print(f"console changed: {self.switch[0]} to {src[0]}", flush=True)
            self.switch = (src[0], GAME_PORT)
            if self.session_peer():
                self.build_tables()
        self.switch = (src[0], self.switch[1] if self.switch else GAME_PORT)
        self.add_chal("s2h", game, chal)
        self.note(ev="rx", dir="s2h", kind="browse_req", src=src[0], len=len(data))
        port = self.port_for(game)
        sock = self.sock_for("h", game)
        if self.cap:
            self.cap.save("disc", data, src[0], socket.inet_ntoa(self.sw_bc), src[1], port)
        self.last_sw_req = out
        if game == "splatoon3":
            self.stash_req = out
            self.save_stash()
        elif game == "mk8d":
            self.stash_mk8d = out
            self.save_stash_mk8d()
        elif game == "splatoon2":
            self.stash_splatoon2 = out
            self.save_stash_splatoon2()
        elif game == "mkw":
            self.stash_mkw = out
            self.save_stash_mkw()
        self.last_sw_game = game
        self.send(sock, out, (socket.inet_ntoa(self.h_bc), port), "s2h", kind="browse_req")
        now = time.time()
        self.rooms = {k: t for k, t in self.rooms.items() if now - t < 60}
        targets = {ip for (g, ip) in self.rooms if g == game}
        if self.host:
            targets.add(self.host[0])
        for ip in sorted(targets):
            self.send(sock, out, (ip, port), "s2h", kind="browse_req_uni")
        if not targets and now - self.last_sweep >= 5:
            self.last_sweep = now
            count = min(self.h_net.num_addresses, 256)
            base = int(self.h_net.network_address)
            for i in range(count):
                ip = str(ipaddress.IPv4Address(base + i))
                if ip != self.h_ip and ip != socket.inet_ntoa(self.h_bc):
                    self.send(sock, out, (ip, port), "s2h", kind="browse_req_uni", probe=1)

    def stash_path(self):
        ev = os.environ.get("EVENTS")
        if ev:
            return os.path.join(os.path.dirname(ev), "last_browse_request.bin")
        return None

    def save_stash(self):
        path = self.stash_path()
        if not path or not self.stash_req:
            return
        try:
            with open(path, "wb") as fh:
                fh.write(self.stash_req)
        except Exception:
            pass

    def load_stash(self):
        path = self.stash_path()
        if not path:
            return
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            if len(data) == 325 and data[0] == 0:
                self.stash_req = data
        except Exception:
            pass

    def stash_path_mk8d(self):
        ev = os.environ.get("EVENTS")
        if ev:
            return os.path.join(os.path.dirname(ev), "last_browse_request_mk8d.bin")
        return None

    def save_stash_mk8d(self):
        path = self.stash_path_mk8d()
        if not path or not self.stash_mk8d:
            return
        try:
            with open(path, "wb") as fh:
                fh.write(self.stash_mk8d)
        except Exception:
            pass

    def load_stash_mk8d(self):
        path = self.stash_path_mk8d()
        if not path:
            return
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            if len(data) == 873 and data[0] == 0:
                self.stash_mk8d = data
        except Exception:
            pass

    def scan_poll_mk8d(self):
        if self.key_mk8d or not self.stash_mk8d:
            return
        self.scan_counter += 1
        try:
            req, chal = rekey_request_mk8d(self.key_for("mk8d"), self.stash_mk8d, self.h_bc, self.scan_counter)
        except Exception:
            return
        self.scan_chals_mk8d.append(chal)
        del self.scan_chals_mk8d[:-4]
        sock = self.sock_for("h", "mk8d")
        port = self.port_for("mk8d")
        self.send(sock, req, (socket.inet_ntoa(self.h_bc), port), "scan", kind="browse_req_scan", probe=1)
        now = time.time()
        self.rooms = {k: v for k, v in self.rooms.items() if now - v < 60}
        targets = {ip for (g, ip) in self.rooms if g == "mk8d"}
        if self.host:
            targets.add(self.host[0])
        targets.discard(self.h_ip)
        if targets:
            for ip in sorted(targets):
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)
        elif self.h_net is not None:
            for addr in self.h_net.hosts():
                ip = str(addr)
                if ip == self.h_ip:
                    continue
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)

    def stash_path_splatoon2(self):
        ev = os.environ.get("EVENTS")
        if ev:
            return os.path.join(os.path.dirname(ev), "last_browse_request_splatoon2.bin")
        return None

    def save_stash_splatoon2(self):
        path = self.stash_path_splatoon2()
        if not path or not self.stash_splatoon2:
            return
        try:
            with open(path, "wb") as fh:
                fh.write(self.stash_splatoon2)
        except Exception:
            pass

    def load_stash_splatoon2(self):
        path = self.stash_path_splatoon2()
        if not path:
            return
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            if len(data) == 873 and data[0] == 0:
                self.stash_splatoon2 = data
        except Exception:
            pass

    def stash_path_mkw(self):
        ev = os.environ.get("EVENTS")
        if ev:
            return os.path.join(os.path.dirname(ev), "last_browse_request_mkw.bin")
        return None

    def save_stash_mkw(self):
        path = self.stash_path_mkw()
        if not path or not self.stash_mkw:
            return
        try:
            with open(path, "wb") as fh:
                fh.write(self.stash_mkw)
        except Exception:
            pass

    def load_stash_mkw(self):
        path = self.stash_path_mkw()
        if not path:
            return
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            if len(data) == 352 and data[0] == 0:
                self.stash_mkw = data
        except Exception:
            pass

    def scan_poll_mkw(self):
        if self.key_mkw:
            return
        self.scan_counter += 1
        req = None
        if self.stash_mkw:
            try:
                req, chal = rekey_request_mkw(self.key_for("mkw"), self.stash_mkw, self.h_bc, self.scan_counter)
            except Exception:
                req = None
        if req is None:
            req, chal = make_scan_request_mkw(self.key_for("mkw"), self.h_bc, self.scan_counter)
        self.scan_chals_mkw.append(chal)
        del self.scan_chals_mkw[:-4]
        sock = self.sock_for("h", "mkw")
        port = self.port_for("mkw")
        self.send(sock, req, (socket.inet_ntoa(self.h_bc), port), "scan", kind="browse_req_scan", probe=1)
        now = time.time()
        self.rooms = {k: v for k, v in self.rooms.items() if now - v < 60}
        targets = {ip for (g, ip) in self.rooms if g == "mkw"}
        if self.host:
            targets.add(self.host[0])
        targets.discard(self.h_ip)
        if targets:
            for ip in sorted(targets):
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)
        elif self.h_net is not None:
            for addr in self.h_net.hosts():
                ip = str(addr)
                if ip == self.h_ip:
                    continue
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)

    def scan_poll_splatoon2(self):
        if self.key_splatoon2 or not self.stash_splatoon2:
            return
        self.scan_counter += 1
        try:
            req, chal = rekey_request_mk8d(self.key_for("splatoon2"), self.stash_splatoon2, self.h_bc, self.scan_counter)
        except Exception:
            return
        self.scan_chals_splatoon2.append(chal)
        del self.scan_chals_splatoon2[:-4]
        sock = self.sock_for("h", "splatoon2")
        port = self.port_for("splatoon2")
        self.send(sock, req, (socket.inet_ntoa(self.h_bc), port), "scan", kind="browse_req_scan", probe=1)
        now = time.time()
        self.rooms = {k: v for k, v in self.rooms.items() if now - v < 60}
        targets = {ip for (g, ip) in self.rooms if g == "splatoon2"}
        if self.host:
            targets.add(self.host[0])
        targets.discard(self.h_ip)
        if targets:
            for ip in sorted(targets):
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)
        elif self.h_net is not None:
            for addr in self.h_net.hosts():
                ip = str(addr)
                if ip == self.h_ip:
                    continue
                self.send(sock, req, (ip, port), "scan", kind="browse_req_scan_uni", probe=1)

    def scan_poll(self):
        self.scan_counter += 1
        req = None
        if self.stash_req:
            try:
                req, chal = rekey_request(self.key_for("splatoon3"), self.stash_req, self.h_bc, self.scan_counter)
            except Exception:
                req = None
        if req is None:
            req, chal = make_scan_request(self.key_for("splatoon3"), self.h_bc, self.scan_counter)
        self.scan_chals.append(chal)
        del self.scan_chals[:-4]
        sock = self.sock_for("h", "splatoon3")
        port = self.port_for("splatoon3")
        self.send(sock, req, (socket.inet_ntoa(self.h_bc), port), "s2h", kind="browse_req_scan", probe=1)
        now = time.time()
        self.rooms = {k: t for k, t in self.rooms.items() if now - t < 60}
        targets = {ip for (g, ip) in self.rooms if g == "splatoon3"}
        if self.host:
            targets.add(self.host[0])
        for ip in sorted(targets):
            self.send(sock, req, (ip, port), "s2h", kind="browse_req_scan_uni", probe=1)
        if not targets:
            count = min(self.h_net.num_addresses, 256)
            base = int(self.h_net.network_address)
            for i in range(count):
                ip = str(ipaddress.IPv4Address(base + i))
                if ip != self.h_ip and ip != socket.inet_ntoa(self.h_bc):
                    self.send(sock, req, (ip, port), "s2h", kind="browse_req_scan_uni", probe=1)

    def on_disc_host(self, data, src):
        game = detect_game(data)
        if game is not None:
            if getattr(self, "scan_only", False):
                return
            return self.on_request_from_host(data, src, game)
        if len(data) >= 64 and data[0] == 1 and self.host is None:
            self.host = (src[0], GAME_PORT)
            if self.last_sw_req:
                g = self.last_sw_game
                self.send(self.sock_for("h", g), self.last_sw_req, (src[0], self.port_for(g)), "s2h", kind="browse_req_uni")
        if len(data) >= 64 and data[0] == 1 and self.scan_chals:
            opened = False
            for chal in self.scan_chals:
                try:
                    reseal_reply(self.key_for("splatoon3"), data, chal, self.h_bc, self.h_bc,
                                 ip4(src[0]), ip4(src[0]))
                    opened = True
                    break
                except Exception:
                    continue
            if opened:
                self.rooms[("splatoon3", src[0])] = time.time()
                rname = splatoon_host_name(data)
                if rname:
                    self.note_room("splatoon3", src[0], rname, room_players("splatoon3", data))
                return
            self.disc_note(f"note: a reply from {src} did not open with a scan challenge")
            if self.cap:
                self.cap.save("disc", data, src[0], self.h_ip, src[1], src[1])
        if len(data) >= 64 and data[0] == 1 and self.scan_chals_mk8d:
            cands = list(self.scan_chals_mk8d)
            if not self.switch:
                cands.append(self.pin_s2h)
            opened = False
            for chal in cands:
                try:
                    reseal_reply(self.key_for("mk8d"), data, chal, self.h_bc, self.h_bc,
                                 ip4(src[0]), ip4(src[0]), mk8d_param_off(data), frag=True)
                    opened = True
                    break
                except Exception:
                    continue
            if opened:
                self.rooms[("mk8d", src[0])] = time.time()
                self.note_room("mk8d", src[0], mk8d_host_name(data), room_players("mk8d", data))
                return
            self.disc_note(f"note: a reply from {src} did not open with a scan challenge")
            if self.cap:
                self.cap.save("disc", data, src[0], self.h_ip, src[1], src[1])
        if len(data) >= 64 and data[0] == 1 and self.scan_chals_splatoon2:
            cands = list(self.scan_chals_splatoon2)
            if not self.switch:
                cands.append(self.pin_s2h)
            opened = False
            for chal in cands:
                try:
                    reseal_reply(self.key_for("splatoon2"), data, chal, self.h_bc, self.h_bc,
                                 ip4(src[0]), ip4(src[0]), mk8d_param_off(data))
                    opened = True
                    break
                except Exception:
                    continue
            if opened:
                self.rooms[("splatoon2", src[0])] = time.time()
                self.note_room("splatoon2", src[0], splatoon2_host_name(data), room_players("splatoon2", data))
                return
            self.disc_note(f"note: a reply from {src} did not open with a scan challenge")
            if self.cap:
                self.cap.save("disc", data, src[0], self.h_ip, src[1], src[1])
        if len(data) >= 64 and data[0] == 1 and self.scan_chals_mkw:
            opened = False
            for chal in self.scan_chals_mkw:
                try:
                    reseal_reply_mkw(self.key_for("mkw"), data, chal, self.h_bc, self.h_bc,
                                     ip4(src[0]), ip4(src[0]))
                    opened = True
                    break
                except Exception:
                    continue
            if opened:
                self.rooms[("mkw", src[0])] = time.time()
                self.note_room("mkw", src[0], mkw_host_name(data), room_players("mkw", data))
                return
            self.disc_note(f"note: a reply from {src} did not open with a scan challenge")
            if self.cap:
                self.cap.save("disc", data, src[0], self.h_ip, src[1], src[1])
        if len(data) < 64 or data[0] != 1 or not self.switch:
            return
        out, game = None, None
        if self.mk8d_req_chals:
            try:
                out = reseal_reply_rekey(self.key_for("mk8d"), data, self.pin_s2h, self.mk8d_req_chals[0],
                                         self.h_bc, self.sw_bc, ip4(src[0]), self.sw_me,
                                         mk8d_param_off(data), frag=True)
                game = "mk8d"
            except Exception:
                out = None
        if out is None and self.splatoon2_req_chals:
            try:
                out = reseal_reply_rekey(self.key_for("splatoon2"), data, self.pin_s2h, self.splatoon2_req_chals[0],
                                         self.h_bc, self.sw_bc, ip4(src[0]), self.sw_me,
                                         mk8d_param_off(data), frag=True)
                game = "splatoon2"
            except Exception:
                out = None
        if out is None and self.mkw_req_chals:
            try:
                out = reseal_reply_mkw(self.key_for("mkw"), data, self.mkw_req_chals[0],
                                       self.h_bc, self.sw_bc, ip4(src[0]), self.sw_me)
                game = "mkw"
            except Exception:
                out = None
        if out is None:
            for g in ("splatoon3", "mk8d", "splatoon2"):
                for chal in self.chals_for("s2h", g):
                    try:
                        out = reseal_reply(self.key_for(g), data, chal, self.h_bc, self.sw_bc,
                                           ip4(src[0]), self.sw_me,
                                           mk8d_param_off(data) if g in ("mk8d", "splatoon2") else PARAM_OFF[g],
                                           frag=(g in ("mk8d", "splatoon2")))
                        game = g
                        break
                    except Exception:
                        continue
                if out is not None:
                    break
        if out is None:
            if self.cap:
                self.cap.save("disc", data, src[0], self.switch[0], src[1], src[1])
            self.disc_note(f"note: a browse reply from {src} matched no recent request")
            return
        self.note_game(game)
        self.take_session_key(game, data)
        self.host_side = "h"
        if self.host is None or self.host[0] != src[0]:
            self.host = (src[0], GAME_PORT)
            self.build_tables()
        elif self.tables is None:
            self.build_tables()
        self.rooms[(game, src[0])] = time.time()
        if game == "splatoon3":
            rname = splatoon_host_name(data)
            if rname:
                self.note_room("splatoon3", src[0], rname, room_players("splatoon3", data))
        elif game == "mk8d":
            self.note_room("mk8d", src[0], mk8d_host_name(data), room_players("mk8d", data))
        elif game == "splatoon2":
            self.note_room("splatoon2", src[0], splatoon2_host_name(data), room_players("splatoon2", data))
        elif game == "mkw":
            self.note_room("mkw", src[0], mkw_host_name(data), room_players("mkw", data))
        entry = self.sessions.setdefault(src[0], {"keys": {}, "tables": None})
        entry["keys"][game] = self.key_mk8d if game == "mk8d" else (self.key_splatoon2 if game == "splatoon2" else (self.key_mkw if game == "mkw" else self.key))
        entry["tables"] = self.tables_for(src[0])
        self.note(ev="rx", dir="h2s", kind="browse_reply", src=src[0], len=len(data))
        port = self.port_for(game)
        if self.cap:
            self.cap.save("disc", data, src[0], self.switch[0], src[1], port)
        self.send(self.sock_for("sw", game), out, (self.switch[0], port), "h2s", kind="browse_reply")

    def on_request_from_host(self, data, src, game):
        out, chal = None, None
        for g in (("mk8d", "splatoon2") if game == "mk8d" else (game,)):
            if not self.key_for(g):
                continue
            try:
                if g == "splatoon3":
                    out, chal = reseal_request(self.key_for(g), data, self.h_bc, self.sw_bc, CHAL_OFF[g])
                elif g == "mkw":
                    out, chal = reseal_request_mkw(self.key_for(g), data, self.h_bc, self.sw_bc)
                else:
                    out, chal = reseal_request_pinned(self.key_for(g), data, self.h_bc, self.sw_bc, CHAL_OFF[g], self.pin_h2s)
                game = g
                break
            except Exception:
                continue
        if out is None:
            if self.cap:
                self.cap.save("disc", data, src[0], socket.inet_ntoa(self.h_bc), src[1], self.port_for(game))
            self.disc_note(f"note: a browse request from peer {src} could not be opened")
            return
        self.note_game(game)
        self.peer = (src[0], self.peer[1] if self.peer else GAME_PORT)
        self.add_chal("h2s", game, chal)
        self.note(ev="rx", dir="h2s", kind="browse_req", src=src[0], len=len(data))
        port = self.port_for(game)
        sock = self.sock_for("sw", game)
        if self.cap:
            self.cap.save("disc", data, src[0], socket.inet_ntoa(self.h_bc), src[1], port)
        self.send(sock, out, (socket.inet_ntoa(self.sw_bc), port), "h2s", kind="browse_req")
        if self.switch:
            self.send(sock, out, (self.switch[0], port), "h2s", kind="browse_req_uni")

    def on_reply_from_switch(self, data, src):
        dest_peer = self.peer or self.host
        if not dest_peer:
            return
        out, game = None, None
        if self.mk8d_host_req_chals:
            try:
                out = reseal_reply_rekey(self.key_for("mk8d"), data, self.pin_h2s, self.mk8d_host_req_chals[0],
                                         self.sw_bc, self.h_bc, ip4(src[0]), self.h_me,
                                         mk8d_param_off(data), frag=True)
                game = "mk8d"
            except Exception:
                out = None
        if out is None and self.splatoon2_host_req_chals:
            try:
                out = reseal_reply_rekey(self.key_for("splatoon2"), data, self.pin_h2s, self.splatoon2_host_req_chals[0],
                                         self.sw_bc, self.h_bc, ip4(src[0]), self.h_me,
                                         mk8d_param_off(data), frag=True)
                game = "splatoon2"
            except Exception:
                out = None
        if out is None and self.mkw_host_req_chals:
            try:
                out = reseal_reply_mkw(self.key_for("mkw"), data, self.mkw_host_req_chals[0],
                                       self.sw_bc, self.h_bc, ip4(src[0]), self.h_me)
                game = "mkw"
            except Exception:
                out = None
        if out is None:
            for g in ("splatoon3", "mk8d", "splatoon2"):
                for chal in self.chals_for("h2s", g):
                    try:
                        out = reseal_reply(self.key_for(g), data, chal, self.sw_bc, self.h_bc,
                                           ip4(src[0]), self.h_me,
                                           mk8d_param_off(data) if g in ("mk8d", "splatoon2") else PARAM_OFF[g],
                                           frag=(g in ("mk8d", "splatoon2")))
                        game = g
                        break
                    except Exception:
                        continue
                if out is not None:
                    break
        if out is None:
            if self.cap:
                self.cap.save("disc", data, src[0], dest_peer[0], src[1], src[1])
            self.disc_note(f"note: a Switch reply from {src} matched no recent peer request")
            return
        self.note_game(game)
        self.take_session_key(game, data)
        self.host_side = "sw"
        if self.switch is None or self.switch[0] != src[0]:
            self.switch = (src[0], GAME_PORT)
            self.build_tables()
        elif self.tables is None:
            self.build_tables()
        self.note(ev="rx", dir="s2h", kind="browse_reply", src=src[0], len=len(data))
        port = self.port_for(game)
        if self.cap:
            self.cap.save("disc", data, src[0], dest_peer[0], src[1], port)
        self.send(self.sock_for("h", game), out, (dest_peer[0], port), "s2h", kind="browse_reply")

    def note_room(self, game, host, name=None, players=None):
        if name:
            self.room_names[host] = name
        if players:
            self.room_counts[host] = players
        kw = {}
        known = self.room_names.get(host)
        if known:
            kw["name"] = known
        if self.room_counts.get(host):
            kw["players"] = self.room_counts[host]
        self.note(ev="room", game=game, host=host, ts=int(time.time()), **kw)

    def touch_room(self, host, game="mk8d"):
        now = time.time()
        if now - self.room_noted.get(host, 0.0) < 30:
            return
        self.room_noted[host] = now
        self.rooms[(game, host)] = now
        self.note_room(game, host)

    def on_game(self, data, src, direction, local_port=None):
        if not self.tables or (not self.key and not self.key_mk8d and not self.key_splatoon2 and not self.key_mkw):
            self.note(ev="drop_nokey", dir=direction, src=src[0], len=len(data))
            return
        if self.game in ("mk8d", "splatoon2"):
            peer = self.host[0] if direction == "s2h" and self.host else (src[0] if direction == "h2s" else None)
            if peer:
                self.touch_room(peer, self.game)
        if self.game in ("mk8d", "splatoon2") and self.dup(data, src):
            return
        if direction == "s2h":
            if self.switch is None or self.switch[0] != src[0]:
                if self.only_switch and src[0] != self.only_switch:
                    return
                self.switch = src
                if self.session_peer():
                    self.build_tables()
            self.switch = src
            table, pairs, my_ip = self.tables["s2h"], self.ip_pairs["s2h"], self.h_me
            sock, dest = self.g_h, self.session_peer()
            bdest = (socket.inet_ntoa(self.h_bc), GAME_PORT)
        else:
            table, pairs, my_ip = self.tables["h2s"], self.ip_pairs["h2s"], self.sw_me
            sock, dest = self.g_sw, self.switch
            bdest = (socket.inet_ntoa(self.sw_bc), GAME_PORT)
        if direction == "s2h":
            if src[1] in GAME_PORTS:
                self.sw_game_port = src[1]
            udest = (dest[0], self.h_game_port or GAME_PORT)
            pknown = self.h_game_port is not None
        else:
            if src[1] in GAME_PORTS:
                self.h_game_port = src[1]
            udest = (dest[0], self.sw_game_port or GAME_PORT)
            pknown = self.sw_game_port is not None
        order = ("mk8d", "splatoon2", "splatoon3") if self.game in ("mk8d", "splatoon2") else ("splatoon3", "mk8d", "splatoon2")
        keys = {"splatoon3": self.key, "mk8d": self.key_mk8d, "splatoon2": self.key_splatoon2}
        r, used = None, None
        for g in order:
            k = keys[g]
            if not k:
                continue
            tbl = getattr(self, "tables_mk8d", {}).get(direction, table) if g in ("mk8d", "splatoon2") else table
            hook = (lambda plain, ip=src[0]: self.learn_stations(ip, plain)) if direction == "h2s" else None
            hook_m = (lambda plain, ip=src[0], g=g: self.learn_stations_mk8d(ip, plain, g)) if direction == "h2s" else None
            rr = relay_game_mk8d(k, ip4(src[0]), my_ip, data, tbl, on_plain=hook_m) if g in ("mk8d", "splatoon2") else \
                relay_game(k, ip4(src[0]), my_ip, data, table, pairs, on_plain=hook)
            if rr is not None:
                r, used = rr, g
                break
        if r is None and self.host_side == "h" and len(self.sessions) > 1:
            if direction == "s2h":
                cands = sorted((ip for ip in self.sessions if not self.host or ip != self.host[0]),
                               key=lambda ip: max((t for (g2, i2), t in self.rooms.items() if i2 == ip), default=0),
                               reverse=True)
            elif src[0] in self.sessions and (not self.host or src[0] != self.host[0]):
                cands = [src[0]]
            else:
                cands = []
            for ip in cands:
                entry = self.sessions[ip]
                t2, p2, tm2 = entry["tables"]
                for g in order:
                    k2 = entry["keys"].get(g)
                    if not k2:
                        continue
                    tbl2 = tm2[direction] if g in ("mk8d", "splatoon2") else t2[direction]
                    rr = relay_game_mk8d(k2, ip4(src[0]), my_ip, data, tbl2) if g in ("mk8d", "splatoon2") else \
                        relay_game(k2, ip4(src[0]), my_ip, data, t2[direction], p2[direction])
                    if rr is not None:
                        r, used = rr, g
                        break
                if r is not None:
                    self.host = (ip, self.host[1] if self.host and self.host[0] == ip else GAME_PORT)
                    self.key = entry["keys"].get("splatoon3")
                    self.key_mk8d = entry["keys"].get("mk8d")
                    self.key_splatoon2 = entry["keys"].get("splatoon2")
                    self.tables, self.ip_pairs, self.tables_mk8d = t2, p2, tm2
                    table, pairs = t2[direction], p2[direction]
                    if direction == "s2h":
                        dest = self.session_peer()
                        udest = (dest[0], self.h_game_port or GAME_PORT)
                    print(f"session switched to host {ip}", flush=True)
                    break
        if r is None:
            if self.game in ("mk8d", "splatoon2") and len(data) >= 36 and data[:4] == MAGIC and data[4] == 1:
                if not pknown:
                    self.send(sock, data, bdest, direction, label="plain")
                self.send(sock, data, udest, direction, label="plain")
                if self.game != "splatoon2" and not (self.mk8d_state if self.game == "mk8d" else self.splatoon2_state):
                    self.send_extra_ports(sock, data, udest[0], udest[1])
                return
            self.note(ev="rx_unauth", dir=direction, src=src[0], len=len(data))
            return
        if direction == "h2s":
            cur = self.session_peer()
            if cur is None or cur[0] == src[0]:
                if self.host_side == "sw":
                    self.peer = src
                else:
                    self.host = src
            self.__dict__.setdefault("station_ports", {})[src[0]] = src[1]
        self.game = used
        out, label, changed = r
        nonce = data[12:20] if used in ("mk8d", "splatoon2") else data[13:21]
        self.note(ev="rx", dir=direction, src=src[0], len=len(data), label=label, nonce=nonce.hex())
        if self.cap:
            if direction == "s2h":
                self.cap.save("sw-game", data, src[0], self.h_ip, src[1], GAME_PORT)
            else:
                self.cap.save("hs-game", data, self.sw_ip, src[0], GAME_PORT, src[1])
        if used == "splatoon2" and direction == "h2s" and label == "09001000":
            plain = open_game_mk8d(keys[used], ip4(src[0]), data)
            ph = hashlib.sha256(plain).digest() if plain is not None else None
            fan = self.__dict__.setdefault("_s2_probe_fan", {})
            now = time.time()
            if ph is not None and now - fan.get(ph, 0.0) < 1.5:
                return
            if ph is not None:
                fan[ph] = now
            for p in GAME_PORTS:
                self.send(sock, out, (bdest[0], p), direction, label=label, nonce=nonce.hex(), changed=changed)
            return
        if used in ("mk8d", "splatoon2"):
            send_sock = sock
            dest_ip, dest_port = udest
            slotted = False
            if direction == "s2h":
                owner = self.owner_of_port(local_port) if local_port is not None else None
                peer = self.session_peer()
                if owner and (not peer or owner != peer[0]):
                    dest_ip = owner
                    dest_port = getattr(self, "station_ports", {}).get(owner, GAME_PORT)
                    slotted = True
            else:
                slot = self.slot_of(src[0])
                if slot:
                    send_sock = getattr(self, "sw_slot_socks", {}).get(slot, sock)
                    peer = self.session_peer()
                    if not peer or src[0] != peer[0]:
                        slotted = True
            if used == "splatoon2" and self.slot_of(src[0]) is None:
                claims = self.__dict__.setdefault("claim_ports", {})
                if label == "01005b00":
                    plain = open_game_mk8d(keys[used], ip4(src[0]), data)
                    if plain is not None and len(plain) >= 0x33 and plain[0x29:0x2d] == ip4(src[0]) and plain[0x2f:0x33] == ip4(src[0]):
                        claims[src[0]] = (plain[0x2d] << 8) | plain[0x2e]
                cport = claims.get(src[0])
                if cport:
                    socks = self.sw_slot_socks if direction == "h2s" else getattr(self, "h_slot_socks", {})
                    send_sock = socks.get(cport, sock)
            if not pknown and not slotted:
                self.send(send_sock, out, bdest, direction, label=label, nonce=nonce.hex(), changed=changed)
            self.send(send_sock, out, (dest_ip, dest_port), direction, label=label, nonce=nonce.hex(), changed=changed)
            if used != "splatoon2" and not slotted and not (self.mk8d_state if used == "mk8d" else self.splatoon2_state):
                self.send_extra_ports(sock, out, dest_ip, dest_port)
            if label == "01004600":
                if used == "mk8d":
                    self.mk8d_state = True
                else:
                    self.splatoon2_state = True
        else:
            if direction == "s2h":
                owner = self.owner_of_port(local_port) if local_port is not None else None
                peer = self.session_peer()
                if owner and (not peer or owner != peer[0]):
                    dest = (owner, getattr(self, "station_ports", {}).get(owner, GAME_PORT))
            else:
                slot = self.slot_of(src[0])
                sock = getattr(self, "sw_slot_socks", {}).get(slot, sock) if slot else sock
            self.send(sock, out, dest, direction, label=label, nonce=nonce.hex(), changed=changed)

    def on_game_sock(self, data, src, bound_leg, local_port=None):
        if src[0] in (self.sw_ip, self.h_ip):
            return
        leg = self.leg_of(src[0]) or bound_leg
        self.on_game(data, src, "s2h" if leg == "sw" else "h2s", local_port)

    def run_scan_only(self, out_path):
        self.load_stash()
        self.load_stash_mk8d()
        self.load_stash_splatoon2()
        self.load_stash_mkw()
        try:
            self._evf = open(out_path, "w", buffering=1)
        except Exception:
            self._evf = None
        self.note(ev="start", mode="scan", sw=self.sw_ip, h=self.h_ip)
        print("scan only: watching for rooms, not forwarding", flush=True)
        socks = {self.d_h: self.on_disc, self.d_h2: self.on_disc}
        stop = [False]
        signal.signal(signal.SIGINT, lambda *_: stop.__setitem__(0, True))
        signal.signal(signal.SIGTERM, lambda *_: stop.__setitem__(0, True))
        while not stop[0]:
            try:
                ready, _, _ = select.select(list(socks), [], [], 0.5)
            except InterruptedError:
                continue
            for s in ready:
                while True:
                    try:
                        data, src = s.recvfrom(65535)
                    except BlockingIOError:
                        break
                    except OSError:
                        break
                    try:
                        socks[s](data, src)
                    except Exception:
                        pass
            now = time.time()
            while self.queue and self.queue[0][0] <= now:
                _, _, sock, data, dest, direction, meta = heapq.heappop(self.queue)
                self._tx(sock, data, dest, direction, meta)
            if now - self.last_scan >= 15:
                self.last_scan = now
                for poll in (self.scan_poll, self.scan_poll_mk8d,
                             self.scan_poll_splatoon2, self.scan_poll_mkw):
                    try:
                        poll()
                    except Exception:
                        pass
        if getattr(self, "_evf", None) is not None:
            try:
                self._evf.close()
            except Exception:
                pass
            self._evf = None
        print("done: scan stopped", flush=True)

    def run(self, out_path):
        if getattr(self, "scan_only", False):
            return self.run_scan_only(out_path)
        self.load_stash()
        self.load_stash_mk8d()
        self.load_stash_splatoon2()
        self.load_stash_mkw()
        try:
            self._evf = open(out_path, "w", buffering=1)
        except Exception:
            self._evf = None
        self.note(ev="start", sw=self.sw_ip, h=self.h_ip,
                  host=(self.host[0] if self.host else None))
        socks = {self.d_sw: self.on_disc, self.d_h: self.on_disc,
                 self.d_sw2: self.on_disc, self.d_h2: self.on_disc,
                 self.g_sw: lambda d, s: self.on_game_sock(d, s, "sw", GAME_PORT),
                 self.g_h: lambda d, s: self.on_game_sock(d, s, "h"),
                 self.gb_sw: lambda d, s: self.on_game_sock(d, s, "sw"),
                 self.gb_h: lambda d, s: self.on_game_sock(d, s, "h")}
        for s, leg in self.gx:
            lp = s.getsockname()[1] if leg == "sw" and s.getsockname()[0] == self.sw_ip else None
            socks[s] = lambda d, src, leg=leg, lp=lp: self.on_game_sock(d, src, leg, lp)
        stop = [False]
        signal.signal(signal.SIGINT, lambda *_: stop.__setitem__(0, True))
        signal.signal(signal.SIGTERM, lambda *_: stop.__setitem__(0, True))
        last_stat = time.time()
        while not stop[0]:
            timeout = 0.5
            if self.queue:
                timeout = max(0.0, min(timeout, self.queue[0][0] - time.time()))
            try:
                ready, _, _ = select.select(list(socks), [], [], timeout)
            except InterruptedError:
                continue
            for s in ready:
                while True:
                    try:
                        data, src = s.recvfrom(65535)
                    except BlockingIOError:
                        break
                    except OSError as e:
                        if e.errno in (errno.EINTR,):
                            continue
                        break
                    socks[s](data, src)
            now = time.time()
            while self.queue and self.queue[0][0] <= now:
                _, _, sock, data, dest, direction, meta = heapq.heappop(self.queue)
                self._tx(sock, data, dest, direction, meta)
            if now - self.last_scan >= 15:
                self.last_scan = now
                try:
                    self.scan_poll()
                except Exception:
                    pass
                try:
                    self.scan_poll_mk8d()
                except Exception:
                    pass
                try:
                    self.scan_poll_splatoon2()
                except Exception:
                    pass
                try:
                    self.scan_poll_mkw()
                except Exception:
                    pass
            if now - last_stat > 5:
                last_stat = now
                n = {k: sum(1 for e in self.log if e.get("ev") == "tx" and e.get("dir") == k and not e.get("probe")) for k in ("s2h", "h2s")}
                sw_role = "host" if self.host_side == "sw" else "switch"
                h_role = "host" if self.host_side == "h" else "peer"
                print(f"forwarded: {sw_role} to {h_role} {n['s2h']}, {h_role} to {sw_role} {n['h2s']}", flush=True)
        if getattr(self, "_evf", None) is not None:
            try:
                self._evf.close()
            except Exception:
                pass
            self._evf = None
        else:
            with open(out_path, "w") as fh:
                for e in self.log:
                    fh.write(json.dumps(e) + "\n")
        if self.cap:
            self.cap.close()
        print(f"done: {len(self.log)} events saved to {out_path}", flush=True)

class Args:
    pass

def main():
    a = Args()
    a.switch_ip = os.environ.get("PHONE_IP") or None
    a.host_ip = os.environ.get("ZT_IP") or None
    a.switch_prefix = int(os.environ.get("SWITCH_PREFIX", "24"))
    a.host_prefix = int(os.environ.get("HOST_PREFIX", "24"))
    a.switch_peer = os.environ.get("SWITCH_IP") or None
    a.host_peer = os.environ.get("HOST_ZT") or None
    a.delay_s2h = float(os.environ.get("DELAY_S2H_MS", "0"))
    a.delay_h2s = float(os.environ.get("DELAY_H2S_MS", "0"))
    a.capture = os.environ.get("CAPTURE", "").strip() or None
    a.scan_only = os.environ.get("SCAN_ONLY", "").strip() not in ("", "0")
    if "--no-bit-rewrite" in sys.argv:
        global NO_BITS
        NO_BITS = True
        print("bit rewrite off", flush=True)
    events = os.environ.get("EVENTS", f"relay_events_{time.strftime('%Y%m%d_%H%M%S')}.jsonl")
    print("GRID0 proxy v31", flush=True)
    Relay(a).run(events)

if __name__ == "__main__":
    main()
