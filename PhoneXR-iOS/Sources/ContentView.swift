import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var library: GameLibrary
    @State private var importing = false
    @State private var showingHands = false

    private var packageTypes: [UTType] {
        [UTType(filenameExtension: "apk"), .zip, UTType(filenameExtension: "pxr")].compactMap { $0 }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { importing = true } label: {
                        Label("Импортировать .pxr, APK или ZIP", systemImage: "square.and.arrow.down")
                    }
                    Button { showingHands = true } label: {
                        Label("Проверить трекинг рук", systemImage: "hand.raised")
                    }
                } header: {
                    Text("PhoneXR для iPhone")
                } footer: {
                    Text("APK сохраняется на iPhone и анализируется. Android-код не запускается как iOS-код: для игры потребуется порт из исходников.")
                }

                Section("Библиотека") {
                    if library.games.isEmpty {
                        ContentUnavailableView("Игр пока нет", systemImage: "visionpro", description: Text("Импортируйте OpenXR или Quest APK"))
                    }
                    ForEach(library.games) { game in
                        NavigationLink {
                            GameDetails(game: game)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(game.report.title).font(.headline)
                                Text(game.report.hasOpenXR ? "OpenXR / Quest" : "Архив")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete(perform: library.remove)
                }

                Section { Text(library.message).foregroundStyle(.secondary) }
            }
            .navigationTitle("PhoneXR")
            .fileImporter(isPresented: $importing, allowedContentTypes: packageTypes) { result in
                if case let .success(url) = result { library.importPackage(url) }
                if case let .failure(error) = result { library.message = error.localizedDescription }
            }
            .fullScreenCover(isPresented: $showingHands) {
                HandTrackingView().ignoresSafeArea()
            }
        }
    }
}

private struct GameDetails: View {
    let game: ImportedGame

    var body: some View {
        List {
            Section("Проверка пакета") {
                ForEach(game.report.details, id: \.self) { detail in
                    Label(detail, systemImage: detail.contains("не ") || detail.contains("нужен") ? "exclamationmark.triangle" : "checkmark.circle")
                }
            }
            Section {
                Button("Запустить") { }
                    .disabled(!game.report.canLaunchNatively)
            } footer: {
                Text(game.report.canLaunchNatively ? "Нативный пакет PhoneXR готов." : "Этот APK содержит Android-бинарники. Нужна пересборка игры под iOS/Metal.")
            }
        }
        .navigationTitle(game.report.title)
    }
}
