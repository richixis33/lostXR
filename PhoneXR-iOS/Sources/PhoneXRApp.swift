import SwiftUI

@main
struct PhoneXRApp: App {
    @StateObject private var library = GameLibrary()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
        }
    }
}
