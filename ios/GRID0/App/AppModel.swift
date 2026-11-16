import Foundation
import UIKit

// App state and proxy control, mirroring MainActivity's behaviour: interface
// detection, the three steps, the events file driving the rooms list and the
// Switch detection, and the console log file the proxy writes.

struct RoomEntry: Identifiable {
    var id: String { game + "|" + host }
    let game: String
    let host: String
    let name: String?
    var display: String { name ?? host }
}

final class AppModel: ObservableObject {
    static let networkId = "8bd5124fd68185ec"
    static let appStoreZeroTier = "https://apps.apple.com/app/zerotier-one/id1241585323"

    @Published var currentTab = 0
    @Published var ztInstalled = false
    @Published var ztIp: String? = nil
    @Published var ztPrefix = 24
    @Published var phoneIp: String? = nil
    @Published var phonePrefix = 24
    @Published var proxyRunning = false
    @Published var switchIp: String? = nil
    @Published var switchPillVisible = false
    @Published var logText = ""
    @Published var rooms: [RoomEntry] = []
    @Published var tutorialOn: Bool {
        didSet { UserDefaults.standard.set(tutorialOn, forKey: "tutorial_on") }
    }
    @Published var showRooms = false
    @Published var toast: String? = nil

    private var relay: Relay? = nil
    private var relayThread: Thread? = nil
    private var logHandle: FileHandle? = nil
    private var pillDeadline: Date? = nil
    private var timers: [Timer] = []

    var filesDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("GRID0", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    var eventsPath: String { filesDir.appendingPathComponent("proxy_events.jsonl").path }
    var logPath: String { filesDir.appendingPathComponent("proxy_log.txt").path }
    var captureBase: String { filesDir.appendingPathComponent("proxy_capture").path }

    var grid0Connected: Bool {
        guard let ip = ztIp else { return false }
        return networkContains(ip, network: "10.147.17.0", prefix: 24)
    }

    init() {
        if UserDefaults.standard.object(forKey: "tutorial_default_v2") == nil {
            UserDefaults.standard.set(true, forKey: "tutorial_on")
            UserDefaults.standard.set(true, forKey: "tutorial_default_v2")
        }
        tutorialOn = UserDefaults.standard.bool(forKey: "tutorial_on")
        refreshInterfaces()
        startTimers()
    }

    func startTimers() {
        let fast = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.refreshFromFiles()
        }
        let slow = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: true) { [weak self] _ in
            self?.refreshInterfaces()
        }
        timers = [fast, slow]
    }

    // MARK: - Interface detection (getifaddrs, the iOS form of `ip addr`)

    func refreshInterfaces() {
        var addrs: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&addrs) == 0, let first = addrs else { return }
        defer { freeifaddrs(addrs) }
        var wifi: (ip: String, prefix: Int)? = nil
        var ztCands: [(ip: String, prefix: Int)] = []
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            let ifa = cur.pointee
            ptr = ifa.ifa_next
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            var ipBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(sa, socklen_t(sa.pointee.sa_len), &ipBuf, socklen_t(ipBuf.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: ipBuf)
            var prefix = 24
            if let nm = ifa.ifa_netmask {
                let mask = nm.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                prefix = prefixFromMask(mask)
            }
            if name == "en0" {
                wifi = (ip, prefix)
            } else if name.hasPrefix("utun") && ip != "0.0.0.0" {
                ztCands.append((ip, prefix))
            }
        }
        phoneIp = wifi?.ip
        phonePrefix = wifi?.prefix ?? 24
        if let grid0 = ztCands.first(where: { networkContains($0.ip, network: "10.147.17.0", prefix: 24) }) {
            ztIp = grid0.ip
            ztPrefix = grid0.prefix
        } else if let any = ztCands.first {
            ztIp = any.ip
            ztPrefix = any.prefix
        } else {
            ztIp = nil
        }
        ztInstalled = ztIp != nil || canOpenZeroTier()
    }

    private func prefixFromMask(_ maskNetOrder: UInt32) -> Int {
        let host = UInt32(bigEndian: maskNetOrder)
        return host.nonzeroBitCount
    }

    func canOpenZeroTier() -> Bool {
        if let url = URL(string: "zerotier://"), UIApplication.shared.canOpenURL(url) {
            return true
        }
        if let url = URL(string: "com.zerotier.one://"), UIApplication.shared.canOpenURL(url) {
            return true
        }
        return false
    }

    func openZeroTier() {
        for scheme in ["zerotier://", "com.zerotier.one://"] {
            if let url = URL(string: scheme), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url)
                return
            }
        }
        if let url = URL(string: AppModel.appStoreZeroTier) {
            UIApplication.shared.open(url)
        }
    }

    // MARK: - Proxy control

    func toggleProxy() {
        if proxyRunning {
            stopProxy()
        } else {
            startProxy()
        }
    }

    func startProxy() {
        guard let phone = phoneIp, let zt = ztIp else { return }
        guard let key = loadGameKey() else {
            appendLogLine("game key not found: no game_key.txt bundled or in files")
            return
        }
        FileManager.default.createFile(atPath: logPath, contents: nil)
        logHandle = FileHandle(forWritingAtPath: logPath)
        let logPathCopy = logPath
        let cfg = ProxyConfig(
            switchIp: phone,
            hostIp: zt,
            switchPrefix: phonePrefix,
            hostPrefix: ztPrefix,
            captureBase: captureBase,
            eventsPath: eventsPath,
            filesDir: filesDir.path,
            gameKey: key,
            log: { [weak self] line in
                self?.appendLogLine(line)
                _ = logPathCopy
            })
        let relay = Relay(cfg: cfg)
        self.relay = relay
        let thread = Thread {
            relay.run()
            DispatchQueue.main.async { [weak self] in
                self?.proxyRunning = false
                self?.relay = nil
                UIApplication.shared.isIdleTimerDisabled = false
            }
        }
        thread.stackSize = 1 << 20
        relayThread = thread
        proxyRunning = true
        UIApplication.shared.isIdleTimerDisabled = true
        thread.start()
    }

    func stopProxy() {
        relay?.requestStop()
    }

    private func loadGameKey() -> [UInt8]? {
        let local = filesDir.appendingPathComponent("game_key.txt")
        if let hex = try? String(contentsOf: local, encoding: .utf8) {
            let key = hexToBytes(hex.trimmingCharacters(in: .whitespacesAndNewlines))
            if key.count == 16 { return key }
        }
        if let url = Bundle.main.url(forResource: "game_key", withExtension: "txt"),
           let hex = try? String(contentsOf: url, encoding: .utf8) {
            let key = hexToBytes(hex.trimmingCharacters(in: .whitespacesAndNewlines))
            if key.count == 16 { return key }
        }
        return nil
    }

    private func hexToBytes(_ hex: String) -> [UInt8] {
        var out: [UInt8] = []
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex
            if let b = UInt8(hex[idx..<next], radix: 16) {
                out.append(b)
            }
            idx = next
        }
        return out
    }

    func appendLogLine(_ line: String) {
        logHandle?.write(Data((line + "\n").utf8))
    }

    // MARK: - Files refresh: log text, rooms, Switch detection

    func refreshFromFiles() {
        if let text = try? String(contentsOfFile: logPath, encoding: .utf8) {
            let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
            let tail = lines.suffix(200).joined(separator: "\n")
            if tail != logText {
                logText = tail
            }
        }
        parseEvents()
        if let deadline = pillDeadline, Date() > deadline {
            switchPillVisible = false
            pillDeadline = nil
        }
    }

    private func parseEvents() {
        guard let text = try? String(contentsOfFile: eventsPath, encoding: .utf8) else { return }
        var latest: [String: RoomEntry] = [:]
        var lastSwitch: String? = nil
        let cutoff = Date().timeIntervalSince1970 - 90
        for line in text.split(separator: "\n") {
            guard let obj = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else {
                continue
            }
            if obj["ev"] as? String == "room",
               let game = obj["game"] as? String,
               let host = obj["host"] as? String,
               let ts = (obj["ts"] as? NSNumber)?.doubleValue,
               ts >= cutoff {
                latest[game + "|" + host] = RoomEntry(game: game, host: host, name: obj["name"] as? String)
            }
            if obj["kind"] as? String == "browse_req", obj["dir"] as? String == "s2h",
               let src = obj["src"] as? String {
                lastSwitch = src
            }
        }
        let newRooms = latest.values.sorted { $0.game + $0.host < $1.game + $1.host }
        if newRooms.map({ $0.id + ($0.name ?? "") }) != rooms.map({ $0.id + ($0.name ?? "") }) {
            rooms = newRooms
        }
        if let sw = lastSwitch {
            if switchIp != sw {
                switchIp = sw
            }
            if !switchPillVisible {
                switchPillVisible = true
                pillDeadline = Date().addingTimeInterval(5)
            }
        }
    }

    func roomsFor(_ game: String) -> [RoomEntry] {
        return rooms.filter { $0.game == game }
    }

    // MARK: - Sharing

    func shareCapture() -> URL? {
        var items: [(name: String, url: URL)] = []
        let names = ["proxy_capture.bin", "proxy_capture.txt", "proxy_events.jsonl", "proxy_log.txt"]
        for n in names {
            let u = filesDir.appendingPathComponent(n)
            if FileManager.default.fileExists(atPath: u.path) {
                items.append((n, u))
            }
        }
        if items.isEmpty { return nil }
        let zipUrl = filesDir.appendingPathComponent("capture.zip")
        if writeZip(entries: items, to: zipUrl) {
            return zipUrl
        }
        return nil
    }

    // Minimal stored (no compression) ZIP writer, enough for the share sheet.
    private func writeZip(entries: [(name: String, url: URL)], to dest: URL) -> Bool {
        var out = Data()
        var central = Data()
        for (name, url) in entries {
            guard let data = try? Data(contentsOf: url) else { continue }
            let nameData = Data(name.utf8)
            let crc = crc32(data)
            let offset = UInt32(out.count)
            func u16(_ v: UInt16) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
            func u32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
            out += u32(0x04034B50)
            out += u16(20)
            out += u16(0)
            out += u16(0)
            out += u16(0)
            out += u16(0)
            out += u32(crc)
            out += u32(UInt32(data.count))
            out += u32(UInt32(data.count))
            out += u16(UInt16(nameData.count))
            out += u16(0)
            out += nameData
            out += data
            central += u32(0x02014B50)
            central += u16(20)
            central += u16(20)
            central += u16(0)
            central += u16(0)
            central += u16(0)
            central += u16(0)
            central += u32(crc)
            central += u32(UInt32(data.count))
            central += u32(UInt32(data.count))
            central += u16(UInt16(nameData.count))
            central += u16(0)
            central += u16(0)
            central += u16(0)
            central += u16(0)
            central += u32(0)
            central += u32(offset)
            central += nameData
        }
        func u16(_ v: UInt16) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
        func u32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
        let cdStart = UInt32(out.count)
        out += central
        out += u32(0x06054B50)
        out += u16(0)
        out += u16(0)
        out += u16(UInt16(entries.count))
        out += u16(UInt16(entries.count))
        out += u32(UInt32(central.count))
        out += u32(cdStart)
        out += u16(0)
        do {
            try out.write(to: dest)
            return true
        } catch {
            return false
        }
    }

    private func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for b in data {
            crc ^= UInt32(b)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1
            }
        }
        return crc ^ 0xFFFF_FFFF
    }

    func showToast(_ msg: String) {
        toast = msg
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            if self?.toast == msg {
                self?.toast = nil
            }
        }
    }
}
