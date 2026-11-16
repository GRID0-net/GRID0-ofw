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
    var log: (String) -> Void = { print($0) }
}

final class UDPSock {
    let fd: Int32
    init?(ip: String, port: Int, broadcast: Bool, log: (String) -> Void) {
        let f = socket(AF_INET, SOCK_DGRAM, 0)
        if f < 0 { return nil }
        var yes: Int32 = 1
        setsockopt(f, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
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
        if !tryBind(ip) {
            if ip == "0.0.0.0" {
                Darwin.close(f)
                return nil
            }
            log("warning: cannot bind \(ip):\(port), using all interfaces")
            if !tryBind("0.0.0.0") {
                Darwin.close(f)
                return nil
            }
        }
        let flags = fcntl(f, F_GETFL, 0)
        _ = fcntl(f, F_SETFL, flags | O_NONBLOCK)
        fd = f
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
    var ipS2h: [BytePair]
    var ipH2s: [BytePair]
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
    var roomNoted: [String: Double] = [:]
    var roomNames: [String: String] = [:]
    var key: [UInt8]? = nil
    var keyMk8d: [UInt8]? = nil
    var mk8dState = false
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
    var cap: Capture? = nil

    private let stopLock = NSLock()
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

    init(cfg: ProxyConfig) {
        self.cfg = cfg
        gameKey = cfg.gameKey
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
        dSw = UDPSock(ip: "0.0.0.0", port: DISCOVERY_PORT, broadcast: true, log: log)!
        dH = UDPSock(ip: hIp, port: DISCOVERY_PORT, broadcast: true, log: log)!
        dSw2 = UDPSock(ip: "0.0.0.0", port: MK8D_PORT, broadcast: true, log: log)!
        dH2 = UDPSock(ip: hIp, port: MK8D_PORT, broadcast: true, log: log)!
        gSw = UDPSock(ip: swIp, port: GAME_PORT, broadcast: true, log: log)!
        gH = UDPSock(ip: hIp, port: GAME_PORT, broadcast: true, log: log)!
        gbSw = UDPSock(ip: ipString(swBc), port: GAME_PORT, broadcast: true, log: log)!
        gbH = UDPSock(ip: ipString(hBc), port: GAME_PORT, broadcast: true, log: log)!
        for p in GAME_PORTS.dropFirst() {
            gx.append((UDPSock(ip: swIp, port: p, broadcast: true, log: log)!, "sw"))
            gx.append((UDPSock(ip: ipString(swBc), port: p, broadcast: true, log: log)!, "sw"))
            gx.append((UDPSock(ip: hIp, port: p, broadcast: true, log: log)!, "h"))
            gx.append((UDPSock(ip: ipString(hBc), port: p, broadcast: true, log: log)!, "h"))
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
        let ipS2h: [BytePair] = [(swMe, h), (sw, hMe)].filter { $0.o != $0.n }
        let ipH2s: [BytePair] = [(h, swMe), (hMe, sw)].filter { $0.o != $0.n }
        return SessionTables(
            s2h: s2h, h2s: h2s, ipS2h: ipS2h, ipH2s: ipH2s,
            mkS2h: s2h + idFragments([(swMe, h), (sw, hMe)]),
            mkH2s: h2s + idFragments([(h, swMe), (hMe, sw)]))
    }

    func buildTables() {
        guard let peer = sessionPeer(), switchAddr != nil else { return }
        tables = tablesFor(peer.ip)
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
        return game == "splatoon3" ? gameKey : gameKeyMk8d
    }

    func chalsFor(_ direction: String, _ game: String) -> [[UInt8]] {
        if direction == "s2h" {
            return game == "splatoon3" ? reqChals : mk8dReqChals
        }
        return game == "splatoon3" ? hostReqChals : mk8dHostReqChals
    }

    func addChal(_ direction: String, _ game: String, _ chal: [UInt8]) {
        var lst = chalsFor(direction, game)
        lst = [chal] + lst.filter { $0 != chal }
        lst = Array(lst.prefix(8))
        if direction == "s2h" {
            if game == "splatoon3" { reqChals = lst } else { mk8dReqChals = lst }
        } else {
            if game == "splatoon3" { hostReqChals = lst } else { mk8dHostReqChals = lst }
        }
    }

    func noteGame(_ g: String) {
        if g != game {
            game = g
            cfg.log("game: \(GAME_NAMES[g] ?? g)")
        }
    }

    func portFor(_ game: String) -> Int {
        return game == "mk8d" ? MK8D_PORT : DISCOVERY_PORT
    }

    func sockFor(_ leg: String, _ game: String) -> UDPSock {
        if leg == "h" {
            return game == "mk8d" ? dH2 : dH
        }
        return game == "mk8d" ? dSw2 : dSw
    }

    func takeSessionKey(_ game: String, _ data: [UInt8]) {
        let off = game == "mk8d" ? mk8dParamOff(data) : PARAM_OFF[game]!
        let k = sessionKey(gameKey: keyFor(game), reply: data, off: off)
        if game == "mk8d" {
            if k != keyMk8d {
                keyMk8d = k
                mk8dState = false
                cfg.log("session started")
            }
        } else if k != key {
            key = k
            cfg.log("session started")
        }
    }
}
