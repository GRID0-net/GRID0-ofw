import Foundation
import Darwin

// Relay packet handlers and the run loop, ported from zttestproxy.py.

extension Relay {

    func onDisc(_ data: [UInt8], _ src: Addr) {
        if src.ip == swIp || src.ip == hIp { return }
        guard let leg = legOf(src.ip) else { return }
        if dup(data, src.ip) { return }
        if leg == "sw" {
            onDiscSwitch(data, src)
        } else {
            onDiscHost(data, src)
        }
    }

    func onDiscSwitch(_ data: [UInt8], _ src: Addr) {
        if let only = onlySwitch, src.ip != only { return }
        if data.count >= 64 && data[0] == 1 {
            onReplyFromSwitch(data, src)
            return
        }
        guard let detected = detectGame(data) else { return }
        var game = detected
        var sealed: (out: [UInt8], chal: [UInt8])? = nil
        for g in (detected == "mk8d" ? ["mk8d", "splatoon2"] : [detected]) {
            if keyFor(g).isEmpty { continue }
            if g == "splatoon3" {
                sealed = resealRequest(gameKey: keyFor(g), req: data, oldBc: swBc, newBc: hBc,
                                       chalOff: CHAL_OFF[g]!)
            } else if g == "mkw" {
                sealed = resealRequestMkw(gameKey: keyFor(g), req: data, oldBc: swBc, newBc: hBc)
            } else {
                sealed = resealRequestPinned(gameKey: keyFor(g), req: data, oldBc: swBc, newBc: hBc,
                                             chalOff: CHAL_OFF[g]!, pin: pinS2h)
            }
            if sealed != nil {
                game = g
                break
            }
        }
        guard let (out, chal) = sealed else {
            cap?.save(kind: "disc", data: data, src: src.ip, dst: ipString(swBc),
                      sport: src.port, dport: portFor(game))
            discNote("note: a browse request from \(src.ip):\(src.port) could not be opened")
            return
        }
        noteGame(game)
        if let sw = switchAddr, sw.ip != src.ip {
            cfg.log("console changed: \(sw.ip) to \(src.ip)")
            switchAddr = (src.ip, GAME_PORT)
            if sessionPeer() != nil {
                buildTables()
            }
        }
        switchAddr = (src.ip, switchAddr?.port ?? GAME_PORT)
        addChal("s2h", game, chal)
        note([("ev", .s("rx")), ("dir", .s("s2h")), ("kind", .s("browse_req")),
              ("src", .s(src.ip)), ("len", .i(data.count))])
        let port = portFor(game)
        let sock = sockFor("h", game)
        cap?.save(kind: "disc", data: data, src: src.ip, dst: ipString(swBc), sport: src.port, dport: port)
        lastSwReq = out
        if game == "splatoon3" {
            stashReq = out
            saveStash()
        } else if game == "mk8d" {
            stashMk8d = out
            saveStashMk8d()
        } else if game == "splatoon2" {
            stashSplatoon2 = out
            saveStashSplatoon2()
        } else if game == "mkw" {
            stashMkw = out
            saveStashMkw()
        }
        lastSwGame = game
        send(sock, out, ipString(hBc), port, "s2h", meta: [("kind", .s("browse_req"))])
        let t = now()
        rooms = rooms.filter { t - $0.value < 60 }
        var targets = Set(rooms.keys.compactMap { k -> String? in
            let parts = k.split(separator: "|")
            return (parts.count == 2 && parts[0] == game) ? String(parts[1]) : nil
        })
        if let h = hostAddr {
            targets.insert(h.ip)
        }
        for ip in targets.sorted() {
            send(sock, out, ip, port, "s2h", meta: [("kind", .s("browse_req_uni"))])
        }
        if targets.isEmpty && t - lastSweep >= 5 {
            lastSweep = t
            let count = min(1 << (32 - hPrefix), 256)
            let base = ipToUInt32(hIp) & prefixMask(hPrefix)
            for i in 0..<count {
                let ip = uint32ToIp(base + UInt32(i))
                if ip != hIp && ip != ipString(hBc) {
                    send(sock, out, ip, port, "s2h",
                         meta: [("kind", .s("browse_req_uni")), ("probe", .i(1))])
                }
            }
        }
    }

    func stashPath() -> String {
        return cfg.filesDir + "/last_browse_request.bin"
    }

    func saveStash() {
        guard let data = stashReq else { return }
        try? Data(data).write(to: URL(fileURLWithPath: stashPath()))
    }

    func loadStash() {
        if let data = try? Data(contentsOf: URL(fileURLWithPath: stashPath())),
           data.count == 325 && data[0] == 0 {
            stashReq = Array(data)
        }
    }

    func stashPathMk8d() -> String {
        return cfg.filesDir + "/last_browse_request_mk8d.bin"
    }

    func saveStashMk8d() {
        guard let data = stashMk8d else { return }
        try? Data(data).write(to: URL(fileURLWithPath: stashPathMk8d()))
    }

    func loadStashMk8d() {
        if let data = try? Data(contentsOf: URL(fileURLWithPath: stashPathMk8d())),
           data.count == 873 && data[0] == 0 {
            stashMk8d = Array(data)
        }
    }

    func stashPathSplatoon2() -> String {
        return cfg.filesDir + "/last_browse_request_splatoon2.bin"
    }

    func saveStashSplatoon2() {
        guard let data = stashSplatoon2 else { return }
        try? Data(data).write(to: URL(fileURLWithPath: stashPathSplatoon2()))
    }

    func loadStashSplatoon2() {
        if let data = try? Data(contentsOf: URL(fileURLWithPath: stashPathSplatoon2())),
           data.count == 873 && data[0] == 0 {
            stashSplatoon2 = Array(data)
        }
    }

    func stashPathMkw() -> String {
        return cfg.filesDir + "/last_browse_request_mkw.bin"
    }

    func saveStashMkw() {
        guard let data = stashMkw else { return }
        try? Data(data).write(to: URL(fileURLWithPath: stashPathMkw()))
    }

    func loadStashMkw() {
        if let data = try? Data(contentsOf: URL(fileURLWithPath: stashPathMkw())),
           data.count == 352 && data[0] == 0 {
            stashMkw = Array(data)
        }
    }

    func roomTargets(_ game: String) -> Set<String> {
        return Set(rooms.keys.compactMap { k -> String? in
            let parts = k.split(separator: "|")
            return (parts.count == 2 && parts[0] == game) ? String(parts[1]) : nil
        })
    }

    func scanPollMk8d() {
        if keyMk8d != nil || stashMk8d == nil { return }
        scanCounter += 1
        guard let (req, chal) = rekeyRequestMk8d(gameKey: keyFor("mk8d"), req: stashMk8d!,
                                                 bc: hBc, counter: scanCounter) else { return }
        scanChalsMk8d.append(chal)
        scanChalsMk8d = Array(scanChalsMk8d.suffix(4))
        let sock = sockFor("h", "mk8d")
        let port = portFor("mk8d")
        send(sock, req, ipString(hBc), port, "scan",
             meta: [("kind", .s("browse_req_scan")), ("probe", .i(1))])
        let t = now()
        rooms = rooms.filter { t - $0.value < 60 }
        var targets = roomTargets("mk8d")
        if let h = hostAddr {
            targets.insert(h.ip)
        }
        targets.remove(hIp)
        if !targets.isEmpty {
            for ip in targets.sorted() {
                send(sock, req, ip, port, "scan",
                     meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
            }
        } else {
            let base = ipToUInt32(hIp) & prefixMask(hPrefix)
            let bc = base | ~prefixMask(hPrefix)
            var v = base + 1
            while v < bc {
                let ip = uint32ToIp(v)
                if ip != hIp {
                    send(sock, req, ip, port, "scan",
                         meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
                }
                v += 1
            }
        }
    }

    func scanPollSplatoon2() {
        if keySplatoon2 != nil || stashSplatoon2 == nil { return }
        scanCounter += 1
        guard let (req, chal) = rekeyRequestMk8d(gameKey: keyFor("splatoon2"), req: stashSplatoon2!,
                                                 bc: hBc, counter: scanCounter) else { return }
        scanChalsSplatoon2.append(chal)
        scanChalsSplatoon2 = Array(scanChalsSplatoon2.suffix(4))
        let sock = sockFor("h", "splatoon2")
        let port = portFor("splatoon2")
        send(sock, req, ipString(hBc), port, "scan",
             meta: [("kind", .s("browse_req_scan")), ("probe", .i(1))])
        let t = now()
        rooms = rooms.filter { t - $0.value < 60 }
        var targets = roomTargets("splatoon2")
        if let h = hostAddr {
            targets.insert(h.ip)
        }
        targets.remove(hIp)
        if !targets.isEmpty {
            for ip in targets.sorted() {
                send(sock, req, ip, port, "scan",
                     meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
            }
        } else {
            let base = ipToUInt32(hIp) & prefixMask(hPrefix)
            let bc = base | ~prefixMask(hPrefix)
            var v = base + 1
            while v < bc {
                let ip = uint32ToIp(v)
                if ip != hIp {
                    send(sock, req, ip, port, "scan",
                         meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
                }
                v += 1
            }
        }
    }

    func scanPollMkw() {
        if keyMkw != nil { return }
        scanCounter += 1
        var req: [UInt8]? = nil
        var chal: [UInt8]? = nil
        if let stash = stashMkw {
            if let r = rekeyRequestMkw(gameKey: keyFor("mkw"), req: stash, bc: hBc, counter: scanCounter) {
                req = r.req
                chal = r.chal
            }
        }
        if req == nil {
            let made = makeScanRequestMkw(gameKey: keyFor("mkw"), bc: hBc, counter: scanCounter)
            req = made.req
            chal = made.chal
        }
        guard let request = req, let challenge = chal else { return }
        scanChalsMkw.append(challenge)
        scanChalsMkw = Array(scanChalsMkw.suffix(4))
        let sock = sockFor("h", "mkw")
        let port = portFor("mkw")
        send(sock, request, ipString(hBc), port, "scan",
             meta: [("kind", .s("browse_req_scan")), ("probe", .i(1))])
        let t = now()
        rooms = rooms.filter { t - $0.value < 60 }
        var targets = roomTargets("mkw")
        if let h = hostAddr {
            targets.insert(h.ip)
        }
        targets.remove(hIp)
        if !targets.isEmpty {
            for ip in targets.sorted() {
                send(sock, request, ip, port, "scan",
                     meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
            }
        } else {
            let base = ipToUInt32(hIp) & prefixMask(hPrefix)
            let bc = base | ~prefixMask(hPrefix)
            var v = base + 1
            while v < bc {
                let ip = uint32ToIp(v)
                if ip != hIp {
                    send(sock, request, ip, port, "scan",
                         meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
                }
                v += 1
            }
        }
    }

    func scanPoll() {
        scanCounter += 1
        var req: [UInt8]? = nil
        var chal: [UInt8]? = nil
        if let stash = stashReq {
            if let r = rekeyRequest(gameKey: keyFor("splatoon3"), req: stash, bc: hBc, counter: scanCounter) {
                req = r.req
                chal = r.chal
            }
        }
        if req == nil {
            let made = makeScanRequest(gameKey: keyFor("splatoon3"), bc: hBc, counter: scanCounter)
            req = made.req
            chal = made.chal
        }
        guard let request = req, let challenge = chal else { return }
        scanChals.append(challenge)
        scanChals = Array(scanChals.suffix(4))
        let sock = sockFor("h", "splatoon3")
        let port = portFor("splatoon3")
        send(sock, request, ipString(hBc), port, "s2h",
             meta: [("kind", .s("browse_req_scan")), ("probe", .i(1))])
        let t = now()
        rooms = rooms.filter { t - $0.value < 60 }
        var targets = roomTargets("splatoon3")
        if let h = hostAddr {
            targets.insert(h.ip)
        }
        for ip in targets.sorted() {
            send(sock, request, ip, port, "s2h",
                 meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
        }
        if targets.isEmpty {
            let count = min(1 << (32 - hPrefix), 256)
            let base = ipToUInt32(hIp) & prefixMask(hPrefix)
            for i in 0..<count {
                let ip = uint32ToIp(base + UInt32(i))
                if ip != hIp && ip != ipString(hBc) {
                    send(sock, request, ip, port, "s2h",
                         meta: [("kind", .s("browse_req_scan_uni")), ("probe", .i(1))])
                }
            }
        }
    }

    func onDiscHost(_ data: [UInt8], _ src: Addr) {
        if let game = detectGame(data) {
            onRequestFromHost(data, src, game)
            return
        }
        if data.count >= 64 && data[0] == 1 && hostAddr == nil {
            hostAddr = (src.ip, GAME_PORT)
            if let lastReq = lastSwReq {
                let g = lastSwGame
                send(sockFor("h", g), lastReq, src.ip, portFor(g), "s2h",
                     meta: [("kind", .s("browse_req_uni"))])
            }
        }
        if data.count >= 64 && data[0] == 1 && !scanChals.isEmpty {
            var opened = false
            for chal in scanChals {
                if resealReply(gameKey: keyFor("splatoon3"), rep: data, reqChal: chal,
                               oldBc: hBc, newBc: hBc, oldHost: ip4(src.ip), newHost: ip4(src.ip)) != nil {
                    opened = true
                    break
                }
            }
            if opened {
                rooms["splatoon3|\(src.ip)"] = now()
                if let rname = splatoonHostName(data) {
                    noteRoom("splatoon3", src.ip, rname, roomPlayers("splatoon3", data))
                }
                return
            }
            discNote("note: a reply from \(src.ip):\(src.port) did not open with a scan challenge")
            cap?.save(kind: "disc", data: data, src: src.ip, dst: hIp, sport: src.port, dport: src.port)
        }
        if data.count >= 64 && data[0] == 1 && !scanChalsMk8d.isEmpty {
            var cands = scanChalsMk8d
            if switchAddr == nil {
                cands.append(pinS2h)
            }
            var opened = false
            for chal in cands {
                if resealReply(gameKey: keyFor("mk8d"), rep: data, reqChal: chal,
                               oldBc: hBc, newBc: hBc, oldHost: ip4(src.ip), newHost: ip4(src.ip),
                               paramOff: mk8dParamOff(data), frag: true) != nil {
                    opened = true
                    break
                }
            }
            if opened {
                rooms["mk8d|\(src.ip)"] = now()
                noteRoom("mk8d", src.ip, mk8dHostName(data), roomPlayers("mk8d", data))
                return
            }
            discNote("note: a reply from \(src.ip):\(src.port) did not open with a scan challenge")
            cap?.save(kind: "disc", data: data, src: src.ip, dst: hIp, sport: src.port, dport: src.port)
        }
        if data.count >= 64 && data[0] == 1 && !scanChalsSplatoon2.isEmpty {
            var cands = scanChalsSplatoon2
            if switchAddr == nil {
                cands.append(pinS2h)
            }
            var opened = false
            for chal in cands {
                if resealReply(gameKey: keyFor("splatoon2"), rep: data, reqChal: chal,
                               oldBc: hBc, newBc: hBc, oldHost: ip4(src.ip), newHost: ip4(src.ip),
                               paramOff: mk8dParamOff(data)) != nil {
                    opened = true
                    break
                }
            }
            if opened {
                rooms["splatoon2|\(src.ip)"] = now()
                noteRoom("splatoon2", src.ip, splatoon2HostName(data), roomPlayers("splatoon2", data))
                return
            }
            discNote("note: a reply from \(src.ip):\(src.port) did not open with a scan challenge")
            cap?.save(kind: "disc", data: data, src: src.ip, dst: hIp, sport: src.port, dport: src.port)
        }
        if data.count >= 64 && data[0] == 1 && !scanChalsMkw.isEmpty {
            var opened = false
            for chal in scanChalsMkw {
                if resealReplyMkw(gameKey: keyFor("mkw"), rep: data, reqChal: chal,
                                  oldBc: hBc, newBc: hBc, oldHost: ip4(src.ip), newHost: ip4(src.ip)) != nil {
                    opened = true
                    break
                }
            }
            if opened {
                rooms["mkw|\(src.ip)"] = now()
                noteRoom("mkw", src.ip, mkwHostName(data), roomPlayers("mkw", data))
                return
            }
            discNote("note: a reply from \(src.ip):\(src.port) did not open with a scan challenge")
            cap?.save(kind: "disc", data: data, src: src.ip, dst: hIp, sport: src.port, dport: src.port)
        }
        if data.count < 64 || data[0] != 1 || switchAddr == nil {
            return
        }
        var out: [UInt8]? = nil
        var game: String? = nil
        if !mk8dReqChals.isEmpty {
            out = resealReplyRekey(gameKey: keyFor("mk8d"), rep: data, chalIn: pinS2h,
                                   chalOut: mk8dReqChals[0], oldBc: hBc, newBc: swBc,
                                   oldHost: ip4(src.ip), newHost: swMe,
                                   paramOff: mk8dParamOff(data), frag: true)
            if out != nil { game = "mk8d" }
        }
        if out == nil && !splatoon2ReqChals.isEmpty {
            out = resealReplyRekey(gameKey: keyFor("splatoon2"), rep: data, chalIn: pinS2h,
                                   chalOut: splatoon2ReqChals[0], oldBc: hBc, newBc: swBc,
                                   oldHost: ip4(src.ip), newHost: swMe,
                                   paramOff: mk8dParamOff(data))
            if out != nil { game = "splatoon2" }
        }
        if out == nil && !mkwReqChals.isEmpty {
            out = resealReplyMkw(gameKey: keyFor("mkw"), rep: data, reqChal: mkwReqChals[0],
                                 oldBc: hBc, newBc: swBc, oldHost: ip4(src.ip), newHost: swMe)
            if out != nil { game = "mkw" }
        }
        if out == nil {
            for g in ["splatoon3", "mk8d", "splatoon2"] {
                for chal in chalsFor("s2h", g) {
                    let r = resealReply(gameKey: keyFor(g), rep: data, reqChal: chal,
                                        oldBc: hBc, newBc: swBc, oldHost: ip4(src.ip), newHost: swMe,
                                        paramOff: (g == "mk8d" || g == "splatoon2") ? mk8dParamOff(data) : PARAM_OFF[g]!,
                                        frag: g == "mk8d")
                    if r != nil {
                        out = r
                        game = g
                        break
                    }
                }
                if out != nil { break }
            }
        }
        guard let sealed = out, let g = game else {
            cap?.save(kind: "disc", data: data, src: src.ip, dst: switchAddr!.ip,
                      sport: src.port, dport: src.port)
            discNote("note: a browse reply from \(src.ip):\(src.port) matched no recent request")
            return
        }
        noteGame(g)
        takeSessionKey(g, data)
        hostSide = "h"
        if hostAddr == nil || hostAddr!.ip != src.ip {
            hostAddr = (src.ip, GAME_PORT)
            buildTables()
        } else if tables == nil {
            buildTables()
        }
        rooms["\(g)|\(src.ip)"] = now()
        if g == "splatoon3" {
            if let rname = splatoonHostName(data) {
                noteRoom("splatoon3", src.ip, rname, roomPlayers("splatoon3", data))
            }
        } else if g == "mk8d" {
            noteRoom("mk8d", src.ip, mk8dHostName(data), roomPlayers("mk8d", data))
        } else if g == "splatoon2" {
            noteRoom("splatoon2", src.ip, splatoon2HostName(data), roomPlayers("splatoon2", data))
        } else if g == "mkw" {
            noteRoom("mkw", src.ip, mkwHostName(data), roomPlayers("mkw", data))
        }
        var entry = sessions[src.ip] ?? SessionEntry()
        if let k = (g == "mk8d" ? keyMk8d : (g == "splatoon2" ? keySplatoon2 : (g == "mkw" ? keyMkw : key))) {
            entry.keys[g] = k
        }
        entry.tables = tablesFor(src.ip)
        sessions[src.ip] = entry
        note([("ev", .s("rx")), ("dir", .s("h2s")), ("kind", .s("browse_reply")),
              ("src", .s(src.ip)), ("len", .i(data.count))])
        let port = portFor(g)
        cap?.save(kind: "disc", data: data, src: src.ip, dst: switchAddr!.ip, sport: src.port, dport: port)
        send(sockFor("sw", g), sealed, switchAddr!.ip, port, "h2s", meta: [("kind", .s("browse_reply"))])
    }

    func onRequestFromHost(_ data: [UInt8], _ src: Addr, _ detected: String) {
        var game = detected
        var sealed: (out: [UInt8], chal: [UInt8])? = nil
        for g in (detected == "mk8d" ? ["mk8d", "splatoon2"] : [detected]) {
            if keyFor(g).isEmpty { continue }
            if g == "splatoon3" {
                sealed = resealRequest(gameKey: keyFor(g), req: data, oldBc: hBc, newBc: swBc,
                                       chalOff: CHAL_OFF[g]!)
            } else if g == "mkw" {
                sealed = resealRequestMkw(gameKey: keyFor(g), req: data, oldBc: hBc, newBc: swBc)
            } else {
                sealed = resealRequestPinned(gameKey: keyFor(g), req: data, oldBc: hBc, newBc: swBc,
                                             chalOff: CHAL_OFF[g]!, pin: pinH2s)
            }
            if sealed != nil {
                game = g
                break
            }
        }
        guard let (out, chal) = sealed else {
            cap?.save(kind: "disc", data: data, src: src.ip, dst: ipString(hBc),
                      sport: src.port, dport: portFor(game))
            discNote("note: a browse request from peer \(src.ip):\(src.port) could not be opened")
            return
        }
        noteGame(game)
        peerAddr = (src.ip, peerAddr?.port ?? GAME_PORT)
        addChal("h2s", game, chal)
        note([("ev", .s("rx")), ("dir", .s("h2s")), ("kind", .s("browse_req")),
              ("src", .s(src.ip)), ("len", .i(data.count))])
        let port = portFor(game)
        let sock = sockFor("sw", game)
        cap?.save(kind: "disc", data: data, src: src.ip, dst: ipString(hBc), sport: src.port, dport: port)
        send(sock, out, ipString(swBc), port, "h2s", meta: [("kind", .s("browse_req"))])
        if let sw = switchAddr {
            send(sock, out, sw.ip, port, "h2s", meta: [("kind", .s("browse_req_uni"))])
        }
    }

    func onReplyFromSwitch(_ data: [UInt8], _ src: Addr) {
        guard let destPeer = peerAddr ?? hostAddr else { return }
        var out: [UInt8]? = nil
        var game: String? = nil
        if !mk8dHostReqChals.isEmpty {
            out = resealReplyRekey(gameKey: keyFor("mk8d"), rep: data, chalIn: pinH2s,
                                   chalOut: mk8dHostReqChals[0], oldBc: swBc, newBc: hBc,
                                   oldHost: ip4(src.ip), newHost: hMe,
                                   paramOff: mk8dParamOff(data), frag: true)
            if out != nil { game = "mk8d" }
        }
        if out == nil && !splatoon2HostReqChals.isEmpty {
            out = resealReplyRekey(gameKey: keyFor("splatoon2"), rep: data, chalIn: pinH2s,
                                   chalOut: splatoon2HostReqChals[0], oldBc: swBc, newBc: hBc,
                                   oldHost: ip4(src.ip), newHost: hMe,
                                   paramOff: mk8dParamOff(data))
            if out != nil { game = "splatoon2" }
        }
        if out == nil && !mkwHostReqChals.isEmpty {
            out = resealReplyMkw(gameKey: keyFor("mkw"), rep: data, reqChal: mkwHostReqChals[0],
                                 oldBc: swBc, newBc: hBc, oldHost: ip4(src.ip), newHost: hMe)
            if out != nil { game = "mkw" }
        }
        if out == nil {
            for g in ["splatoon3", "mk8d", "splatoon2"] {
                for chal in chalsFor("h2s", g) {
                    let r = resealReply(gameKey: keyFor(g), rep: data, reqChal: chal,
                                        oldBc: swBc, newBc: hBc, oldHost: ip4(src.ip), newHost: hMe,
                                        paramOff: (g == "mk8d" || g == "splatoon2") ? mk8dParamOff(data) : PARAM_OFF[g]!,
                                        frag: g == "mk8d")
                    if r != nil {
                        out = r
                        game = g
                        break
                    }
                }
                if out != nil { break }
            }
        }
        guard let sealed = out, let g = game else {
            cap?.save(kind: "disc", data: data, src: src.ip, dst: destPeer.ip,
                      sport: src.port, dport: src.port)
            discNote("note: a Switch reply from \(src.ip):\(src.port) matched no recent peer request")
            return
        }
        noteGame(g)
        takeSessionKey(g, data)
        hostSide = "sw"
        if switchAddr == nil || switchAddr!.ip != src.ip {
            switchAddr = (src.ip, GAME_PORT)
            buildTables()
        } else if tables == nil {
            buildTables()
        }
        note([("ev", .s("rx")), ("dir", .s("s2h")), ("kind", .s("browse_reply")),
              ("src", .s(src.ip)), ("len", .i(data.count))])
        let port = portFor(g)
        cap?.save(kind: "disc", data: data, src: src.ip, dst: destPeer.ip, sport: src.port, dport: port)
        send(sockFor("h", g), sealed, destPeer.ip, port, "s2h", meta: [("kind", .s("browse_reply"))])
    }

    func noteRoom(_ game: String, _ host: String, _ name: String?, _ players: Int? = nil) {
        if let name = name {
            roomNames[host] = name
        }
        if let players = players {
            roomCounts[host] = players
        }
        var fields: [(String, JVal)] = [("ev", .s("room")), ("game", .s(game)), ("host", .s(host))]
        if let known = roomNames[host] {
            fields.append(("name", .s(known)))
        }
        if let count = roomCounts[host] {
            fields.append(("players", .i(count)))
        }
        fields.append(("ts", .i(Int(now()))))
        note(fields)
    }

    func touchRoom(_ host: String, _ game: String = "mk8d") {
        let t = now()
        if t - (roomNoted[host] ?? 0) < 30 { return }
        roomNoted[host] = t
        rooms["\(game)|\(host)"] = t
        noteRoom(game, host, nil)
    }
}
