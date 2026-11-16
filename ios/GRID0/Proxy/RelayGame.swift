import Foundation
import Darwin

// Game traffic forwarding and the run loop, ported from zttestproxy.py.

extension Relay {

    func onGame(_ data: [UInt8], _ src: Addr, _ direction: String, _ localPort: Int? = nil) {
        guard let tbl = tables, key != nil || keyMk8d != nil else {
            note([("ev", .s("drop_nokey")), ("dir", .s(direction)),
                  ("src", .s(src.ip)), ("len", .i(data.count))])
            return
        }
        if game == "mk8d" {
            var peer: String? = nil
            if direction == "s2h" {
                peer = hostAddr?.ip
            } else {
                peer = src.ip
            }
            if let p = peer {
                touchRoom(p)
            }
        }
        if game == "mk8d" && dup(data, src.ip) {
            return
        }
        var table: [BytePair]
        var pairs: [BytePair]
        var myIp: [UInt8]
        var sock: UDPSock
        var dest: Addr?
        var bdest: (ip: String, port: Int)
        if direction == "s2h" {
            if switchAddr == nil || switchAddr!.ip != src.ip {
                if let only = onlySwitch, src.ip != only { return }
                switchAddr = src
                if sessionPeer() != nil {
                    buildTables()
                }
            }
            guard let t = tables else { return }
            table = t.s2h
            pairs = t.ipS2h
            myIp = hMe
            sock = gH
            dest = sessionPeer()
            bdest = (ipString(hBc), GAME_PORT)
        } else {
            guard let t = tables else { return }
            table = t.h2s
            pairs = t.ipH2s
            myIp = swMe
            sock = gSw
            dest = switchAddr
            bdest = (ipString(swBc), GAME_PORT)
        }
        var udest: Addr
        let pknown: Bool
        if direction == "s2h" {
            if GAME_PORTS.contains(src.port) {
                swGamePort = src.port
            }
            udest = (dest!.ip, hGamePort ?? GAME_PORT)
            pknown = hGamePort != nil
        } else {
            if GAME_PORTS.contains(src.port) {
                hGamePort = src.port
            }
            udest = (dest!.ip, swGamePort ?? GAME_PORT)
            pknown = swGamePort != nil
        }
        let order = game == "mk8d" ? ["mk8d", "splatoon3"] : ["splatoon3", "mk8d"]
        var r: (out: [UInt8], label: String, changed: Bool)? = nil
        var used: String? = nil
        for g in order {
            let k = g == "mk8d" ? keyMk8d : key
            if k == nil { continue }
            let rr: (out: [UInt8], label: String, changed: Bool)?
            if g == "mk8d" {
                let t2 = tables!
                rr = relayGameMk8d(key: k!, srcIp: ip4(src.ip), myIp: myIp, d: data,
                                   table: direction == "s2h" ? t2.mkS2h : t2.mkH2s)
            } else {
                let srcIpStr = src.ip
                let hook: (([UInt8]) -> ([BytePair], [BytePair])?)? = direction == "h2s"
                    ? { [unowned self] plain in self.learnStations(srcIpStr, plain) } : nil
                rr = relayGame(key: k!, srcIp: ip4(src.ip), myIp: myIp, d: data,
                               table: table, ipPairs: pairs, onPlain: hook)
            }
            if rr != nil {
                r = rr
                used = g
                break
            }
        }
        if r == nil && hostSide == "h" && sessions.count > 1 {
            var cands: [String] = []
            if direction == "s2h" {
                cands = sessions.keys.filter { ip in
                    hostAddr == nil || ip != hostAddr!.ip
                }.sorted { a, b in
                    let ta = rooms.filter { $0.key.hasSuffix("|" + a) }.values.max() ?? 0
                    let tb = rooms.filter { $0.key.hasSuffix("|" + b) }.values.max() ?? 0
                    return ta > tb
                }
            } else if sessions[src.ip] != nil && (hostAddr == nil || src.ip != hostAddr!.ip) {
                cands = [src.ip]
            }
            for ip in cands {
                guard let entry = sessions[ip], let et = entry.tables else { continue }
                for g in order {
                    guard let k2 = entry.keys[g] else { continue }
                    let rr: (out: [UInt8], label: String, changed: Bool)?
                    if g == "mk8d" {
                        rr = relayGameMk8d(key: k2, srcIp: ip4(src.ip), myIp: myIp, d: data,
                                           table: direction == "s2h" ? et.mkS2h : et.mkH2s)
                    } else {
                        rr = relayGame(key: k2, srcIp: ip4(src.ip), myIp: myIp, d: data,
                                       table: direction == "s2h" ? et.s2h : et.h2s,
                                       ipPairs: direction == "s2h" ? et.ipS2h : et.ipH2s)
                    }
                    if rr != nil {
                        r = rr
                        used = g
                        break
                    }
                }
                if r != nil {
                    hostAddr = (ip, (hostAddr != nil && hostAddr!.ip == ip) ? hostAddr!.port : GAME_PORT)
                    key = entry.keys["splatoon3"]
                    keyMk8d = entry.keys["mk8d"]
                    tables = et
                    table = direction == "s2h" ? et.s2h : et.h2s
                    pairs = direction == "s2h" ? et.ipS2h : et.ipH2s
                    if direction == "s2h" {
                        dest = sessionPeer()
                        udest = (dest!.ip, hGamePort ?? GAME_PORT)
                    }
                    cfg.log("session switched to host \(ip)")
                    break
                }
            }
        }
        guard let result = r, let usedGame = used else {
            if game == "mk8d" && data.count >= 36 && Array(data[0..<4]) == MAGIC && data[4] == 1 {
                if !pknown {
                    send(sock, data, bdest.ip, bdest.port, direction, meta: [("label", .s("plain"))])
                }
                send(sock, data, udest.ip, udest.port, direction, meta: [("label", .s("plain"))])
                if !mk8dState {
                    sendExtraPorts(sock, data, udest.ip, skipPort: udest.port)
                }
                return
            }
            note([("ev", .s("rx_unauth")), ("dir", .s(direction)),
                  ("src", .s(src.ip)), ("len", .i(data.count))])
            return
        }
        _ = tbl
        if direction == "h2s" {
            let cur = sessionPeer()
            if cur == nil || cur!.ip == src.ip {
                if hostSide == "sw" {
                    peerAddr = src
                } else {
                    hostAddr = src
                }
            }
            stationPorts[src.ip] = src.port
        }
        game = usedGame
        let out = result.out
        let label = result.label
        let changed = result.changed
        let nonceBytes = usedGame == "mk8d" ? Array(data[12..<20]) : Array(data[13..<21])
        let nonce = nonceBytes.map { String(format: "%02x", $0) }.joined()
        note([("ev", .s("rx")), ("dir", .s(direction)), ("src", .s(src.ip)),
              ("len", .i(data.count)), ("label", .s(label)), ("nonce", .s(nonce))])
        if direction == "s2h" {
            cap?.save(kind: "sw-game", data: data, src: src.ip, dst: hIp, sport: src.port, dport: GAME_PORT)
        } else {
            cap?.save(kind: "hs-game", data: data, src: swIp, dst: src.ip, sport: GAME_PORT, dport: src.port)
        }
        let meta: [(String, JVal)] = [("label", .s(label)), ("nonce", .s(nonce)), ("changed", .b(changed))]
        if usedGame == "mk8d" {
            if !pknown {
                send(sock, out, bdest.ip, bdest.port, direction, meta: meta)
            }
            send(sock, out, udest.ip, udest.port, direction, meta: meta)
            if !mk8dState {
                sendExtraPorts(sock, out, udest.ip, skipPort: udest.port)
            }
            if label == "01004600" {
                mk8dState = true
            }
        } else {
            var to = dest!
            var via = sock
            if direction == "s2h" {
                if let lp = localPort, let owner = ownerOfPort(lp), owner != sessionPeer()?.ip {
                    to = (owner, stationPorts[owner] ?? GAME_PORT)
                }
            } else if let slot = slotOf(src.ip), let s = swSlotSocks[slot] {
                via = s
            }
            send(via, out, to.ip, to.port, direction, meta: meta)
        }
    }

    func onGameSock(_ data: [UInt8], _ src: Addr, _ boundLeg: String, _ localPort: Int? = nil) {
        if src.ip == swIp || src.ip == hIp { return }
        let leg = legOf(src.ip) ?? boundLeg
        onGame(data, src, leg == "sw" ? "s2h" : "h2s", localPort)
    }

    func run() {
        loadStash()
        loadStashMk8d()
        FileManager.default.createFile(atPath: cfg.eventsPath, contents: nil)
        eventsHandle = FileHandle(forWritingAtPath: cfg.eventsPath)
        note([("ev", .s("start")), ("sw", .s(swIp)), ("h", .s(hIp)),
              ("host", hostAddr != nil ? .s(hostAddr!.ip) : .null)])
        var handlers: [Int32: (Data, Addr) -> Void] = [:]
        let discHandler: ([UInt8], Addr) -> Void = { [self] d, s in onDisc(d, s) }
        handlers[dSw.fd] = { d, s in discHandler(Array(d), s) }
        handlers[dH.fd] = { d, s in discHandler(Array(d), s) }
        handlers[dSw2.fd] = { d, s in discHandler(Array(d), s) }
        handlers[dH2.fd] = { d, s in discHandler(Array(d), s) }
        handlers[gSw.fd] = { [self] d, s in onGameSock(Array(d), s, "sw", GAME_PORT) }
        handlers[gH.fd] = { [self] d, s in onGameSock(Array(d), s, "h") }
        handlers[gbSw.fd] = { [self] d, s in onGameSock(Array(d), s, "sw") }
        handlers[gbH.fd] = { [self] d, s in onGameSock(Array(d), s, "h") }
        var sockByFd: [Int32: UDPSock] = [:]
        for s in [dSw, dH, dSw2, dH2, gSw, gH, gbSw, gbH] {
            sockByFd[s.fd] = s
        }
        for (s, leg) in gx {
            sockByFd[s.fd] = s
            let lp: Int? = (leg == "sw" && s.boundIp == swIp) ? s.port : nil
            handlers[s.fd] = { [self] d, src in onGameSock(Array(d), src, leg, lp) }
        }
        var lastStat = now()
        while !stopRequested {
            mark("poll")
            var timeout = 0.5
            if let first = queue.first {
                timeout = max(0.0, min(timeout, first.due - now()))
            }
            var fds = handlers.keys.map { fd in
                pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            }
            let ready = poll(&fds, nfds_t(fds.count), Int32(timeout * 1000))
            if ready > 0 {
                for f in fds where f.revents & Int16(POLLIN) != 0 {
                    guard let sock = sockByFd[f.fd], let handler = handlers[f.fd] else { continue }
                    while let pkt = sock.recv() {
                        mark("packet fd \(f.fd) from \(pkt.ip):\(pkt.port) len \(pkt.data.count) game \(game ?? "-") host \(hostSide ?? "-")")
                        handler(Data(pkt.data), (pkt.ip, pkt.port))
                    }
                }
            }
            let t = now()
            while let first = queue.first, first.due <= t {
                queue.removeFirst()
                tx(first.sock, first.data, first.ip, first.port, first.direction, first.meta)
            }
            mark("timers")
            if t - lastScan >= 15 {
                lastScan = t
                mark("scan splatoon3")
                scanPoll()
                mark("scan mk8d")
                scanPollMk8d()
            }
            if t - lastStat > 5 {
                lastStat = t
                let swRole = hostSide == "sw" ? "host" : "switch"
                let hRole = hostSide == "h" ? "host" : "peer"
                cfg.log("forwarded: \(swRole) to \(hRole) \(txCounts["s2h"] ?? 0), \(hRole) to \(swRole) \(txCounts["h2s"] ?? 0)")
            }
        }
        if let h = eventsHandle {
            try? h.close()
            eventsHandle = nil
        }
        cap?.close()
        for s in sockByFd.values {
            s.close()
        }
        cfg.log("done: events saved to \(cfg.eventsPath)")
    }
}
