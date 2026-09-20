import Foundation

struct PackageReport: Codable {
    let title: String
    let details: [String]
    let isAndroidAPK: Bool
    let hasOpenXR: Bool
    let canLaunchNatively: Bool
}

enum APKAnalyzer {
    static func inspect(_ url: URL) throws -> PackageReport {
        let ext = url.pathExtension.lowercased()
        guard ext == "apk" || ext == "zip" || ext == "pxr" else {
            return PackageReport(
                title: url.deletingPathExtension().lastPathComponent,
                details: ["Формат сохранён в библиотеке LostXR."],
                isAndroidAPK: false,
                hasOpenXR: false,
                canLaunchNatively: false
            )
        }

        let entries = try zipEntryNames(url)
        if ext == "pxr" {
            let normalized = entries.map { $0.hasPrefix("./") ? String($0.dropFirst(2)) : $0 }
            let valid = normalized.contains("manifest.json")
            let hasAndroid = normalized.contains("payload/android/game.apk")
            let hasIOS = normalized.contains("payload/ios/game.ipa")
            let hasUnitySource = normalized.contains("source/ProjectSettings/ProjectVersion.txt")
            guard valid else { throw ImportError.invalidArchive }
            return PackageReport(
                title: url.deletingPathExtension().lastPathComponent,
                details: hasUnitySource ? [
                    "LostXR Source Package v2",
                    "Unity-исходник включён",
                    "Соберите Android или iPhone версию через XR Bridge на Mac"
                ] : [
                    "LostXR Package v1",
                    hasAndroid ? "Android APK включён" : "Android APK отсутствует",
                    hasIOS ? "iPhone IPA включён" : "iPhone IPA отсутствует",
                    "IPA устанавливается через LiveContainer"
                ],
                isAndroidAPK: false,
                hasOpenXR: hasAndroid || hasIOS || hasUnitySource,
                canLaunchNatively: false
            )
        }
        let hasManifest = entries.contains("AndroidManifest.xml")
        let hasLoader = entries.contains { $0.hasSuffix("/libopenxr_loader.so") }
        let hasQuestPlugin = entries.contains {
            let name = $0.lowercased()
            return name.contains("oculus") || name.contains("openxr_meta") || name.contains("godotopenxrmeta")
        }
        let nativeLibraries = entries.filter { $0.hasPrefix("lib/arm64-v8a/") && $0.hasSuffix(".so") }.count

        var details = [String]()
        details.append(hasManifest ? "Android APK найден" : "ZIP не содержит AndroidManifest.xml")
        details.append(hasLoader ? "OpenXR loader найден" : "OpenXR loader не найден")
        if hasQuestPlugin { details.append("Найдены зависимости Meta/Oculus") }
        details.append("ARM64 Android-библиотек: \(nativeLibraries)")
        details.append("Для запуска на iPhone нужен исходный код и сборка под Metal/iOS")

        return PackageReport(
            title: url.deletingPathExtension().lastPathComponent,
            details: details,
            isAndroidAPK: hasManifest,
            hasOpenXR: hasLoader || hasQuestPlugin,
            canLaunchNatively: false
        )
    }

    private static func zipEntryNames(_ url: URL) throws -> [String] {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count >= 22 else { throw ImportError.invalidArchive }
        let signature: UInt32 = 0x06054b50
        let floor = max(0, data.count - 65_557)
        var eocd: Int?
        var cursor = data.count - 22
        while cursor >= floor {
            if u32(data, cursor) == signature { eocd = cursor; break }
            cursor -= 1
        }
        guard let end = eocd else { throw ImportError.invalidArchive }
        let count = Int(u16(data, end + 10))
        var offset = Int(u32(data, end + 16))
        var names = [String]()
        names.reserveCapacity(count)
        for _ in 0..<count {
            guard offset + 46 <= data.count, u32(data, offset) == 0x02014b50 else { break }
            let nameLength = Int(u16(data, offset + 28))
            let extraLength = Int(u16(data, offset + 30))
            let commentLength = Int(u16(data, offset + 32))
            let start = offset + 46
            guard start + nameLength <= data.count else { break }
            if let name = String(data: data[start..<(start + nameLength)], encoding: .utf8) { names.append(name) }
            offset = start + nameLength + extraLength + commentLength
        }
        return names
    }

    private static func u16(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(data[offset]) | UInt16(data[offset + 1]) << 8
    }

    private static func u32(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(data[offset]) | UInt32(data[offset + 1]) << 8 |
            UInt32(data[offset + 2]) << 16 | UInt32(data[offset + 3]) << 24
    }

    enum ImportError: LocalizedError {
        case invalidArchive
        var errorDescription: String? { "Повреждённый или неподдерживаемый архив" }
    }
}
