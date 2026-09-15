import Foundation

enum UnityBuildPlatform: String, CaseIterable, Identifiable {
    case android
    case ios
    var id: String { rawValue }
    var title: String { self == .android ? "Android APK" : "iPhone / Xcode" }
    var icon: String { self == .android ? "shippingbox.fill" : "iphone" }
}

final class UnityBridge: @unchecked Sendable {
    private let fileManager = FileManager.default

    func isUnitySource(_ source: URL) -> Bool {
        if ["zip", "pxr"].contains(source.pathExtension.lowercased()) {
            return (try? run(URL(fileURLWithPath: "/usr/bin/unzip"), ["-Z1", source.path]))?
                .contains("ProjectSettings/ProjectVersion.txt") == true
        }
        return fileManager.fileExists(atPath: source.appendingPathComponent("ProjectSettings/ProjectVersion.txt").path)
    }

    func build(source: URL, platform: UnityBuildPlatform, destination: URL) throws -> String {
        let editor = try findUnityEditor()
        try ensurePlatformSupport(editor: editor, platform: platform)
        let prepared = try prepareProject(source)
        defer { try? fileManager.removeItem(at: prepared) }
        try applyCompatibilityFixes(in: prepared)
        try installOpenXRPackages(in: prepared)
        try installBuildScript(in: prepared)

        let buildRoot = prepared.appendingPathComponent("PhoneXRBuild", isDirectory: true)
        let internalOutput = platform == .android
            ? buildRoot.appendingPathComponent("PhoneXR-OpenXR.apk")
            : buildRoot.appendingPathComponent("PhoneXR-iOS-Xcode", isDirectory: true)
        try fileManager.createDirectory(at: buildRoot, withIntermediateDirectories: true)

        let method = platform == .android ? "PhoneXRBuild.BuildAndroid" : "PhoneXRBuild.BuildIOS"
        let arguments = ["-batchmode", "-quit", "-accept-apiupdate", "-projectPath", prepared.path, "-executeMethod", method, "-logFile", "-"]
        let environment = ["PHONEXR_OUTPUT": internalOutput.path]
        let log: String
        do {
            log = try run(editor, arguments, environment: environment)
        } catch {
            // On the first build after adding OpenXR, Unity creates its settings
            // asset during preprocessing and intentionally asks for one rebuild.
            guard error.localizedDescription.contains("OpenXR Settings found in project but not yet loaded") else {
                throw error
            }
            log = try run(editor, arguments, environment: environment)
        }
        guard fileManager.fileExists(atPath: internalOutput.path) else {
            throw BridgeError.message("Unity завершился без результата.\n\n\(log.suffix(5000))")
        }

        if platform == .android {
            try? fileManager.removeItem(at: destination)
            try fileManager.copyItem(at: internalOutput, to: destination)
            return "Android OpenXR APK собран: \(destination.path)"
        }
        // The destination chosen in the UI is an output folder, so replacing an
        // older export is expected. Refusing it after Unity's long export loses
        // the completed temporary build and looks like a failed compilation.
        try? fileManager.removeItem(at: destination)
        try fileManager.copyItem(at: internalOutput, to: destination)
        return "Xcode-проект собран: \(destination.path). Откройте его в Xcode для подписи и установки на iPhone."
    }

    private func findUnityEditor() throws -> URL {
        let directCandidates = [
            fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Applications/Unity/Unity.app/Contents/MacOS/Unity"),
            URL(fileURLWithPath: "/Applications/Unity/Unity.app/Contents/MacOS/Unity")
        ]
        if let editor = directCandidates.first(where: { fileManager.isExecutableFile(atPath: $0.path) }) { return editor }

        let hubRoots = [
            fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Applications/Unity/Hub/Editor"),
            URL(fileURLWithPath: "/Applications/Unity/Hub/Editor")
        ]
        for root in hubRoots {
            let versions = (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
            for version in versions.sorted(by: { $0.lastPathComponent > $1.lastPathComponent }) {
                let editor = version.appendingPathComponent("Unity.app/Contents/MacOS/Unity")
                if fileManager.isExecutableFile(atPath: editor.path) { return editor }
            }
        }
        throw BridgeError.message("Unity Editor не найден. Установите Unity 2022.3 LTS.")
    }

    private func ensurePlatformSupport(editor: URL, platform: UnityBuildPlatform) throws {
        let appContents = editor.deletingLastPathComponent().deletingLastPathComponent()
        let standaloneRoot = appContents.deletingLastPathComponent().deletingLastPathComponent()
        let names = platform == .android ? ["AndroidPlayer"] : ["iOSSupport"]
        let roots = [
            appContents.appendingPathComponent("PlaybackEngines"),
            standaloneRoot.appendingPathComponent("PlaybackEngines")
        ]
        let installed = roots.contains { root in names.contains { fileManager.fileExists(atPath: root.appendingPathComponent($0).path) } }
        guard installed else {
            let module = platform == .android ? "Android Build Support" : "iOS Build Support"
            throw BridgeError.message("Не установлен \(module) для этой версии Unity. XR Bridge умеет автоматически скачать OpenXR-библиотеки, но платформенный модуль Unity ставится отдельно.")
        }
    }

    private func prepareProject(_ source: URL) throws -> URL {
        let extraction = fileManager.temporaryDirectory.appendingPathComponent("xrbridge-source-\(UUID().uuidString)")
        let sourceRoot: URL
        if ["zip", "pxr"].contains(source.pathExtension.lowercased()) {
            try fileManager.createDirectory(at: extraction, withIntermediateDirectories: true)
            _ = try run(URL(fileURLWithPath: "/usr/bin/ditto"), ["-x", "-k", source.path, extraction.path])
            guard let found = findProjectRoot(under: extraction) else {
                try? fileManager.removeItem(at: extraction)
                throw BridgeError.message("ZIP не содержит Unity-проект (ProjectSettings/ProjectVersion.txt).")
            }
            sourceRoot = found
        } else {
            sourceRoot = source
        }

        let working = fileManager.temporaryDirectory.appendingPathComponent("xrbridge-unity-\(UUID().uuidString)")
        try fileManager.createDirectory(at: working, withIntermediateDirectories: true)
        for name in ["Assets", "Packages", "ProjectSettings"] {
            let item = sourceRoot.appendingPathComponent(name)
            if fileManager.fileExists(atPath: item.path) {
                try fileManager.copyItem(at: item, to: working.appendingPathComponent(name))
            }
        }
        if ["zip", "pxr"].contains(source.pathExtension.lowercased()) { try? fileManager.removeItem(at: extraction) }
        guard fileManager.fileExists(atPath: working.appendingPathComponent("Assets").path),
              fileManager.fileExists(atPath: working.appendingPathComponent("ProjectSettings").path) else {
            try? fileManager.removeItem(at: working)
            throw BridgeError.message("Не найдены папки Assets и ProjectSettings.")
        }
        return working
    }

    private func findProjectRoot(under root: URL) -> URL? {
        if fileManager.fileExists(atPath: root.appendingPathComponent("ProjectSettings/ProjectVersion.txt").path) { return root }
        return fileManager.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey])?
            .compactMap { $0 as? URL }
            .first { $0.lastPathComponent == "ProjectSettings" && fileManager.fileExists(atPath: $0.appendingPathComponent("ProjectVersion.txt").path) }?
            .deletingLastPathComponent()
    }

    private func installOpenXRPackages(in project: URL) throws {
        let packages = project.appendingPathComponent("Packages", isDirectory: true)
        try fileManager.createDirectory(at: packages, withIntermediateDirectories: true)
        let manifest = packages.appendingPathComponent("manifest.json")
        var root = ((try? Data(contentsOf: manifest))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }) ?? [:]
        var dependencies = root["dependencies"] as? [String: String] ?? [:]
        dependencies["com.unity.xr.management"] = "4.4.0"
        dependencies["com.unity.xr.openxr"] = "1.13.2"
        root["dependencies"] = dependencies
        let data = try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: manifest, options: .atomic)
    }

    private func applyCompatibilityFixes(in project: URL) throws {
        // Unity 2018 projects often contain these removed legacy UI types in bundled
        // test/debug scripts. Update only the temporary build copy, never the source.
        let assets = project.appendingPathComponent("Assets", isDirectory: true)
        guard let files = fileManager.enumerator(
            at: assets,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        for case let file as URL in files where file.pathExtension.lowercased() == "cs" {
            let path = file.path.replacingOccurrences(of: "\\", with: "/")
            if path.contains("/3rd_PartyAssets/SteamVR/Editor/") ||
                path.contains("/3rd_PartyAssets/VRTK/Source/Editor/") {
                try fileManager.moveItem(at: file, to: file.appendingPathExtension("disabled"))
                continue
            }
            guard var source = try? String(contentsOf: file, encoding: .utf8) else { continue }
            let original = source
            source = source.replacingOccurrences(of: "GUIText", with: "UnityEngine.UI.Text")
            source = source.replacingOccurrences(of: ".gameObject.active = false;", with: ".gameObject.SetActive(false);")
            source = source.replacingOccurrences(of: ".gameObject.active = true;", with: ".gameObject.SetActive(true);")
            source = source.replacingOccurrences(of: "XRDevice.model", with: "SystemInfo.deviceModel")
            source = source.replacingOccurrences(of: "XRDevice.isPresent", with: "XRSettings.isDeviceActive")
            if source != original {
                try source.write(to: file, atomically: true, encoding: .utf8)
            }
        }
    }

    private func installBuildScript(in project: URL) throws {
        let editorFolder = project.appendingPathComponent("Assets/Editor", isDirectory: true)
        try fileManager.createDirectory(at: editorFolder, withIntermediateDirectories: true)
        let script = #"""
using System;
using System.Linq;
using System.Reflection;
using UnityEngine;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.XR.Management.Metadata;
using UnityEditor.XR.Management;
using UnityEngine.XR.Management;

public static class PhoneXRBuild {
    static string Output() {
        var path = Environment.GetEnvironmentVariable("PHONEXR_OUTPUT");
        if (String.IsNullOrEmpty(path)) throw new Exception("PHONEXR_OUTPUT is missing");
        return path;
    }

    static string[] Scenes() {
        var scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();
        if (scenes.Length == 0) throw new Exception("В Build Settings нет включённых сцен");
        return scenes;
    }

    public static void BuildAndroid() {
        EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
        PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
        EnableOpenXR(BuildTargetGroup.Android);
        Build(Scenes(), Output(), BuildTarget.Android);
    }

    static void EnableOpenXR(BuildTargetGroup group) {
        var settings = XRGeneralSettingsPerBuildTarget.XRGeneralSettingsForBuildTarget(group);
        if (settings == null) throw new Exception("XR Plug-in Management settings are missing for " + group);
        if (settings.AssignedSettings == null) {
            settings.AssignedSettings = ScriptableObject.CreateInstance<XRManagerSettings>();
            EditorUtility.SetDirty(settings);
        }
        if (!XRPackageMetadataStore.AssignLoader(settings.AssignedSettings, "UnityEngine.XR.OpenXR.OpenXRLoader", group))
            throw new Exception("Could not enable the OpenXR loader for " + group);
        AssetDatabase.SaveAssets();
    }

    public static void BuildIOS() {
        EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.iOS, BuildTarget.iOS);
        PlayerSettings.SetScriptingBackend(BuildTargetGroup.iOS, ScriptingImplementation.IL2CPP);
        PlayerSettings.bundleVersion = "1.0.0";
        PlayerSettings.iOS.buildNumber = "1";
        PlayerSettings.iOS.sdkVersion = iOSSdkVersion.DeviceSDK;
        PlayerSettings.iOS.targetOSVersionString = "15.0";
        Build(Scenes(), Output(), BuildTarget.iOS);
    }

    static void Build(string[] scenes, string output, BuildTarget target) {
        PreloadOpenXRSettings();
        var report = BuildPipeline.BuildPlayer(scenes, output, target, BuildOptions.None);
        if (report.summary.result != BuildResult.Succeeded)
            throw new Exception("Unity build failed: " + report.summary.result);
    }

    static void PreloadOpenXRSettings() {
        var type = AppDomain.CurrentDomain.GetAssemblies()
            .Select(assembly => assembly.GetType("UnityEditor.XR.OpenXR.OpenXRPackageSettings"))
            .FirstOrDefault(candidate => candidate != null);
        var method = type?.GetMethod("GetOrCreateInstance", BindingFlags.Public | BindingFlags.Static);
        method?.Invoke(null, null);
        AssetDatabase.SaveAssets();
    }
}
"""#
        try script.write(to: editorFolder.appendingPathComponent("PhoneXRBuild.cs"), atomically: true, encoding: .utf8)

        let iosRuntime = #"""
using System.Collections;
using System.Linq;
using UnityEngine;
using UnityEngine.SceneManagement;

#if UNITY_IOS && !UNITY_EDITOR
public sealed class PhoneXRIOSBootstrap : MonoBehaviour {
    Camera leftEye;
    Camera rightEye;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
    static void Install() {
        var host = new GameObject("PhoneXR iOS Runtime");
        DontDestroyOnLoad(host);
        host.AddComponent<PhoneXRIOSBootstrap>();
    }

    IEnumerator Start() {
        Screen.sleepTimeout = SleepTimeout.NeverSleep;
        Screen.orientation = ScreenOrientation.LandscapeLeft;
        if (SystemInfo.supportsGyroscope) Input.gyro.enabled = true;
        SceneManager.sceneLoaded += OnSceneLoaded;
        yield return new WaitForSeconds(0.5f);
        SetupCameras();
    }

    void OnDestroy() { SceneManager.sceneLoaded -= OnSceneLoaded; }
    void OnSceneLoaded(Scene scene, LoadSceneMode mode) { StartCoroutine(SetupNextFrame()); }
    IEnumerator SetupNextFrame() { yield return null; yield return new WaitForSeconds(0.25f); SetupCameras(); }

    void SetupCameras() {
        var cameras = Resources.FindObjectsOfTypeAll<Camera>()
            .Where(c => c.gameObject.scene.IsValid() && c != rightEye)
            .OrderByDescending(c => c.CompareTag("MainCamera"))
            .ThenByDescending(c => c.name.ToLowerInvariant().Contains("eye") || c.name.ToLowerInvariant().Contains("head"))
            .ToArray();
        leftEye = cameras.FirstOrDefault();
        if (leftEye == null) {
            var fallback = new GameObject("PhoneXR Fallback Camera");
            fallback.transform.position = new Vector3(0f, 1.6f, -3f);
            leftEye = fallback.AddComponent<Camera>();
            leftEye.clearFlags = CameraClearFlags.Skybox;
        }
        ActivateHierarchy(leftEye.transform);
        leftEye.enabled = true;
        leftEye.targetTexture = null;
        leftEye.cullingMask = ~0;
        leftEye.rect = new Rect(0f, 0f, 0.5f, 1f);

        if (rightEye == null) {
            var right = new GameObject("PhoneXR Right Eye");
            right.transform.SetParent(leftEye.transform.parent, false);
            right.transform.localPosition = leftEye.transform.localPosition + Vector3.right * 0.064f;
            right.transform.localRotation = leftEye.transform.localRotation;
            rightEye = right.AddComponent<Camera>();
        }
        rightEye.CopyFrom(leftEye);
        rightEye.enabled = true;
        rightEye.targetTexture = null;
        rightEye.rect = new Rect(0.5f, 0f, 0.5f, 1f);
        rightEye.transform.localPosition = leftEye.transform.localPosition + Vector3.right * 0.064f;
    }

    static void ActivateHierarchy(Transform item) {
        for (var current = item; current != null; current = current.parent)
            current.gameObject.SetActive(true);
    }

    void LateUpdate() {
        if (leftEye == null || !Input.gyro.enabled) return;
        var q = Input.gyro.attitude;
        leftEye.transform.localRotation = new Quaternion(-q.x, -q.y, q.z, q.w) * Quaternion.Euler(90f, 0f, 0f);
        if (rightEye != null) rightEye.transform.localRotation = leftEye.transform.localRotation;
    }
}
#endif
"""#
        try iosRuntime.write(to: editorFolder.appendingPathComponent("PhoneXRIOSBootstrap.cs"), atomically: true, encoding: .utf8)
    }

    private func run(_ executable: URL, _ arguments: [String], environment extra: [String: String] = [:]) throws -> String {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        var environment = ProcessInfo.processInfo.environment
        extra.forEach { environment[$0.key] = $0.value }
        process.environment = environment
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let output = String(decoding: data, as: UTF8.self)
        guard process.terminationStatus == 0 else { throw BridgeError.message(output.suffix(6000).description) }
        return output
    }
}
