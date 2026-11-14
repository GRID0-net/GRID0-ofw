#!/usr/bin/env python3
# GRID0 proxy v228
# Relays Switch LAN play over ZeroTier through this device.
import errno, hashlib, heapq, hmac, ipaddress, json, os, select, signal, socket, sys, time
from Crypto.Cipher import AES
import zstandard as zstd

DISCOVERY_PORT, GAME_PORT = 35000, 49152
MAGIC = bytes.fromhex("32ab9864")
ZSTD_MAGIC = bytes.fromhex("28b52ffd")

def fetch_game_key():
    import re, urllib.request
    try:
        with urllib.request.urlopen("https://raw.githubusercontent.com/kinnay/NintendoClientsWiki/master/Pia-Game-Keys.md", timeout=10) as resp:
            text = resp.read().decode("utf-8", "replace")
    except Exception:
        return None
    for line in text.splitlines():
        if "Splatoon 3" in line:
            m = re.search(r"[0-9a-fA-F]{32}", line)
            if m:
                print("fetched the Splatoon 3 game key from NintendoClientsWiki", flush=True)
                return bytes.fromhex(m.group(0))
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

def rewrite(body, table):
    for o, n in table:
        body = body.replace(o, n)
    return body

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

_CCTX = zstd.ZstdCompressor(level=3, write_content_size=True, write_checksum=False)

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

def rewrite_bits(body, ip_pairs):
    if len(body) < 4 or not ip_pairs:
        return body
    n = len(body) * 8
    x = int.from_bytes(body, "little")
    pats = [(int.from_bytes(o, "little"), int.from_bytes(nw, "little"),
             int.from_bytes(o[2:4], "little"), int.from_bytes(nw[2:4], "little")) for o, nw in ip_pairs]
    first = {p[0] for p in pats}
    mask, m16, i, hit = 0xFFFFFFFF, 0xFFFF, 0, False
    while i <= n - 32:
        w = (x >> i) & mask
        if w in first:
            for p, q, p16, q16 in pats:
                if w == p:
                    x = (x & ~(mask << i)) | (q << i)
                    if i >= 16 and (x >> (i - 16)) & m16 == p16:
                        x = (x & ~(m16 << (i - 16))) | (q16 << (i - 16))
                    hit = True
                    break
            i += 32
        else:
            i += 1
    return x.to_bytes(len(body), "little") if hit else body

def relay_game(key, src_ip, my_ip, d, table, ip_pairs=None):
    o = open_game(key, src_ip, d)
    if o is None:
        return None
    raw, compressed = o
    plain = zstd.ZstdDecompressor().decompress(raw, max_output_size=1 << 20) if compressed else raw
    new = rewrite(plain, table) if REWRITE_PROTOS is None else rewrite_some(plain, table, REWRITE_PROTOS)
    if ip_pairs and not NO_BITS:
        new = rewrite_bits(new, ip_pairs)
    if TRIM_JOIN:
        new = trim_join_response(new)
    changed = new != plain
    body = (_CCTX.compress(new) if compressed else new) if changed else raw
    return seal_game(key, my_ip, d, body), plain[:4].hex(), changed

def session_key(game_key, reply):
    param = bytearray(reply[105:137])
    param[-1] = (param[-1] + 1) & 0xFF
    return hmac.new(game_key, bytes(param), hashlib.sha256).digest()[:16]

def reseal_request(game_key, req, old_bc, new_bc):
    if len(req) != 325 or req[0] != 0:
        raise ValueError("not a browse request")
    cr = req[27:]
    counter, chal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:]
    k = AES.new(game_key, AES.MODE_ECB).encrypt(chal)
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    nct, ntag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    return req[:53] + ntag + nct, chal

def reseal_reply(game_key, rep, req_chal, old_bc, new_bc, old_host, new_host):
    if rep[0] != 1:
        raise ValueError("not a browse reply")
    off = 5 + int.from_bytes(rep[1:5], "big")
    cr = rep[off:off + 58]
    counter, rchal, tag, ct = cr[2:10], cr[10:26], cr[26:42], cr[42:58]
    k = hmac.new(game_key, rchal + req_chal, hashlib.sha256).digest()[:16]
    pt = AES.new(k, AES.MODE_GCM, nonce=old_bc + counter).decrypt_and_verify(ct, tag)
    nct, ntag = AES.new(k, AES.MODE_GCM, nonce=new_bc + counter).encrypt_and_digest(pt)
    out = bytearray(rep)
    for o, n in ((old_host, new_host), (old_host[::-1], new_host[::-1])):
        i = 0
        while (i := bytes(out).find(o, i)) >= 0:
            if not (105 <= i < 137 or off <= i < off + 58):
                out[i:i + 4] = n
            i += 4
    out[off + 26:off + 42] = ntag
    out[off + 42:off + 58] = nct
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
        self.game_key = GAME_KEY
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
        self.req_chals = []
        self.host_req_chals = []
        self.host_side = None
        self.last_sw_req = None
        self.last_sweep = 0.0
        self.key = None
        self.tables = None
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
        self.g_sw = udp_bind(self.sw_ip, GAME_PORT)
        self.g_h = udp_bind(self.h_ip, GAME_PORT, bind_devices=zt_cands)
        self.cap = Capture(a.capture) if a.capture else None

    def note(self, **kw):
        kw["t"] = round(time.time() - self.t0, 6)
        self.log.append(kw)

    def build_tables(self):
        sw, h = ip4(self.switch[0]), ip4(self.host[0])
        self.tables = {
            "s2h": address_pairs([(self.sw_me, h), (sw, self.h_me)]),
            "h2s": address_pairs([(h, self.sw_me), (self.h_me, sw)]),
        }
        self.ip_pairs = {
            "s2h": [(o, n) for o, n in [(self.sw_me, h), (sw, self.h_me)] if o != n],
            "h2s": [(o, n) for o, n in [(h, self.sw_me), (self.h_me, sw)] if o != n],
        }
        if self.host_side == "sw":
            print(f"paired: Switch {self.switch[0]} is hosting, peer {self.host[0]} joined", flush=True)
        elif self.host_side == "h":
            print(f"paired: Switch {self.switch[0]} with host {self.host[0]}", flush=True)
        else:
            print(f"paired: Switch {self.switch[0]} with peer {self.host[0]}", flush=True)

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
        if len(data) != 325 or data[0] != 0:
            return
        try:
            out, chal = reseal_request(self.game_key, data, self.sw_bc, self.h_bc)
        except Exception as e:
            self.disc_note(f"note: a browse request from {src} could not be opened")
            return
        if self.switch and self.switch[0] != src[0]:
            print(f"console changed: {self.switch[0]} to {src[0]}", flush=True)
            self.switch = (src[0], GAME_PORT)
            if self.host:
                self.build_tables()
        self.switch = (src[0], self.switch[1] if self.switch else GAME_PORT)
        self.req_chals = ([chal] + [c for c in self.req_chals if c != chal])[:8]
        self.note(ev="rx", dir="s2h", kind="browse_req", src=src[0], len=len(data))
        if self.cap:
            self.cap.save("disc", data, src[0], socket.inet_ntoa(self.sw_bc), src[1], DISCOVERY_PORT)
        self.last_sw_req = out
        self.send(self.d_h, out, (socket.inet_ntoa(self.h_bc), DISCOVERY_PORT), "s2h", kind="browse_req")
        if self.host:
            self.send(self.d_h, out, (self.host[0], DISCOVERY_PORT), "s2h", kind="browse_req_uni")
        elif time.time() - self.last_sweep >= 5:
            self.last_sweep = time.time()
            count = min(self.h_net.num_addresses, 256)
            base = int(self.h_net.network_address)
            for i in range(count):
                ip = str(ipaddress.IPv4Address(base + i))
                if ip != self.h_ip and ip != socket.inet_ntoa(self.h_bc):
                    self.send(self.d_h, out, (ip, DISCOVERY_PORT), "s2h", kind="browse_req_uni", probe=1)

    def on_disc_host(self, data, src):
        if len(data) == 325 and data[0] == 0:
            return self.on_request_from_host(data, src)
        if len(data) >= 64 and data[0] == 1 and self.host is None:
            self.host = (src[0], GAME_PORT)
            if self.last_sw_req:
                self.send(self.d_h, self.last_sw_req, (src[0], DISCOVERY_PORT), "s2h", kind="browse_req_uni")
        if len(data) < 64 or data[0] != 1 or not self.switch:
            return
        out = None
        for chal in self.req_chals:
            try:
                out = reseal_reply(self.game_key, data, chal, self.h_bc, self.sw_bc, ip4(src[0]), self.sw_me)
                break
            except ValueError:
                continue
        if out is None:
            self.disc_note(f"note: a browse reply from {src} matched no recent request")
            return
        k = session_key(self.game_key, data)
        if k != self.key:
            self.key = k
            print("session started", flush=True)
        self.host_side = "h"
        if self.host is None or self.host[0] != src[0]:
            self.host = (src[0], GAME_PORT)
            self.build_tables()
        elif self.tables is None:
            self.build_tables()
        self.note(ev="rx", dir="h2s", kind="browse_reply", src=src[0], len=len(data))
        if self.cap:
            self.cap.save("disc", data, src[0], self.switch[0], src[1], DISCOVERY_PORT)
        self.send(self.d_sw, out, (self.switch[0], DISCOVERY_PORT), "h2s", kind="browse_reply")

    def on_request_from_host(self, data, src):
        try:
            out, chal = reseal_request(self.game_key, data, self.h_bc, self.sw_bc)
        except Exception as e:
            self.disc_note(f"note: a browse request from peer {src} could not be opened")
            return
        self.host = (src[0], self.host[1] if self.host else GAME_PORT)
        self.host_req_chals = ([chal] + [c for c in self.host_req_chals if c != chal])[:8]
        self.note(ev="rx", dir="h2s", kind="browse_req", src=src[0], len=len(data))
        if self.cap:
            self.cap.save("disc", data, src[0], socket.inet_ntoa(self.h_bc), src[1], DISCOVERY_PORT)
        self.send(self.d_sw, out, (socket.inet_ntoa(self.sw_bc), DISCOVERY_PORT), "h2s", kind="browse_req")
        if self.switch:
            self.send(self.d_sw, out, (self.switch[0], DISCOVERY_PORT), "h2s", kind="browse_req_uni")

    def on_reply_from_switch(self, data, src):
        if not self.host:
            return
        out = None
        for chal in self.host_req_chals:
            try:
                out = reseal_reply(self.game_key, data, chal, self.sw_bc, self.h_bc, ip4(src[0]), self.h_me)
                break
            except ValueError:
                continue
        if out is None:
            self.disc_note(f"note: a Switch reply from {src} matched no recent peer request")
            return
        k = session_key(self.game_key, data)
        if k != self.key:
            self.key = k
            print("session started", flush=True)
        self.host_side = "sw"
        if self.switch is None or self.switch[0] != src[0]:
            self.switch = (src[0], GAME_PORT)
            self.build_tables()
        elif self.tables is None:
            self.build_tables()
        self.note(ev="rx", dir="s2h", kind="browse_reply", src=src[0], len=len(data))
        if self.cap:
            self.cap.save("disc", data, src[0], self.host[0], src[1], DISCOVERY_PORT)
        self.send(self.d_h, out, (self.host[0], DISCOVERY_PORT), "s2h", kind="browse_reply")

    def on_game(self, data, src, direction):
        if not self.key or not self.tables:
            self.note(ev="drop_nokey", dir=direction, src=src[0], len=len(data))
            return
        if direction == "s2h":
            if self.switch is None or self.switch[0] != src[0]:
                if self.only_switch and src[0] != self.only_switch:
                    return
                self.switch = src
                if self.host:
                    self.build_tables()
            self.switch = src
            r = relay_game(self.key, ip4(src[0]), self.h_me, data, self.tables["s2h"], self.ip_pairs["s2h"])
            sock, dest = self.g_h, self.host
        else:
            self.host = src
            r = relay_game(self.key, ip4(src[0]), self.sw_me, data, self.tables["h2s"], self.ip_pairs["h2s"])
            sock, dest = self.g_sw, self.switch
        if r is None:
            self.note(ev="rx_unauth", dir=direction, src=src[0], len=len(data))
            return
        out, label, changed = r
        self.note(ev="rx", dir=direction, src=src[0], len=len(data), label=label, nonce=data[13:21].hex())
        if self.cap:
            if direction == "s2h":
                self.cap.save("sw-game", data, src[0], self.h_ip, src[1], GAME_PORT)
            else:
                self.cap.save("hs-game", data, self.sw_ip, src[0], GAME_PORT, src[1])
        self.send(sock, out, dest, direction, label=label, nonce=data[13:21].hex(), changed=changed)

    def on_game_sock(self, data, src, bound_leg):
        if src[0] in (self.sw_ip, self.h_ip):
            return
        leg = self.leg_of(src[0]) or bound_leg
        self.on_game(data, src, "s2h" if leg == "sw" else "h2s")

    def run(self, out_path):
        self.note(ev="start", sw=self.sw_ip, h=self.h_ip,
                  host=(self.host[0] if self.host else None))
        socks = {self.d_sw: self.on_disc, self.d_h: self.on_disc,
                 self.g_sw: lambda d, s: self.on_game_sock(d, s, "sw"),
                 self.g_h: lambda d, s: self.on_game_sock(d, s, "h")}
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
            if now - last_stat > 5:
                last_stat = now
                n = {k: sum(1 for e in self.log if e.get("ev") == "tx" and e.get("dir") == k and not e.get("probe")) for k in ("s2h", "h2s")}
                sw_role = "host" if self.host_side == "sw" else "switch"
                h_role = "host" if self.host_side == "h" else "peer"
                print(f"forwarded: {sw_role} to {h_role} {n['s2h']}, {h_role} to {sw_role} {n['h2s']}", flush=True)
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
    if "--no-bit-rewrite" in sys.argv:
        global NO_BITS
        NO_BITS = True
        print("bit rewrite off", flush=True)
    events = os.environ.get("EVENTS", f"relay_events_{time.strftime('%Y%m%d_%H%M%S')}.jsonl")
    print("GRID0 proxy v228", flush=True)
    Relay(a).run(events)

if __name__ == "__main__":
    main()
