import SwiftUI

@main
struct GRID0-ofwApp: App {
    @StateObject private var relay = RelayModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(relay)
                .frame(minWidth: 720, minHeight: 600)
        }
        .windowResizability(.contentMinSize)
    }
}
