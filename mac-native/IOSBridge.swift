import Foundation

final class IOSBridge: @unchecked Sendable {
    private let xcrun = URL(fileURLWithPath: "/usr/bin/xcrun")

    func devices() throws -> [IOSDevice] {
        let jsonURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("xrbridge-ios-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: jsonURL) }
        _ = try run(["devicectl", "list", "devices", "--json-output", jsonURL.path])
        let data = try Data(contentsOf: jsonURL)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = root["result"] as? [String: Any],
              let items = result["devices"] as? [[String: Any]] else {
            throw BridgeError.message("Xcode вернул неизвестный формат списка устройств.")
        }
        return items.compactMap { item in
            guard let hardware = item["hardwareProperties"] as? [String: Any],
                  (hardware["platform"] as? String) == "iOS",
                  let identifier = item["identifier"] as? String else { return nil }
            let properties = item["deviceProperties"] as? [String: Any]
            let connection = item["connectionProperties"] as? [String: Any]
            return IOSDevice(
                identifier: identifier,
                name: properties?["name"] as? String ?? "iPhone",
                model: hardware["marketingName"] as? String ?? "iPhone",
                pairingState: connection?["pairingState"] as? String ?? "unknown",
                tunnelState: connection?["tunnelState"] as? String ?? "unknown"
            )
        }
    }

    func copyToLiveContainer(ipa: URL, deviceIdentifier: String) throws -> String {
        let apps = try run(["devicectl", "device", "info", "apps", "--device", deviceIdentifier])
        guard let range = apps.range(of: #"LiveContainer\s+(com\.[A-Za-z0-9._-]+)"#, options: .regularExpression) else {
            throw BridgeError.message("LiveContainer не найден на iPhone. Сначала установите или обновите LiveContainer.")
        }
        let row = String(apps[range])
        guard let bundleID = row.split(whereSeparator: { $0 == " " || $0 == "\t" }).last.map(String.init) else {
            throw BridgeError.message("Не удалось определить bundle ID LiveContainer.")
        }
        _ = try run([
            "devicectl", "device", "copy", "to",
            "--device", deviceIdentifier,
            "--source", ipa.path,
            "--destination", "Documents/PhoneXR-iOS-1.1.ipa",
            "--domain-type", "appDataContainer",
            "--domain-identifier", bundleID
        ])
        return "PhoneXR-iOS-1.1.ipa передан. Откройте LiveContainer, нажмите + и выберите новый файл PhoneXR-iOS-1.1.ipa."
    }

    private func run(_ arguments: [String]) throws -> String {
        let process = Process()
        process.executableURL = xcrun
        process.arguments = arguments
        var environment = ProcessInfo.processInfo.environment
        environment["DEVELOPER_DIR"] = "/Applications/Xcode.app/Contents/Developer"
        process.environment = environment
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let output = String(decoding: data, as: UTF8.self)
        guard process.terminationStatus == 0 else {
            throw BridgeError.message(output.suffix(4000).description)
        }
        return output
    }
}
