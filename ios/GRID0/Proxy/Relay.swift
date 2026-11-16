import Foundation
import Darwin

// The relay itself, ported from zttestproxy.py's Relay class. Runs on its own
// thread, moving discovery and game traffic between the Switch's Wi-Fi leg and
// the ZeroTier leg exactly like the Python proxy does.

enum JVal {
    case s(String)
    case i(Int)
    case b(Bool)
    case d(Double)
    case null
}

func jsonEscape(_ s: String) -> String {
    var out = "\""
    for scalar in s.unicodeScalars {
        switch scalar {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\t": out += "\\t"
        default:
            if scalar.value < 0x20 || scalar.value > 0x7E {
                if scalar.value > 0xFFFF {
                    let v = scalar.value - 0x10000
                    out += String(format: "\\u%04x\\u%04x", 0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF))
                } else {
                    out += String(format: "\\u%04x", scalar.value)
                }
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    out += "\""
    return out
}

func jsonEvent(_ pairs: [(String, JVal)]) -> String {
    let parts = pairs.map { (k, v) -> String in
        switch v {
        case .s(let s): return "\"\(k)\": \(jsonEscape(s))"
        case .i(let n): return "\"\(k)\": \(n)"
        case .b(let b): return "\"\(k)\": \(b ? "true" : "false")"
        case .d(let x): return "\"\(k)\": \(String(format: "%.6f", x))"
        case .null: return "\"\(k)\": null"
        }
    }
    return "{" + parts.joined(separator: ", ") + "}"
}

struct ProxyConfig {
    var switchIp: String
    var hostIp: String
    var switchPrefix: Int = 24
    var hostPrefix: Int = 24
    var switchPeer: String? = nil
    var hostPeer: String? = nil
    var delayS2hMs: Double = 0
    var delayH2sMs: Double = 0
    var captureBase: String? = nil
    var eventsPath: String
    var filesDir: String
    var gameKey: [UInt8]
    var gameKeySplatoon2: [UInt8]? = nil
    var log: (String) -> Void = { print($0) }
}

final class UDPSock {
    let fd: Int32
    let port: Int
    let boundIp: String
    init?(ip: String, port: Int, broadcast: Bool, log: (String) -> Void) {
        let f = socket(AF_INET, SOCK_DGRAM, 0)
        if f < 0 { return nil }
        var yes: Int32 = 1
        setsockopt(f, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(f, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        if broadcast {
            setsockopt(f, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        }
        func tryBind(_ bindIp: String) -> Bool {
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = in_port_t(UInt16(port)).bigEndian
            addr.sin_addr.s_addr = inet_addr(bindIp)
            let r = withUnsafePointer(to: &addr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    bind(f, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            return r == 0
        }
        var bound = ip
        if !tryBind(ip) {
            if ip == "0.0.0.0" {
                Darwin.close(f)
                return nil
            }
            log("warning: cannot bind \(ip):\(port), using all interfaces")
            bound = "0.0.0.0"
            if !tryBind("0.0.0.0") {
                log("error: cannot bind 0.0.0.0:\(port) (error \(errno))")
                Darwin.close(f)
                return nil
            }
        }
        let flags = fcntl(f, F_GETFL, 0)
        _ = fcntl(f, F_SETFL, flags | O_NONBLOCK)
        fd = f
        self.port = port
        boundIp = bound
    }

    @discardableResult
    func send(_ data: [UInt8], to ip: String, port: Int) -> Int32 {
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(port)).bigEndian
        addr.sin_addr.s_addr = inet_addr(ip)
        let r = data.withUnsafeBufferPointer { buf in
            withUnsafePointer(to: &addr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sendto(fd, buf.baseAddress, buf.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        return r >= 0 ? 0 : errno
    }

    func recv() -> (data: [UInt8], ip: String, port: Int)? {
        var buf = [UInt8](repeating: 0, count: 65535)
        var addr = sockaddr_in()
        var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = withUnsafeMutablePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                recvfrom(fd, &buf, buf.count, 0, sa, &addrLen)
            }
        }
        if n <= 0 { return nil }
        let ip = String(cString: inet_ntoa(addr.sin_addr))
        let port = Int(UInt16(bigEndian: addr.sin_port))
        return (Array(buf[0..<n]), ip, port)
    }

    func close() {
        Darwin.close(fd)
    }
}

final class Capture {
    private var bin: FileHandle?
    private var txt: FileHandle?
    private(set) var n = 0

    init(base: String) {
        FileManager.default.createFile(atPath: base + ".bin", contents: nil)
        FileManager.default.createFile(atPath: base + ".txt", contents: nil)
        bin = FileHandle(forWritingAtPath: base + ".bin")
        txt = FileHandle(forWritingAtPath: base + ".txt")
    }

    func save(kind: String, data: [UInt8], src: String, dst: String, sport: Int, dport: Int) {
        if kind != "disc" && kind != "sw-game" && kind != "hs-game" { return }
        let recSrc = kind != "hs-game" ? src : dst
        let recDst = kind != "hs-game" ? dst : src
        let line = [kind, "x", "x", recSrc, recDst, "x", "x", "x", "x",
                    String(sport), String(dport), String(n)].joined(separator: "\t") + "\n"
        txt?.write(Data(line.utf8))
        bin?.write(Data(data))
        n += 1
    }

    func close() {
        try? bin?.close()
        try? txt?.close()
    }
}

struct SessionTables {
    var s2h: [BytePair]
    var h2s: [BytePair]
    var ipS2h: [BitPair]
    var ipH2s: [BitPair]
    var mkS2h: [BytePair]
    var mkH2s: [BytePair]
}

struct SessionEntry {
    var keys: [String: [UInt8]] = [:]
    var tables: SessionTables? = nil
}

typealias Addr = (ip: String, port: Int)

final class Relay {
    let cfg: ProxyConfig
    let gameKey: [UInt8]
    let gameKeyMk8d: [UInt8] = MK8D_KEY
    var gameKeySplatoon2: [UInt8] = []
    let swIp: String
    let hIp: String
    let swPrefix: Int
    let hPrefix: Int
    let swMe: [UInt8]
    let hMe: [UInt8]
    let swBc: [UInt8]
    let hBc: [UInt8]
    let delay: [String: Double]
    let onlySwitch: String?

    var switchAddr: Addr?
    var hostAddr: Addr?
    var peerAddr: Addr?
    var rooms: [String: Double] = [:]   // "game|ip" -> time
    var sessions: [String: SessionEntry] = [:]
    var reqChals: [[UInt8]] = []
    var hostReqChals: [[UInt8]] = []
    var mk8dReqChals: [[UInt8]] = []
    var mk8dHostReqChals: [[UInt8]] = []
    var splatoon2ReqChals: [[UInt8]] = []
    var splatoon2HostReqChals: [[UInt8]] = []
    let pinS2h: [UInt8] = randomBytes(16)
    let pinH2s: [UInt8] = randomBytes(16)
    var hostSide: String? = nil
    var lastSwReq: [UInt8]? = nil
    var lastSweep = 0.0
    var scanChals: [[UInt8]] = []
    var scanCounter: UInt64 = 0
    var lastScan = 0.0
    var stashReq: [UInt8]? = nil
    var stashMk8d: [UInt8]? = nil
    var scanChalsMk8d: [[UInt8]] = []
    var stashSplatoon2: [UInt8]? = nil
    var scanChalsSplatoon2: [[UInt8]] = []
    var roomNoted: [String: Double] = [:]
    var roomNames: [String: String] = [:]
    var roomCounts: [String: Int] = [:]
    var key: [UInt8]? = nil
    var keyMk8d: [UInt8]? = nil
    var keySplatoon2: [UInt8]? = nil
    var mk8dState = false
    var splatoon2State = false
    var game: String? = nil
    var lastSwGame = "splatoon3"
    var tables: SessionTables? = nil
    var swGamePort: Int? = nil
    var hGamePort: Int? = nil
    var seen: [String: Double] = [:]
    let t0 = Date().timeIntervalSince1970
    var txCounts: [String: Int] = ["s2h": 0, "h2s": 0]
    private var txWarn = 0
    private var discWarn = 0

    let dSw: UDPSock
    let dH: UDPSock
    let dSw2: UDPSock
    let dH2: UDPSock
    let gSw: UDPSock
    let gH: UDPSock
    let gbSw: UDPSock
    let gbH: UDPSock
    var gx: [(UDPSock, String)] = []
    var swSlotSocks: [Int: UDPSock] = [:]
    var slots: [String: Int] = [:]          // remote station IP -> local port
    var stationPorts: [String: Int] = [:]   // remote station IP -> its game port
    var cap: Capture? = nil

    private let stopLock = NSLock()
    private var _stage = "starting"
    private var _beat = Date().timeIntervalSince1970
    // Watchdog breadcrumb: what the relay thread is doing and when it last moved.
    func mark(_ stage: String) {
        stopLock.lock()
        _stage = stage
        _beat = Date().timeIntervalSince1970
        stopLock.unlock()
    }
    var heartbeat: (stage: String, beat: Double) {
        stopLock.lock()
        defer { stopLock.unlock() }
        return (_stage, _beat)
    }
    private var _stop = false
    var stopRequested: Bool {
        stopLock.lock()
        defer { stopLock.unlock() }
        return _stop
    }
    func requestStop() {
        stopLock.lock()
        _stop = true
        stopLock.unlock()
    }

    var queue: [(due: Double, seq: Int, sock: UDPSock, data: [UInt8], ip: String, port: Int,
                 direction: String, meta: [(String, JVal)])] = []
    var queueSeq = 0
    var eventsHandle: FileHandle? = nil

    init?(cfg: ProxyConfig) {
        self.cfg = cfg
        gameKey = cfg.gameKey
        gameKeySplatoon2 = cfg.gameKeySplatoon2 ?? []
        swIp = cfg.switchIp
        hIp = cfg.hostIp
        swPrefix = cfg.switchPrefix
        hPrefix = cfg.hostPrefix
        swMe = ip4(swIp)
        hMe = ip4(hIp)
        swBc = bcastOf(swIp, swPrefix)
        hBc = bcastOf(hIp, hPrefix)
        delay = ["s2h": cfg.delayS2hMs / 1000, "h2s": cfg.delayH2sMs / 1000]
        onlySwitch = cfg.switchPeer
        if let hp = cfg.hostPeer {
            hostAddr = (hp, GAME_PORT)
        }
        cfg.log("switch leg: \(swIp) (broadcast \(ipString(swBc)))")
        cfg.log("peer leg: \(hIp) (broadcast \(ipString(hBc)))")
        if let h = hostAddr {
            cfg.log("peer: \(h.ip)")
        }
        cfg.log("ready")
        let log = cfg.log
        var opened: [UDPSock] = []
        func open(_ ip: String, _ port: Int) -> UDPSock? {
            guard let s = UDPSock(ip: ip, port: port, broadcast: true, log: log) else {
                cfg.log("error: cannot open \(ip):\(port)")
                return nil
            }
            opened.append(s)
            return s
        }
        guard let a = open("0.0.0.0", DISCOVERY_PORT),
              let b = open(hIp, DISCOVERY_PORT),
              let c = open("0.0.0.0", MK8D_PORT),
              let d = open(hIp, MK8D_PORT),
              let e = open(swIp, GAME_PORT),
              let f = open(hIp, GAME_PORT),
              let g = open(ipString(swBc), GAME_PORT),
              let h = open(ipString(hBc), GAME_PORT) else {
            opened.forEach { $0.close() }
            return nil
        }
        dSw = a; dH = b; dSw2 = c; dH2 = d; gSw = e; gH = f; gbSw = g; gbH = h
        for p in GAME_PORTS.dropFirst() {
            guard let x1 = open(swIp, p), let x2 = open(ipString(swBc), p),
                  let x3 = open(hIp, p), let x4 = open(ipString(hBc), p) else {
                opened.forEach { $0.close() }
                return nil
            }
            gx.append((x1, "sw"))
            gx.append((x2, "sw"))
            gx.append((x3, "h"))
            gx.append((x4, "h"))
        }
        swSlotSocks[GAME_PORT] = gSw
        for (s, leg) in gx where leg == "sw" && s.boundIp == swIp {
            swSlotSocks[s.port] = s
        }
        for p in SLOT_PORTS where swSlotSocks[p] == nil {
            if let s = UDPSock(ip: swIp, port: p, broadcast: true, log: log) {
                swSlotSocks[p] = s
                gx.append((s, "sw"))
            }
        }
        if let base = cfg.captureBase {
            cap = Capture(base: base)
        }
    }

    func now() -> Double {
        return Date().timeIntervalSince1970
    }

    func note(_ pairs: [(String, JVal)]) {
        var fields = pairs
        fields.append(("t", .d(now() - t0)))
        if let h = eventsHandle {
            h.write(Data((jsonEvent(fields) + "\n").utf8))
        }
    }

    func sessionPeer() -> Addr? {
        if hostSide == "sw" {
            return peerAddr ?? hostAddr
        }
        return hostAddr ?? peerAddr
    }

    func tablesFor(_ peerIp: String) -> SessionTables {
        let sw = ip4(switchAddr!.ip)
        let h = ip4(peerIp)
        let s2h = addressPairs([(swMe, h), (sw, hMe)])
        let h2s = addressPairs([(h, swMe), (hMe, sw)])
        let ipS2h = bitPairs([(swMe, h), (sw, hMe)].filter { $0.o != $0.n })
        let ipH2s = bitPairs([(h, swMe), (hMe, sw)].filter { $0.o != $0.n })
        return SessionTables(
            s2h: s2h, h2s: h2s, ipS2h: ipS2h, ipH2s: ipH2s,
            mkS2h: s2h + idFragments([(swMe, h), (sw, hMe)]),
            mkH2s: h2s + idFragments([(h, swMe), (hMe, sw)]))
    }

    func slotOf(_ ip: String) -> Int? {
        return slots[ip]
    }

    func ownerOfPort(_ port: Int) -> String? {
        return slots.first { $0.value == port }?.key
    }

    @discardableResult
    func addStation(_ ip: String) -> Bool {
        if slots[ip] != nil || ip == hIp { return false }
        let used = Set(slots.values)
        guard let free = SLOT_PORTS.first(where: { !used.contains($0) && swSlotSocks[$0] != nil }) else {
            return false
        }
        slots[ip] = free
        cfg.log("station: \(ip) mapped to \(swIp):\(free)")
        note([("ev", .s("station")), ("ip", .s(ip)), ("port", .i(free))])
        return true
    }

    func rebuildSlotTables() {
        guard let peer = sessionPeer(), let sw = switchAddr, var t = tables else { return }
        let list = slots.map { (ip4($0.key), $0.value) }
        let st = slotTables(list, hostIp: ip4(peer.ip), sw: ip4(sw.ip), swMe: swMe, hMe: hMe)
        t.s2h = st.s2h
        t.h2s = st.h2s
        t.ipS2h = st.ipS2h
        t.ipH2s = st.ipH2s
        tables = t
    }

    func learnStations(_ srcIp: String, _ plain: [UInt8]) -> ([BytePair], [BitPair])? {
        var changed = addStation(srcIp)
        let netLow = ipToUInt32(hIp) & prefixMask(hPrefix)
        let netHigh = netLow | ~prefixMask(hPrefix)
        for raw in findStations(plain, Array(hMe[0..<3])) {
            let v = ipToUInt32(ipString(raw))
            if v == netLow || v == netHigh { continue }
            if addStation(ipString(raw)) { changed = true }
        }
        if changed {
            rebuildSlotTables()
            if let t = tables { return (t.h2s, t.ipH2s) }
        }
        return nil
    }

    func buildTables() {
        guard let peer = sessionPeer(), switchAddr != nil else { return }
        tables = tablesFor(peer.ip)
        let others = slots.filter { $0.key != peer.ip && $0.value != GAME_PORT }
        slots = others
        slots[peer.ip] = GAME_PORT
        rebuildSlotTables()
        if hostSide == "sw" {
            cfg.log("paired: Switch \(switchAddr!.ip) is hosting, peer \(peer.ip) joined")
        } else if hostSide == "h" {
            cfg.log("paired: Switch \(switchAddr!.ip) with host \(peer.ip)")
        } else {
            cfg.log("paired: Switch \(switchAddr!.ip) with peer \(peer.ip)")
        }
    }

    func sendExtraPorts(_ sock: UDPSock, _ data: [UInt8], _ destIp: String, skipPort: Int) {
        for p in GAME_PORTS where p != skipPort {
            _ = sock.send(data, to: destIp, port: p)
        }
    }

    func send(_ sock: UDPSock, _ data: [UInt8], _ ip: String, _ port: Int,
              _ direction: String, meta: [(String, JVal)] = []) {
        let due = now() + (delay[direction] ?? 0)
        if due <= now() {
            tx(sock, data, ip, port, direction, meta)
        } else {
            queueSeq += 1
            queue.append((due, queueSeq, sock, data, ip, port, direction, meta))
            queue.sort { $0.due < $1.due }
        }
    }

    func tx(_ sock: UDPSock, _ data: [UInt8], _ ip: String, _ port: Int,
            _ direction: String, _ meta: [(String, JVal)]) {
        let err = sock.send(data, to: ip, port: port)
        if err == 0 {
            var pairs: [(String, JVal)] = [("ev", .s("tx")), ("dir", .s(direction)),
                                           ("dst", .s(ip)), ("len", .i(data.count))]
            pairs += meta
            note(pairs)
            let isProbe = meta.contains { $0.0 == "probe" }
            if !isProbe && (direction == "s2h" || direction == "h2s") {
                txCounts[direction, default: 0] += 1
            }
        } else {
            txWarn += 1
            if txWarn == 1 || txWarn % 50 == 0 {
                cfg.log("warning: send to \(ip):\(port) failed (error \(err))")
            }
            var pairs: [(String, JVal)] = [("ev", .s("txerr")), ("dir", .s(direction)), ("err", .i(Int(err)))]
            pairs += meta
            note(pairs)
        }
    }

    func dup(_ data: [UInt8], _ srcIp: String) -> Bool {
        let t = now()
        seen = seen.filter { t - $0.value < 1.5 }
        let k = srcIp + "|" + sha256(data).map { String(format: "%02x", $0) }.joined()
        if seen[k] != nil { return true }
        seen[k] = t
        return false
    }

    func legOf(_ srcIp: String) -> String? {
        if networkContains(srcIp, network: hIp, prefix: hPrefix) { return "h" }
        if networkContains(srcIp, network: swIp, prefix: swPrefix) { return "sw" }
        return nil
    }

    func discNote(_ msg: String) {
        discWarn += 1
        if discWarn == 1 || discWarn % 50 == 0 {
            cfg.log(msg)
        }
    }

    func keyFor(_ game: String) -> [UInt8] {
        if game == "splatoon3" { return gameKey }
        if game == "splatoon2" { return gameKeySplatoon2 }
        return gameKeyMk8d
    }

    func chalsFor(_ direction: String, _ game: String) -> [[UInt8]] {
        if direction == "s2h" {
            if game == "splatoon3" { return reqChals }
            if game == "splatoon2" { return splatoon2ReqChals }
            return mk8dReqChals
        }
        if game == "splatoon3" { return hostReqChals }
        if game == "splatoon2" { return splatoon2HostReqChals }
        return mk8dHostReqChals
    }

    func addChal(_ direction: String, _ game: String, _ chal: [UInt8]) {
        var lst = chalsFor(direction, game)
        lst = [chal] + lst.filter { $0 != chal }
        lst = Array(lst.prefix(8))
        if direction == "s2h" {
            if game == "splatoon3" { reqChals = lst }
            else if game == "splatoon2" { splatoon2ReqChals = lst }
            else { mk8dReqChals = lst }
        } else {
            if game == "splatoon3" { hostReqChals = lst }
            else if game == "splatoon2" { splatoon2HostReqChals = lst }
            else { mk8dHostReqChals = lst }
        }
    }

    func noteGame(_ g: String) {
        if g != game {
            game = g
            cfg.log("game: \(GAME_NAMES[g] ?? g)")
        }
    }

    func portFor(_ game: String) -> Int {
        return (game == "mk8d" || game == "splatoon2") ? MK8D_PORT : DISCOVERY_PORT
    }

    func sockFor(_ leg: String, _ game: String) -> UDPSock {
        if leg == "h" {
            return (game == "mk8d" || game == "splatoon2") ? dH2 : dH
        }
        return (game == "mk8d" || game == "splatoon2") ? dSw2 : dSw
    }

    func takeSessionKey(_ game: String, _ data: [UInt8]) {
        let off = (game == "mk8d" || game == "splatoon2") ? mk8dParamOff(data) : PARAM_OFF[game]!
        let k = sessionKey(gameKey: keyFor(game), reply: data, off: off)
        if game == "mk8d" {
            if k != keyMk8d {
                keyMk8d = k
                mk8dState = false
                cfg.log("session started")
            }
        } else if game == "splatoon2" {
            if k != keySplatoon2 {
                keySplatoon2 = k
                splatoon2State = false
                cfg.log("session started")
            }
        } else if k != key {
            key = k
            cfg.log("session started")
        }
    }
}
