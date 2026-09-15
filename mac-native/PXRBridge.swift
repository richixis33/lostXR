import Foundation

struct PXRManifest: Codable {
    let format: String
    let version: Int
    let title: String
    let identifier: String
    let kind: String?
    let source: String?
    let platforms: [String: String]?
}

enum PXRPlatform: String {
    case android
    case ios
}

final class PXRBridge: @unchecked Sendable {
    private let files = FileManager.default

    func create(title: String, androidAPK: URL, iosIPA: URL, destination: URL) throws -> String {
        let root = files.temporaryDirectory.appendingPathComponent("pxr-create-\(UUID().uuidString)")
        defer { try? files.removeItem(at: root) }
        let androidFolder = root.appendingPathComponent("payload/android", isDirectory: true)
        let iosFolder = root.appendingPathComponent("payload/ios", isDirectory: true)
        try files.createDirectory(at: androidFolder, withIntermediateDirectories: true)
        try files.createDirectory(at: iosFolder, withIntermediateDirectories: true)
        try files.copyItem(at: androidAPK, to: androidFolder.appendingPathComponent("game.apk"))
        try files.copyItem(at: iosIPA, to: iosFolder.appendingPathComponent("game.ipa"))

        let manifest = PXRManifest(
            format: "com.phonexr.pxr",
            version: 1,
            title: title,
            identifier: UUID().uuidString.lowercased(),
            kind: "binaries",
            source: nil,
            platforms: ["android": "payload/android/game.apk", "ios": "payload/ios/game.ipa"]
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(manifest).write(to: root.appendingPathComponent("manifest.json"), options: .atomic)

        try? files.removeItem(at: destination)
        try runDitto(["-c", "-k", "--sequesterRsrc", ".", destination.path], directory: root)
        return "Универсальный пакет создан: \(destination.path)"
    }

    func createSource(source input: URL, destination: URL) throws -> String {
        let root = files.temporaryDirectory.appendingPathComponent("pxr-source-\(UUID().uuidString)")
        defer { try? files.removeItem(at: root) }
        try files.createDirectory(at: root, withIntermediateDirectories: true)

        let project: URL
        if input.pathExtension.lowercased() == "zip" {
            let unpacked = root.appendingPathComponent("input", isDirectory: true)
            try files.createDirectory(at: unpacked, withIntermediateDirectories: true)
            try runDitto(["-x", "-k", input.path, unpacked.path])
            guard let found = findUnityProject(under: unpacked) else {
                throw BridgeError.message("ZIP не содержит Unity-проект.")
            }
            project = found
        } else {
            project = input
        }
        guard files.fileExists(atPath: project.appendingPathComponent("Assets").path),
              files.fileExists(atPath: project.appendingPathComponent("ProjectSettings/ProjectVersion.txt").path) else {
            throw BridgeError.message("Нужен Unity-проект с Assets и ProjectSettings.")
        }

        let sourceFolder = root.appendingPathComponent("source", isDirectory: true)
        try files.createDirectory(at: sourceFolder, withIntermediateDirectories: true)
        for name in ["Assets", "Packages", "ProjectSettings"] {
            let item = project.appendingPathComponent(name)
            if files.fileExists(atPath: item.path) {
                try files.copyItem(at: item, to: sourceFolder.appendingPathComponent(name))
            }
        }
        let manifest = PXRManifest(
            format: "com.phonexr.pxr",
            version: 2,
            title: project.lastPathComponent,
            identifier: UUID().uuidString.lowercased(),
            kind: "unity-source",
            source: "source",
            platforms: nil
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(manifest).write(to: root.appendingPathComponent("manifest.json"), options: .atomic)
        try? files.removeItem(at: destination)
        try runDitto(["-c", "-k", "--sequesterRsrc", ".", destination.path], directory: root)
        return "Source PXR создан: \(destination.path). Его можно собрать для Android и iPhone."
    }

    func extract(_ package: URL, platform: PXRPlatform) throws -> URL {
        let root = files.temporaryDirectory.appendingPathComponent("pxr-open-\(UUID().uuidString)")
        try files.createDirectory(at: root, withIntermediateDirectories: true)
        do {
            try runDitto(["-x", "-k", package.path, root.path])
            let manifestURL = root.appendingPathComponent("manifest.json")
            let manifest = try JSONDecoder().decode(PXRManifest.self, from: Data(contentsOf: manifestURL))
            guard manifest.format == "com.phonexr.pxr",
                  let relative = manifest.platforms?[platform.rawValue] else {
                throw BridgeError.message("В .pxr нет сборки для \(platform.rawValue).")
            }
            let payload = root.appendingPathComponent(relative)
            guard files.fileExists(atPath: payload.path) else {
                throw BridgeError.message("Повреждён .pxr: отсутствует \(relative).")
            }
            return payload
        } catch {
            try? files.removeItem(at: root)
            throw error
        }
    }

    func isSourcePackage(_ package: URL) -> Bool {
        let root = files.temporaryDirectory.appendingPathComponent("pxr-inspect-\(UUID().uuidString)")
        defer { try? files.removeItem(at: root) }
        do {
            try files.createDirectory(at: root, withIntermediateDirectories: true)
            try runDitto(["-x", "-k", package.path, root.path])
            let data = try Data(contentsOf: root.appendingPathComponent("manifest.json"))
            let manifest = try JSONDecoder().decode(PXRManifest.self, from: data)
            return manifest.format == "com.phonexr.pxr" && manifest.kind == "unity-source"
        } catch {
            return false
        }
    }

    private func findUnityProject(under root: URL) -> URL? {
        if files.fileExists(atPath: root.appendingPathComponent("ProjectSettings/ProjectVersion.txt").path) { return root }
        return files.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey])?
            .compactMap { $0 as? URL }
            .first { $0.lastPathComponent == "ProjectSettings" && files.fileExists(atPath: $0.appendingPathComponent("ProjectVersion.txt").path) }?
            .deletingLastPathComponent()
    }

    private func runDitto(_ arguments: [String], directory: URL? = nil) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = arguments
        process.currentDirectoryURL = directory
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw BridgeError.message(String(decoding: data, as: UTF8.self))
        }
    }
}
