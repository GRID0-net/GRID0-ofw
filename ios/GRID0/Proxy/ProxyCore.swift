import Foundation

// Packet helpers ported from zttestproxy.py. Byte arrays are used throughout,
// matching the Python bytes objects one to one.

let DISCOVERY_PORT = 35000
let GAME_PORT = 49152
let GAME_PORTS = [49152, 49153, 49154, 49155]
let MK8D_PORT = 30000
let MK8D_KEY: [UInt8] = Array("ABCDEFGHIJKLMNOP".utf8)
let GAME_NAMES = ["splatoon3": "Splatoon 3", "mk8d": "Mario Kart 8 Deluxe"]
let CHAL_OFF = ["splatoon3": 27, "mk8d": 575]
let PARAM_OFF = ["splatoon3": 105, "mk8d": 1270]
let MAGIC: [UInt8] = [0x32, 0xAB, 0x98, 0x64]
let ZSTD_MAGIC: [UInt8] = [0x28, 0xB5, 0x2F, 0xFD]

func detectGame(_ data: [UInt8]) -> String? {
    if data.count == 325 && data[0] == 0 {
        return "splatoon3"
    }
    if data.count == 873 && data[0] == 0 && be32(data, 1) == 0x23A {
        return "mk8d"
    }
    return nil
}

func be32(_ d: [UInt8], _ off: Int) -> UInt32 {
    return UInt32(d[off]) << 24 | UInt32(d[off + 1]) << 16 | UInt32(d[off + 2]) << 8 | UInt32(d[off + 3])
}

func ip4(_ s: String) -> [UInt8] {
    let parts = s.split(separator: ".").map { UInt8($0) ?? 0 }
    return parts.count == 4 ? parts : [0, 0, 0, 0]
}

func ipString(_ b: [UInt8]) -> String {
    return b.map { String($0) }.joined(separator: ".")
}

func ipToUInt32(_ s: String) -> UInt32 {
    let b = ip4(s)
    return UInt32(b[0]) << 24 | UInt32(b[1]) << 16 | UInt32(b[2]) << 8 | UInt32(b[3])
}

func uint32ToIp(_ v: UInt32) -> String {
    return ipString([UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)])
}

func prefixMask(_ prefix: Int) -> UInt32 {
    if prefix <= 0 { return 0 }
    if prefix >= 32 { return 0xFFFF_FFFF }
    return UInt32(0xFFFF_FFFF) << UInt32(32 - prefix)
}

func bcastOf(_ ip: String, _ prefix: Int) -> [UInt8] {
    let v = ipToUInt32(ip) | ~prefixMask(prefix)
    return [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
}

func networkContains(_ ip: String, network: String, prefix: Int) -> Bool {
    let m = prefixMask(prefix)
    return ipToUInt32(ip) & m == ipToUInt32(network) & m
}

typealias BytePair = (o: [UInt8], n: [UInt8])

func addressPairs(_ pairs: [BytePair], port: Int = GAME_PORT) -> [BytePair] {
    var a = pairs
    let extra: [BytePair] = pairs.map { (o, n) in
        let po = Array(o[3...3]) + Array(o[2...2]) + [UInt8(port & 0xFF), UInt8((port >> 8) & 0xFF)]
        let pn = Array(n[3...3]) + Array(n[2...2]) + [UInt8(port & 0xFF), UInt8((port >> 8) & 0xFF)]
        return (po, pn)
    }
    a += extra
    a += a.map { (o, n) in (Array(o.reversed()), Array(n.reversed())) }
    return a.filter { $0.o != $0.n }
}

func replaceAll(_ body: [UInt8], _ needle: [UInt8], _ replacement: [UInt8]) -> [UInt8] {
    if needle.isEmpty || body.count < needle.count { return body }
    var out = body
    var i = 0
    while i + needle.count <= out.count {
        var match = true
        for j in 0..<needle.count where out[i + j] != needle[j] { match = false; break }
        if match {
            for j in 0..<needle.count { out[i + j] = replacement[j] }
            i += needle.count
        } else {
            i += 1
        }
    }
    return out
}

func rewrite(_ body: [UInt8], _ table: [BytePair]) -> [UInt8] {
    var out = body
    for p in table {
        out = replaceAll(out, p.o, p.n)
    }
    return out
}

func idFragments(_ pairs: [BytePair]) -> [BytePair] {
    var out: [BytePair] = []
    for (o, n) in pairs {
        for p in GAME_PORTS {
            out.append((Array(o[2..<4]) + [UInt8((p >> 8) & 0xFF), UInt8(p & 0xFF)],
                        Array(n[2..<4]) + [UInt8((p >> 8) & 0xFF), UInt8(p & 0xFF)]))
        }
    }
    return out.filter { $0.o != $0.n }
}

// Splatoon 3 game packets: 8-byte GCM tag at [21:29], nonce src_ip + [13:21].
func openGame(key: [UInt8], srcIp: [UInt8], d: [UInt8]) -> (plain: [UInt8], compressed: Bool)? {
    if d.count < 29 || Array(d[0..<4]) != MAGIC { return nil }
    let nonce = srcIp + Array(d[13..<21])
    guard let p0 = aesGCMDecrypt(key: key, nonce: nonce,
                                 ciphertext: Array(d[29...]), tag: Array(d[21..<29])) else {
        return nil
    }
    var p = p0
    let pad = Int(d[5] >> 4)
    if pad > 0 && pad <= p.count {
        p = Array(p[0..<(p.count - pad)])
    }
    return (p, p.count >= 4 && Array(p[0..<4]) == ZSTD_MAGIC)
}

func sealGame(key: [UInt8], myIp: [UInt8], hdr: [UInt8], body: [UInt8]) -> [UInt8] {
    let pad = (16 - body.count % 16) % 16
    var h = Array(hdr[0..<29])
    h[5] = UInt8(pad << 4) | (hdr[5] & 0x0F)
    let nonce = myIp + Array(h[13..<21])
    let padded = body + [UInt8](repeating: 0xFF, count: pad)
    let (ct, tag) = aesGCMEncrypt(key: key, nonce: nonce, plaintext: padded)
    for i in 0..<8 { h[21 + i] = tag[i] }
    return h + ct
}

// Mario Kart 8 Deluxe game packets: 16-byte tag at [20:36],
// nonce src_ip + [5:6] + [13:20].
func openGameMk8d(key: [UInt8], srcIp: [UInt8], d: [UInt8]) -> [UInt8]? {
    if d.count < 36 || Array(d[0..<4]) != MAGIC || d[4] != 2 { return nil }
    let nonce = srcIp + Array(d[5..<6]) + Array(d[13..<20])
    return aesGCMDecrypt(key: key, nonce: nonce, ciphertext: Array(d[36...]), tag: Array(d[20..<36]))
}

func sealGameMk8d(key: [UInt8], myIp: [UInt8], hdr: [UInt8], body: [UInt8]) -> [UInt8] {
    var h = Array(hdr[0..<36])
    let nonce = myIp + Array(h[5..<6]) + Array(h[13..<20])
    let (ct, tag) = aesGCMEncrypt(key: key, nonce: nonce, plaintext: body)
    for i in 0..<16 { h[20 + i] = tag[i] }
    return h + ct
}

func relayGameMk8d(key: [UInt8], srcIp: [UInt8], myIp: [UInt8], d: [UInt8],
                   table: [BytePair]) -> (out: [UInt8], label: String, changed: Bool)? {
    guard let plain = openGameMk8d(key: key, srcIp: srcIp, d: d) else { return nil }
    let new = rewrite(plain, table)
    return (sealGameMk8d(key: key, myIp: myIp, hdr: d, body: new),
            plain[0..<4].map { String(format: "%02x", $0) }.joined(),
            new != plain)
}

let FIELD_WIDTHS: [(bit: UInt8, width: Int)] = [(1, 1), (2, 2), (4, 1), (8, 1), (16, 1)]

func trimJoinResponse(_ plain: [UInt8]) -> [UInt8] {
    var pos = 0
    var f: [UInt8: Int] = [1: 0, 2: 0, 4: 0, 8: 0, 16: 0]
    var cut: Int? = nil
    var seen = false
    while pos < plain.count {
        let mask = plain[pos]
        if mask & ~31 != 0 { return plain }
        var q = pos + 1
        for (bit, w) in FIELD_WIDTHS where mask & bit != 0 {
            var v = 0
            for i in 0..<w { v = (v << 8) | Int(plain[q + i]) }
            f[bit] = v
            q += w
        }
        let end = q + (f[2] ?? 0)
        if end > plain.count { return plain }
        if f[4] == 13 {
            if f[2] == 198 { seen = true }
            cut = end
        }
        pos = end
    }
    if seen, let c = cut, c < plain.count {
        return Array(plain[0..<c])
    }
    return plain
}

func rewriteSome(_ plain: [UInt8], _ table: [BytePair], _ protos: Set<Int>) -> [UInt8] {
    var out = plain
    var pos = 0
    var f: [UInt8: Int] = [1: 0, 2: 0, 4: 0, 8: 0, 16: 0]
    while pos < plain.count {
        let mask = plain[pos]
        if mask & ~31 != 0 { break }
        var q = pos + 1
        for (bit, w) in FIELD_WIDTHS where mask & bit != 0 {
            var v = 0
            for i in 0..<w { v = (v << 8) | Int(plain[q + i]) }
            f[bit] = v
            q += w
        }
        let end = q + (f[2] ?? 0)
        if end > plain.count { break }
        if let proto = f[4], protos.contains(proto) {
            let seg = rewrite(Array(plain[q..<end]), table)
            out.replaceSubrange(q..<end, with: seg)
        }
        pos = end
    }
    return out
}

private func getBits(_ body: [UInt8], _ i: Int, _ count: Int) -> UInt32 {
    var v: UInt32 = 0
    for j in 0..<count {
        let bit = (body[(i + j) / 8] >> UInt8((i + j) % 8)) & 1
        v |= UInt32(bit) << UInt32(j)
    }
    return v
}

private func setBits(_ body: inout [UInt8], _ i: Int, _ count: Int, _ v: UInt32) {
    for j in 0..<count {
        let idx = (i + j) / 8
        let shift = UInt8((i + j) % 8)
        if (v >> UInt32(j)) & 1 == 1 {
            body[idx] |= (1 << shift)
        } else {
            body[idx] &= ~(1 << shift)
        }
    }
}

// The bit-packed address rewrite for Splatoon 3 Broadcast Reliable records:
// addresses sit big-endian inside an LSB-first bit stream, each preceded by a
// 16-bit copy of its last two octets.
func rewriteBits(_ body: [UInt8], _ ipPairs: [BytePair]) -> [UInt8] {
    if body.count < 4 || ipPairs.isEmpty { return body }
    let n = body.count * 8
    let pats: [(full: UInt32, newFull: UInt32, tail: UInt32, newTail: UInt32)] = ipPairs.map { (o, nw) in
        (UInt32(o[0]) | UInt32(o[1]) << 8 | UInt32(o[2]) << 16 | UInt32(o[3]) << 24,
         UInt32(nw[0]) | UInt32(nw[1]) << 8 | UInt32(nw[2]) << 16 | UInt32(nw[3]) << 24,
         UInt32(o[2]) | UInt32(o[3]) << 8,
         UInt32(nw[2]) | UInt32(nw[3]) << 8)
    }
    let first = Set(pats.map { $0.full })
    var out = body
    var hit = false
    var i = 0
    while i <= n - 32 {
        let w = getBits(out, i, 32)
        if first.contains(w) {
            for p in pats where w == p.full {
                setBits(&out, i, 32, p.newFull)
                if i >= 16 && getBits(out, i - 16, 16) == p.tail {
                    setBits(&out, i - 16, 16, p.newTail)
                }
                hit = true
                break
            }
            i += 32
        } else {
            i += 1
        }
    }
    return hit ? out : body
}

var trimJoinEnabled = false
var noBitsEnabled = false
var rewriteProtos: Set<Int>? = nil

func relayGame(key: [UInt8], srcIp: [UInt8], myIp: [UInt8], d: [UInt8],
               table: [BytePair], ipPairs: [BytePair]?) -> (out: [UInt8], label: String, changed: Bool)? {
    guard let o = openGame(key: key, srcIp: srcIp, d: d) else { return nil }
    let raw = o.plain
    var plain: [UInt8]
    if o.compressed {
        guard let dec = try? zstdDecompress(raw) else { return nil }
        plain = dec
    } else {
        plain = raw
    }
    var new: [UInt8]
    if let protos = rewriteProtos {
        new = rewriteSome(plain, table, protos)
    } else {
        new = rewrite(plain, table)
    }
    if let ipPairs = ipPairs, !noBitsEnabled {
        new = rewriteBits(new, ipPairs)
    }
    if trimJoinEnabled {
        new = trimJoinResponse(new)
    }
    let changed = new != plain
    let body = changed ? (o.compressed ? zstdCompress(new) : new) : raw
    return (sealGame(key: key, myIp: myIp, hdr: d, body: body),
            plain[0..<4].map { String(format: "%02x", $0) }.joined(),
            changed)
}

func sessionKey(gameKey: [UInt8], reply: [UInt8], off: Int = 105) -> [UInt8] {
    var param = Array(reply[off..<(off + 32)])
    param[31] = (param[31] &+ 1) & 0xFF
    return Array(hmacSHA256(key: gameKey, data: param)[0..<16])
}

func splatoonHostName(_ rep: [UInt8]) -> String? {
    if rep.count > 41 {
        let n = Int(rep[39])
        if n >= 1 && n <= 32 && rep[40] == 1 && 41 + n <= rep.count {
            if let name = String(bytes: Array(rep[41..<(41 + n)]), encoding: .utf8),
               !name.isEmpty, name.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
                return name
            }
        }
    }
    var chars: [UInt16] = []
    var i = 141
    while i + 1 < min(rep.count, 141 + 40) {
        let code = UInt16(rep[i]) | (UInt16(rep[i + 1]) << 8)
        if code == 0 { break }
        chars.append(code)
        i += 2
    }
    if !chars.isEmpty {
        let name = String(utf16CodeUnits: chars, count: chars.count)
        if name.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
            return name
        }
    }
    return nil
}

func mk8dHostName(_ rep: [UInt8]) -> String? {
    if rep.count > 80 {
        let raw = Array(rep[48..<80])
        if let end = raw.firstIndex(of: 0), end >= 1 {
            if let name = String(bytes: Array(raw[0..<end]), encoding: .utf8),
               !name.isEmpty, name.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
                return name
            }
        }
    }
    return nil
}

let SCAN_HEADER: [UInt8] = [0x00, 0x00, 0x00, 0x00, 0x00, 0x16, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A,
                            0x00, 0x04, 0x00, 0x06, 0x01, 0x00, 0xC2, 0x50,
                            0x0F, 0xC2, 0x00, 0x00]

func be64Bytes(_ v: UInt64) -> [UInt8] {
    return (0..<8).map { UInt8((v >> UInt64(56 - 8 * $0)) & 0xFF) }
}

func makeScanRequest(gameKey: [UInt8], bc: [UInt8], counter: UInt64) -> (req: [UInt8], chal: [UInt8]) {
    let chal = randomBytes(16)
    let k = aesEncryptBlock(key: gameKey, block: chal)
    let nonce = bc + be64Bytes(counter)
    let (ct, tag) = aesGCMEncrypt(key: k, nonce: nonce, plaintext: randomBytes(256))
    return (SCAN_HEADER + [0x03, 0x01] + be64Bytes(counter) + chal + tag + ct, chal)
}

func rekeyRequest(gameKey: [UInt8], req: [UInt8], bc: [UInt8], counter: UInt64) -> (req: [UInt8], chal: [UInt8])? {
    if req.count != 325 || req[0] != 0 { return nil }
    let cr = Array(req[27...])
    let kOld = aesEncryptBlock(key: gameKey, block: Array(cr[10..<26]))
    guard let pt = aesGCMDecrypt(key: kOld, nonce: bc + Array(cr[2..<10]),
                                 ciphertext: Array(cr[42...]), tag: Array(cr[26..<42])) else {
        return nil
    }
    let chal = randomBytes(16)
    let k = aesEncryptBlock(key: gameKey, block: chal)
    let (ct, tag) = aesGCMEncrypt(key: k, nonce: bc + be64Bytes(counter), plaintext: pt)
    return (Array(req[0..<29]) + be64Bytes(counter) + chal + tag + ct, chal)
}

func rekeyRequestMk8d(gameKey: [UInt8], req: [UInt8], bc: [UInt8], counter: UInt64) -> (req: [UInt8], chal: [UInt8])? {
    if req.count != 873 || req[0] != 0 { return nil }
    let cr = Array(req[575...])
    let kOld = aesEncryptBlock(key: gameKey, block: Array(cr[10..<26]))
    guard let pt = aesGCMDecrypt(key: kOld, nonce: bc + Array(cr[2..<10]),
                                 ciphertext: Array(cr[42...]), tag: Array(cr[26..<42])) else {
        return nil
    }
    let chal = randomBytes(16)
    let k = aesEncryptBlock(key: gameKey, block: chal)
    let (ct, tag) = aesGCMEncrypt(key: k, nonce: bc + be64Bytes(counter), plaintext: pt)
    return (Array(req[0..<577]) + be64Bytes(counter) + chal + tag + ct, chal)
}

func mk8dParamOff(_ rep: [UInt8]) -> Int {
    let size = Int(be32(rep, 1))
    if size >= 32 && size <= rep.count - 5 {
        return 5 + size - 32
    }
    return PARAM_OFF["mk8d"]!
}

// Throws-style helpers return nil on any crypto mismatch, mirroring the
// Python exceptions the callers catch.
func resealRequest(gameKey: [UInt8], req: [UInt8], oldBc: [UInt8], newBc: [UInt8],
                   chalOff: Int) -> (out: [UInt8], chal: [UInt8])? {
    if req.count != chalOff + 298 || req[0] != 0 { return nil }
    let cr = Array(req[chalOff...])
    let counter = Array(cr[2..<10])
    let chal = Array(cr[10..<26])
    let tag = Array(cr[26..<42])
    let ct = Array(cr[42...])
    let k = aesEncryptBlock(key: gameKey, block: chal)
    guard let pt = aesGCMDecrypt(key: k, nonce: oldBc + counter, ciphertext: ct, tag: tag) else {
        return nil
    }
    let (nct, ntag) = aesGCMEncrypt(key: k, nonce: newBc + counter, plaintext: pt)
    return (Array(req[0..<(chalOff + 26)]) + ntag + nct, chal)
}

func resealRequestPinned(gameKey: [UInt8], req: [UInt8], oldBc: [UInt8], newBc: [UInt8],
                         chalOff: Int, pin: [UInt8]) -> (out: [UInt8], chal: [UInt8])? {
    if req.count != chalOff + 298 || req[0] != 0 { return nil }
    let cr = Array(req[chalOff...])
    let counter = Array(cr[2..<10])
    let chal = Array(cr[10..<26])
    let tag = Array(cr[26..<42])
    let ct = Array(cr[42...])
    let k = aesEncryptBlock(key: gameKey, block: chal)
    guard let pt = aesGCMDecrypt(key: k, nonce: oldBc + counter, ciphertext: ct, tag: tag) else {
        return nil
    }
    let k2 = aesEncryptBlock(key: gameKey, block: pin)
    let (nct, ntag) = aesGCMEncrypt(key: k2, nonce: newBc + counter, plaintext: pt)
    var out = req
    out.replaceSubrange((chalOff + 10)..<(chalOff + 26), with: pin)
    out.replaceSubrange((chalOff + 26)..<(chalOff + 42), with: ntag)
    out.replaceSubrange((chalOff + 42)..<out.count, with: nct)
    return (out, chal)
}

private func resealReplyBody(gameKey: [UInt8], rep: [UInt8], chalIn: [UInt8], chalOut: [UInt8],
                             oldBc: [UInt8], newBc: [UInt8], oldHost: [UInt8], newHost: [UInt8],
                             paramOff: Int, frag: Bool) -> [UInt8]? {
    if rep.isEmpty || rep[0] != 1 { return nil }
    let off = 5 + Int(be32(rep, 1))
    if off + 58 > rep.count { return nil }
    let cr = Array(rep[off..<(off + 58)])
    let counter = Array(cr[2..<10])
    let rchal = Array(cr[10..<26])
    let tag = Array(cr[26..<42])
    let ct = Array(cr[42..<58])
    let kIn = Array(hmacSHA256(key: gameKey, data: rchal + chalIn)[0..<16])
    guard let pt = aesGCMDecrypt(key: kIn, nonce: oldBc + counter, ciphertext: ct, tag: tag) else {
        return nil
    }
    let kOut = Array(hmacSHA256(key: gameKey, data: rchal + chalOut)[0..<16])
    let (nct, ntag) = aesGCMEncrypt(key: kOut, nonce: newBc + counter, plaintext: pt)
    var out = rep
    var pats: [BytePair] = [(oldHost, newHost), (Array(oldHost.reversed()), Array(newHost.reversed()))]
    if frag {
        pats += idFragments([(oldHost, newHost)])
    }
    for p in pats {
        var i = 0
        while i + p.o.count <= out.count {
            var match = true
            for j in 0..<p.o.count where out[i + j] != p.o[j] { match = false; break }
            if match {
                if !(paramOff <= i && i < paramOff + 32) && !(off <= i && i < off + 58) {
                    for j in 0..<p.o.count { out[i + j] = p.n[j] }
                }
                i += p.o.count
            } else {
                i += 1
            }
        }
    }
    out.replaceSubrange((off + 26)..<(off + 42), with: ntag)
    out.replaceSubrange((off + 42)..<(off + 58), with: nct)
    return out
}

func resealReply(gameKey: [UInt8], rep: [UInt8], reqChal: [UInt8], oldBc: [UInt8], newBc: [UInt8],
                 oldHost: [UInt8], newHost: [UInt8], paramOff: Int = 105, frag: Bool = false) -> [UInt8]? {
    return resealReplyBody(gameKey: gameKey, rep: rep, chalIn: reqChal, chalOut: reqChal,
                           oldBc: oldBc, newBc: newBc, oldHost: oldHost, newHost: newHost,
                           paramOff: paramOff, frag: frag)
}

func resealReplyRekey(gameKey: [UInt8], rep: [UInt8], chalIn: [UInt8], chalOut: [UInt8],
                      oldBc: [UInt8], newBc: [UInt8], oldHost: [UInt8], newHost: [UInt8],
                      paramOff: Int = 105, frag: Bool = false) -> [UInt8]? {
    return resealReplyBody(gameKey: gameKey, rep: rep, chalIn: chalIn, chalOut: chalOut,
                           oldBc: oldBc, newBc: newBc, oldHost: oldHost, newHost: newHost,
                           paramOff: paramOff, frag: frag)
}
