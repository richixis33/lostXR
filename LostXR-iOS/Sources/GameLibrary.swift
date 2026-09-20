import Foundation

struct ImportedGame: Codable, Identifiable {
    let id: UUID
    let fileName: String
    let storedPath: String
    let importedAt: Date
    let report: PackageReport
}

@MainActor
final class GameLibrary: ObservableObject {
    @Published private(set) var games = [ImportedGame]()
    @Published var message = "Готово"

    private let fileManager = FileManager.default
    private var root: URL {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("ImportedGames", isDirectory: true)
    }
    private var indexURL: URL { root.appendingPathComponent("library.json") }

    init() { load() }

    func importPackage(_ source: URL) {
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }
        do {
            try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
            let report = try APKAnalyzer.inspect(source)
            let name = "\(UUID().uuidString)-\(source.lastPathComponent)"
            let destination = root.appendingPathComponent(name)
            try fileManager.copyItem(at: source, to: destination)
            games.insert(ImportedGame(
                id: UUID(),
                fileName: source.lastPathComponent,
                storedPath: destination.path,
                importedAt: Date(),
                report: report
            ), at: 0)
            try save()
            message = report.hasOpenXR ? "OpenXR APK импортирован для переноса/порта" : "Файл импортирован"
        } catch {
            message = "Ошибка импорта: \(error.localizedDescription)"
        }
    }

    func remove(_ offsets: IndexSet) {
        for index in offsets {
            try? fileManager.removeItem(atPath: games[index].storedPath)
        }
        games.remove(atOffsets: offsets)
        try? save()
    }

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let stored = try? JSONDecoder().decode([ImportedGame].self, from: data) else { return }
        games = stored
    }

    private func save() throws {
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(games).write(to: indexURL, options: .atomic)
    }
}
