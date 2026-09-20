import SwiftUI

@main
struct LostXRApp: App {
    @StateObject private var library = GameLibrary()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
        }
    }
}
