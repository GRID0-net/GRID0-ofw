import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                tabBar
                if model.switchPillVisible {
                    HStack(spacing: 6) {
                        SwitchLogoView()
                            .frame(width: 20, height: 18)
                        Text(model.switchIp ?? "")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Color.green))
                    .padding(.top, 8)
                }
                if model.currentTab == 0 {
                    playView
                } else {
                    moreView
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(UIColor.systemBackground))

            if model.currentTab == 0 && !model.tutorialOn {
                ZtButton()
                    .padding(.trailing, 20)
                    .padding(.bottom, 20)
            }

            if let toast = model.toast {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.system(size: 14))
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Capsule().fill(Color.black.opacity(0.8)))
                        .padding(.bottom, 90)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: $model.showRooms) {
            CurrentRoomsView()
                .environmentObject(model)
        }
    }

    private var tabBar: some View {
        HStack(spacing: 4) {
            tabButton("PLAY", index: 0)
            tabButton("MORE", index: 1)
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color(red: 0xDD / 255, green: 0xDD / 255, blue: 0xDD / 255))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white, lineWidth: 1))
        )
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    private func tabButton(_ title: String, index: Int) -> some View {
        Button(action: {
            model.currentTab = index
            if index == 1 {
                model.refreshInterfaces()
                model.refreshFromFiles()
            }
        }) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(model.currentTab == index ? .white : .black)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(model.currentTab == index ? Color.black : Color.clear)
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - PLAY

    private var playView: some View {
        ScrollView {
            VStack(spacing: 0) {
                ZStack {
                    if model.proxyRunning {
                        RippleRings()
                    }
                    Image("logo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 184, height: 184)
                }
                .frame(width: 200, height: 200)
                .padding(.top, 16)
                .onTapGesture {
                    if !model.tutorialOn && !model.proxyRunning {
                        if !model.ztInstalled {
                            model.showToast("ZeroTier isn't installed")
                            return
                        }
                        if !model.grid0Connected {
                            model.showToast("You're not connected to GRID0")
                            return
                        }
                    }
                    model.toggleProxy()
                }

                stepLabel("Step 1", top: 8)
                stepRow(done: model.ztInstalled) {
                    stepButton("Get ZeroTier",
                               filled: !model.ztInstalled,
                               enabled: !model.ztInstalled) {
                        model.openZeroTier()
                    }
                }
                stepLabel("Step 2", top: 16)
                stepRow(done: model.grid0Connected) {
                    stepButton("Connect to GRID0",
                               filled: model.ztInstalled && !model.grid0Connected,
                               enabled: model.ztInstalled && !model.grid0Connected) {
                        model.openZeroTier()
                    }
                }
                stepLabel("Step 3", top: 16)
                stepRow(done: false) {
                    stepButton(model.proxyRunning ? "Stop Proxy" : "Start Proxy",
                               filled: model.grid0Connected,
                               enabled: model.grid0Connected) {
                        model.toggleProxy()
                    }
                }

                Button(action: { model.showRooms = true }) {
                    Text("Current Rooms")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.black)
                        .frame(width: 170, height: 40)
                        .background(
                            Capsule()
                                .fill(Color(red: 0x9E / 255, green: 0x9E / 255, blue: 0x9E / 255))
                                .overlay(Capsule().stroke(Color.black, lineWidth: 2))
                        )
                }
                .buttonStyle(.plain)
                .padding(.top, 16)
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 110)
        }
    }

    private func stepLabel(_ text: String, top: CGFloat) -> some View {
        Text(text)
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(.secondary)
            .padding(.top, top)
            .padding(.bottom, 6)
    }

    private func stepRow<Content: View>(done: Bool, @ViewBuilder content: () -> Content) -> some View {
        ZStack {
            content()
            HStack {
                Spacer()
                if done {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                        .font(.system(size: 22))
                }
            }
            .frame(width: 245)
        }
    }

    private func stepButton(_ title: String, filled: Bool, enabled: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(filled ? .white : .black)
                .frame(width: 245, height: 48)
                .background(
                    RoundedRectangle(cornerRadius: 24)
                        .fill(filled ? Color.black : Color(red: 0xDD / 255, green: 0xDD / 255, blue: 0xDD / 255))
                )
                .opacity(enabled ? 1 : 0.55)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    // MARK: - MORE

    private var moreView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("v3.3.6")
                        .font(.system(size: 12))
                        .foregroundColor(Color(red: 0xAA / 255, green: 0xAA / 255, blue: 0xAA / 255))
                    Spacer()
                    Button(action: {
                        model.refreshInterfaces()
                        model.refreshFromFiles()
                    }) {
                        Image(systemName: "arrow.clockwise")
                            .foregroundColor(.primary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)

                VStack(spacing: 0) {
                    moreRow("ZeroTier Installed", model.ztInstalled ? "Yes" : "No")
                    moreRow("ZeroTier IP", model.ztIp ?? "Not connected")
                    moreRow("Phone IP", model.phoneIp ?? "Not connected")
                    moreRow("Switch IP", model.switchIp ?? "Not detected")
                    moreRow("Client Isolation", isolationText)
                    moreRow("Wi-Fi Status", model.phoneIp != nil ? "Connected" : "Not connected")
                    moreRow("ZeroTier Status", model.ztIp != nil ? "Connected" : "Not connected")
                    moreRow("GRID0 Status", model.grid0Connected ? "Connected" : "Not connected")
                    moreRow("Network ID", AppModel.networkId)
                }
                .padding(4)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(UIColor.lightGray), lineWidth: 1))
                .padding(.horizontal, 16)
                .padding(.top, 2)

                HStack {
                    Text("Tutorial")
                        .font(.system(size: 16))
                    Spacer()
                    Toggle("", isOn: $model.tutorialOn)
                        .labelsHidden()
                }
                .padding(.horizontal, 16)
                .padding(.top, 20)

                logBox
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
            }
            .padding(.bottom, 40)
        }
    }

    private var isolationText: String {
        if model.proxyRunning && model.switchIp != nil {
            return "Off (Switch reachable)"
        }
        return "Not checked"
    }

    private var logBox: some View {
        ZStack(alignment: .topTrailing) {
            ScrollView {
                Text(model.logText.isEmpty ? " " : model.logText)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .textSelection(.enabled)
            }
            .frame(height: 260)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.black))

            HStack(spacing: 8) {
                circleIconButton("doc.on.doc") {
                    UIPasteboard.general.string = model.logText
                    model.showToast("Log copied")
                }
                circleIconButton("square.and.arrow.up") {
                    if let url = model.shareCapture() {
                        SharePresenter.present(url: url)
                    }
                }
            }
            .padding(8)
        }
    }

    private func circleIconButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.black)
                .frame(width: 30, height: 30)
                .background(Circle().fill(Color.white))
        }
        .buttonStyle(.plain)
    }

    private func moreRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
            Spacer()
            Text(value)
                .font(.system(size: 14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 8)
    }
}

struct RippleRings: View {
    @State private var phase = false
    var body: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .stroke(Color.black.opacity(0.35), lineWidth: 2)
                    .frame(width: 190, height: 190)
                    .scaleEffect(phase ? 1.25 + CGFloat(i) * 0.12 : 1.0 + CGFloat(i) * 0.12)
                    .opacity(phase ? 0.0 : 0.5)
                    .animation(
                        Animation.easeOut(duration: 1.8)
                            .repeatForever(autoreverses: false)
                            .delay(Double(i) * 0.6),
                        value: phase)
            }
        }
        .onAppear { phase = true }
    }
}

struct ZtButton: View {
    @EnvironmentObject var model: AppModel
    @State private var spin = false

    var body: some View {
        Button(action: { model.openZeroTier() }) {
            ZStack {
                if model.grid0Connected {
                    ForEach(0..<6, id: \.self) { i in
                        Circle()
                            .fill(Color(red: 1.0, green: 0xB2 / 255, blue: 0x5B / 255))
                            .frame(width: 7, height: 7)
                            .offset(x: spin ? 26 : 36)
                            .rotationEffect(.degrees(Double(i) * 60 + (spin ? 30 : 0)))
                    }
                }
                Image("zt_logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 46, height: 46)
                    .grayscale(model.grid0Connected ? 0 : 1)
            }
            .frame(width: 88, height: 88)
        }
        .buttonStyle(.plain)
        .onAppear {
            withAnimation(Animation.linear(duration: 4.2).repeatForever(autoreverses: false)) {
                spin = true
            }
        }
    }
}

struct CurrentRoomsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss

    private let grey = Color(red: 0x9E / 255, green: 0x9E / 255, blue: 0x9E / 255)

    var body: some View {
        ZStack {
            grey.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    Text("Current Rooms")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.black)
                        .padding(.top, 20)
                    Button(action: { model.refreshFromFiles() }) {
                        Text("Refresh All")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.black)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(
                                Capsule()
                                    .fill(Color(red: 0xEE / 255, green: 0xEE / 255, blue: 0xEE / 255))
                                    .overlay(Capsule().stroke(Color.black, lineWidth: 1.5))
                            )
                    }
                    .buttonStyle(.plain)
                    ForEach(orderedGames(), id: \.game) { entry in
                        gameSection(game: entry.game, logo: entry.logo)
                    }
                    Button("Close") { dismiss() }
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.black)
                        .padding(.top, 4)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.bottom, 30)
            }
        }
    }

    private func orderedGames() -> [(game: String, logo: String)] {
        let all: [(game: String, logo: String)] = [
            ("splatoon3", "logo_splatoon3"),
            ("mk8d", "logo_mk8d"),
            ("splatoon2", "logo_splatoon2"),
            ("mkw", "logo_mkw"),
        ]
        return all.enumerated().sorted { a, b in
            let pa = model.playersFor(a.element.game)
            let pb = model.playersFor(b.element.game)
            if pa != pb { return pa > pb }
            return a.offset < b.offset
        }.map { $0.element }
    }

    private func gameSection(game: String, logo: String) -> some View {
        VStack(spacing: 10) {
            HStack(alignment: .center) {
                Image(logo)
                    .resizable()
                    .scaledToFit()
                    .frame(width: game == "mkw" ? 180 : 150)
                Spacer()
                Button(action: { model.refreshFromFiles() }) {
                    Text("↻")
                        .font(.system(size: 22))
                        .foregroundColor(.black)
                        .frame(width: 44, height: 44)
                        .background(
                            Circle()
                                .fill(Color(red: 0xEE / 255, green: 0xEE / 255, blue: 0xEE / 255))
                                .overlay(Circle().stroke(Color.black, lineWidth: 1.5))
                        )
                }
                .buttonStyle(.plain)
            }
            let list = model.roomsFor(game)
            Group {
                if !model.proxyRunning {
                    Text("Start the proxy to see open rooms")
                } else if list.isEmpty {
                    Text("No rooms open right now")
                } else {
                    VStack(spacing: 4) {
                        ForEach(list) { room in
                            Text(room.display)
                        }
                    }
                }
            }
            .font(.system(size: 15))
            .foregroundColor(.black)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

enum SharePresenter {
    static func present(url: URL) {
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.windows.first?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController {
            top = presented
        }
        if let pop = vc.popoverPresentationController {
            pop.sourceView = top.view
            pop.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
        }
        top.present(vc, animated: true)
    }
}

struct SwitchLogoView: View {
    var body: some View {
        HStack(spacing: 3) {
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .stroke(Color.white, lineWidth: 2)
                Circle()
                    .fill(Color.white)
                    .frame(width: 5, height: 5)
                    .offset(y: -4)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.white)
                Circle()
                    .fill(Color.green)
                    .frame(width: 5, height: 5)
                    .offset(y: 4)
            }
        }
    }
}
