import SwiftUI
import AppKit
import UniformTypeIdentifiers

@main
struct XRBridgeApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup("XR Bridge") {
            ContentView().environmentObject(model)
                .frame(minWidth: 920, minHeight: 660)
        }
        .windowStyle(.titleBar)
        .defaultSize(width: 1060, height: 760)
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationSplitView {
            List(selection: $model.section) {
                Section("XR Bridge") {
                    ForEach(AppSection.allCases) { section in
                        Label(section.title, systemImage: section.icon)
                            .tag(section)
                    }
                }
            }
            .navigationTitle("XR Bridge")
            .navigationSplitViewColumnWidth(min: 190, ideal: 220, max: 260)
            .onChange(of: model.section) { _ in model.refreshDevices() }
            .toolbar {
                Button(action: model.refreshDevices) {
                    Label("Обновить", systemImage: "arrow.clockwise")
                }
                .disabled(model.section == .build)
            }
        } detail: {
            switch model.section {
            case .build: sourceDetail
            case .package: pxrDetail
            case .android: androidDetail
            case .iphone: iphoneDetail
            }
        }
    }

    private var sourceDetail: some View {
        page(title: "Сборка из исходников", subtitle: "Создайте APK для Android или Xcode-проект для iPhone независимо от подключённых устройств.") {
            sourceSection
            statusSection
        }
        .navigationTitle("Сборка")
    }

    private var pxrDetail: some View {
        page(title: "Универсальный .pxr", subtitle: "Один пакет PhoneXR с отдельными сборками для Android и iPhone.") {
            GroupBox("Source PXR — один Unity-код для двух платформ") {
                VStack(alignment: .leading, spacing: 12) {
                    packageFileRow(icon: "curlybraces.square.fill", title: "Unity-проект или ZIP", url: model.pxrSourceURL) {
                        model.choosePXRSource()
                    }
                    HStack {
                        Text("Содержит Assets, Packages и ProjectSettings. Потом выберите этот .pxr во вкладке «Сборка».")
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { model.createSourcePXR() } label: {
                            Label("Создать Source .pxr", systemImage: "hammer.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.pxrSourceURL == nil || model.isBusy)
                    }
                }
                .padding(8)
            }
            GroupBox("Файлы игры") {
                VStack(spacing: 12) {
                    packageFileRow(icon: "shippingbox.fill", title: "Android APK", url: model.pxrAndroidURL) {
                        model.choosePXRAndroid()
                    }
                    Divider()
                    packageFileRow(icon: "iphone", title: "iPhone IPA", url: model.pxrIOSURL) {
                        model.choosePXRIOS()
                    }
                }
                .padding(8)
            }
            GroupBox {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("PhoneXR Package v1").font(.headline)
                        Text("На каждом телефоне будет выбран только совместимый payload.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { model.createPXR() } label: {
                        Label("Создать .pxr", systemImage: "archivebox.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!model.canCreatePXR)
                }
                .padding(8)
            }
            GroupBox("Установить готовый .pxr") {
                VStack(alignment: .leading, spacing: 12) {
                    packageFileRow(icon: "archivebox.fill", title: "PXR пакет", url: model.pxrPackageURL) {
                        model.choosePXRPackage()
                    }
                    Divider()
                    HStack(spacing: 12) {
                        Picker("Android", selection: $model.selectedSerial) {
                            Text("Android не выбран").tag(nil as String?)
                            ForEach(model.devices) { device in
                                Text(device.model).tag(device.serial as String?)
                            }
                        }
                        Button(model.pxrPackageIsSource ? "Собрать для Android" : "Установить на Android") { model.installPXRAndroid() }
                            .disabled(!model.canInstallPXRAndroid)
                    }
                    HStack(spacing: 12) {
                        Picker("iPhone", selection: $model.selectedIOSIdentifier) {
                            Text("iPhone не выбран").tag(nil as String?)
                            ForEach(model.iosDevices) { device in
                                Text(device.name).tag(device.identifier as String?)
                            }
                        }
                        Button(model.pxrPackageIsSource ? "Собрать для iPhone" : "Передать на iPhone") { model.installPXRIOS() }
                            .disabled(!model.canInstallPXRIOS)
                    }
                    HStack {
                        Text(model.pxrPackageIsSource
                             ? "Source PXR сначала собирается Unity в Xcode-проект. Телефон для этого не нужен."
                             : "Готовый iPhone-пакет открывается через LiveContainer.")
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { model.refreshPXRDevices() } label: {
                            Label("Обновить устройства", systemImage: "arrow.clockwise")
                        }
                    }
                }
                .padding(8)
            }
            statusSection
        }
        .navigationTitle("PXR")
    }

    private func packageFileRow(icon: String, title: String, url: URL?, action: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title2).foregroundStyle(.blue).frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(url?.lastPathComponent ?? "Не выбран")
                    .font(.callout).foregroundStyle(.secondary)
                    .lineLimit(1).truncationMode(.middle)
            }
            Spacer()
            Button("Выбрать…", action: action)
        }
    }

    private var androidDetail: some View {
        page(title: "Android", subtitle: "Cardboard Hands, OpenXR Runtime и установка APK по кабелю или через файл.") {
            androidDeviceSection
            installMethod
            companionSection
            openXRSection
            instructions
            statusSection
        }
        .navigationTitle("Android")
    }

    private var iphoneDetail: some View {
        page(title: "iPhone", subtitle: "Передача PhoneXR в LiveContainer по кабелю.") {
                iphoneDeviceSection
                GroupBox {
                    HStack(spacing: 18) {
                        Image(systemName: "visionpro.fill").font(.system(size: 42)).foregroundStyle(.blue)
                        VStack(alignment: .leading, spacing: 5) {
                            Text("PhoneXR iOS").font(.title3.bold())
                            Text("Камера, Vision-трекинг двух рук и библиотека пакетов.")
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Передать в LiveContainer") { model.installIOSCompanion() }
                            .buttonStyle(.borderedProminent)
                            .disabled(!model.canTransferIOS)
                    }
                    .padding(8)
                }
                GroupBox("Как запустить") {
                    VStack(alignment: .leading, spacing: 9) {
                        Label("Разблокируйте iPhone и подключите его data-кабелем.", systemImage: "1.circle.fill")
                        Label("Нажмите «Передать в LiveContainer».", systemImage: "2.circle.fill")
                        Label("Откройте LiveContainer, нажмите + и выберите PhoneXR-iOS-1.1.ipa.", systemImage: "3.circle.fill")
                        Text("Android/Quest APK можно импортировать для проверки, но iOS не исполняет Android APK напрямую.")
                            .font(.callout).foregroundStyle(.orange).padding(.top, 4)
                    }
                    .padding(8)
                }
                statusSection
        }
        .navigationTitle("iPhone")
    }

    private func page<Content: View>(title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(title).font(.largeTitle.bold())
                    Text(subtitle).font(.title3).foregroundStyle(.secondary)
                }
                .padding(.bottom, 4)
                content()
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 28)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .top)
        }
    }

    private var installMethod: some View {
        GroupBox("Способ установки") {
            Picker("", selection: $model.installMethod) {
                ForEach(InstallMethod.allCases) { method in
                    Label(method.title, systemImage: method.icon).tag(method)
                }
            }
            .pickerStyle(.segmented)
            .padding(8)
        }
    }

    private var androidDeviceSection: some View {
        GroupBox("Устройство") {
            HStack(spacing: 12) {
                Picker("Android", selection: $model.selectedSerial) {
                    Text("Не выбрано").tag(nil as String?)
                    ForEach(model.devices) { device in
                        Text("\(device.model) — \(device.stateLabel)").tag(device.serial as String?)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: .infinity)
                Button { model.refreshDevices() } label: {
                    Label("Обновить", systemImage: "arrow.clockwise")
                }
            }
            .padding(8)
        }
    }

    private var iphoneDeviceSection: some View {
        GroupBox("Устройство") {
            HStack(spacing: 12) {
                Picker("iPhone", selection: $model.selectedIOSIdentifier) {
                    Text("Не выбрано").tag(nil as String?)
                    ForEach(model.iosDevices) { device in
                        Text("\(device.name) — \(device.stateLabel)").tag(device.identifier as String?)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: .infinity)
                Button { model.refreshDevices() } label: {
                    Label("Обновить", systemImage: "arrow.clockwise")
                }
            }
            .padding(8)
        }
    }

    private var companionSection: some View {
        GroupBox {
            HStack(spacing: 18) {
                Image(systemName: "hand.raised.fill")
                    .font(.system(size: 42)).foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 5) {
                    Text("Cardboard Hands").font(.title3.bold())
                    Text("Камера, отдельные области глаз, трекинг рук и пространственное меню.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(model.installMethod == .adb ? "Установить" : "Сохранить APK") {
                    model.installCompanion()
                }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.canRun)
            }
            .padding(8)
        }
    }

    private var sourceSection: some View {
        VStack(alignment: .leading, spacing: 18) {
            GroupBox("Платформа") {
                Picker("Платформа сборки", selection: $model.buildPlatform) {
                    ForEach(UnityBuildPlatform.allCases) { platform in
                        Label(platform.title, systemImage: platform.icon).tag(platform)
                    }
                }
                .pickerStyle(.segmented)
                .padding(8)
                .onChange(of: model.buildPlatform) { _ in model.buildPlatformChanged() }
            }

            GroupBox("Исходный проект") {
                HStack {
                    Image(systemName: model.sourceURL == nil ? "shippingbox" : "shippingbox.fill")
                        .font(.title2)
                        .foregroundColor(model.sourceURL == nil ? Color.secondary : Color.blue)
                    Text(model.sourceURL?.path(percentEncoded: false) ?? "Выберите Unity/Gradle проект или ZIP")
                        .lineLimit(2).truncationMode(.middle)
                    Spacer()
                    Button("Выбрать…") { model.chooseSource() }
                }
                .padding(8)
            }

            if model.buildPlatform == .android {
                GroupBox("Результат Android") {
                    VStack(alignment: .leading, spacing: 12) {
                        Picker("Действие", selection: $model.installMethod) {
                            ForEach(InstallMethod.allCases) { method in
                                Label(method.title, systemImage: method.icon).tag(method)
                            }
                        }
                        .pickerStyle(.segmented)
                        .onChange(of: model.installMethod) { method in
                            if method == .adb { model.refreshAndroidForBuild() }
                        }

                        if model.installMethod == .adb {
                            HStack(spacing: 12) {
                                Picker("Телефон", selection: $model.selectedSerial) {
                                    Text("Android не выбран").tag(nil as String?)
                                    ForEach(model.devices) { device in
                                        Text("\(device.model) — \(device.stateLabel)").tag(device.serial as String?)
                                    }
                                }
                                Button { model.refreshAndroidForBuild() } label: {
                                    Image(systemName: "arrow.clockwise")
                                }
                                .help("Обновить список Android-устройств")
                            }
                        }
                    }
                    .padding(8)
                }
            }

            GroupBox {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(model.buildPlatform == .ios ? "Xcode-проект для iPhone" : "Android APK")
                            .font(.headline)
                        Text(model.buildHint)
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        model.buildAndInstall()
                    } label: {
                        Label(
                            model.buildButtonTitle,
                            systemImage: "hammer.fill"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!model.canBuildSource)
                }
                .padding(8)
            }

            Text("Unity-проекты распознаются по папкам Assets и ProjectSettings. Android Gradle-проекты можно собрать только в APK. Исходный проект не изменяется.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var openXRSection: some View {
        GroupBox("Quest / OpenXR APK — экспериментально") {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 16) {
                    Image(systemName: "visionpro").font(.system(size: 36)).foregroundStyle(.blue)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Monado OpenXR Runtime").font(.title3.bold())
                        Text("Лёгкий Android-рантайм с Cardboard и датчиками телефона; Unity не нужен.")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(model.installMethod == .adb ? "Установить Runtime" : "Сохранить Runtime") {
                        model.installRuntime()
                    }
                    .buttonStyle(.bordered)
                    .disabled(!model.canRun)
                }
                HStack {
                    Text("Для выбора активного рантайма Android нужен Khronos Runtime Broker.")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Link("Открыть в Google Play", destination: URL(string: "https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker")!)
                        .font(.caption)
                }
                Divider()
                HStack {
                    Image(systemName: model.questApkURL == nil ? "app.dashed" : "app.fill")
                    Text(model.questApkURL?.lastPathComponent ?? "Выберите APK игры")
                        .lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Button("Выбрать APK…") { model.chooseQuestApk() }
                    Button(model.installMethod == .adb ? "Runtime + игра" : "Сохранить комплект") {
                        model.installQuestApk()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.canRun || model.questApkURL == nil)
                }
                Text("Работают только APK для Android OpenXR, которым хватает датчиков телефона. Игры с обязательными Meta Quest API, DRM, контроллерами или 6DoF могут не запуститься.")
                    .font(.caption).foregroundStyle(.orange)
            }
            .padding(8)
        }
    }

    private var instructions: some View {
        GroupBox("Перед началом") {
            VStack(alignment: .leading, spacing: 8) {
                if model.installMethod == .adb {
                    Label("Включите «Для разработчиков → Отладка по USB» на телефоне.", systemImage: "1.circle.fill")
                    Label("Подключите кабель и подтвердите RSA-ключ на телефоне.", systemImage: "2.circle.fill")
                } else {
                    Label("Соберите и сохраните APK на Mac.", systemImage: "1.circle.fill")
                    Label("Скопируйте APK в Download телефона через передачу файлов.", systemImage: "2.circle.fill")
                }
                Label("В папке проекта должен находиться файл gradlew.", systemImage: "3.circle.fill")
                Text("Unity и PCVR EXE не используются. Для Quest APK сначала установите Runtime и один раз откройте Monado XR. Трекинг рук Cardboard Hands пока не заменяет Meta Quest Hand Tracking внутри чужих игр.")
                    .font(.callout).foregroundStyle(.orange).padding(.top, 4)
            }
            .padding(8)
        }
    }

    private var statusSection: some View {
        GroupBox("Журнал") {
            VStack(alignment: .leading, spacing: 10) {
                if model.isBusy { ProgressView().controlSize(.small) }
                Text(model.status).font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(8)
        }
    }
}

struct Device: Identifiable, Hashable {
    var id: String { serial }
    let serial: String
    let model: String
    let state: String
    var isReady: Bool { state == "device" }
    var stateLabel: String {
        switch state {
        case "device": return "Подключён"
        case "unauthorized": return "Подтвердите RSA на телефоне"
        case "offline": return "ADB offline — переподключите кабель"
        default: return state
        }
    }
}

enum AppSection: String, CaseIterable, Identifiable {
    case build
    case package
    case android
    case iphone
    var id: String { rawValue }
    var title: String {
        switch self {
        case .build: return "Сборка"
        case .package: return "Пакеты .pxr"
        case .android: return "Android"
        case .iphone: return "iPhone"
        }
    }
    var icon: String {
        switch self {
        case .build: return "hammer.fill"
        case .package: return "archivebox.fill"
        case .android: return "cable.connector"
        case .iphone: return "iphone"
        }
    }
}

struct IOSDevice: Identifiable, Hashable {
    let identifier: String
    let name: String
    let model: String
    let pairingState: String
    let tunnelState: String
    var id: String { identifier }
    var isReady: Bool { pairingState == "paired" }
    var stateLabel: String {
        isReady ? "\(model) · подключён" : "Разблокируйте и подтвердите доверие"
    }
}

enum InstallMethod: String, CaseIterable, Identifiable {
    case adb
    case fileTransfer
    var id: String { rawValue }
    var title: String { self == .adb ? "ADB автоматически" : "Передача файлов" }
    var icon: String { self == .adb ? "cable.connector" : "folder" }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var section: AppSection = .build
    @Published var devices: [Device] = []
    @Published var selectedSerial: String?
    @Published var iosDevices: [IOSDevice] = []
    @Published var selectedIOSIdentifier: String?
    @Published var sourceURL: URL?
    @Published var questApkURL: URL?
    @Published var pxrAndroidURL: URL?
    @Published var pxrIOSURL: URL?
    @Published var pxrPackageURL: URL?
    @Published var pxrSourceURL: URL?
    @Published var pxrPackageIsSource = false
    @Published var status = "Выберите исходный проект и платформу сборки."
    @Published var isBusy = false
    @Published var installMethod: InstallMethod = .adb
    @Published var buildPlatform: UnityBuildPlatform = .android

    private let bridge = AndroidBridge()
    private let iosBridge = IOSBridge()
    private let unityBridge = UnityBridge()
    private let pxrBridge = PXRBridge()
    var canRun: Bool {
        !isBusy && (installMethod == .fileTransfer || devices.first(where: { $0.serial == selectedSerial })?.isReady == true)
    }
    var canTransferIOS: Bool {
        !isBusy && iosDevices.first(where: { $0.identifier == selectedIOSIdentifier })?.isReady == true
    }
    var canBuildSource: Bool {
        guard !isBusy, sourceURL != nil else { return false }
        if buildPlatform == .ios { return true }
        if installMethod == .fileTransfer { return true }
        return devices.first(where: { $0.serial == selectedSerial })?.isReady == true
    }
    var canCreatePXR: Bool {
        !isBusy && pxrAndroidURL != nil && pxrIOSURL != nil
    }
    var canInstallPXRAndroid: Bool {
        !isBusy && pxrPackageURL != nil && (pxrPackageIsSource || devices.first(where: { $0.serial == selectedSerial })?.isReady == true)
    }
    var canInstallPXRIOS: Bool {
        !isBusy && pxrPackageURL != nil && (pxrPackageIsSource || iosDevices.first(where: { $0.identifier == selectedIOSIdentifier })?.isReady == true)
    }
    var buildButtonTitle: String {
        if buildPlatform == .ios { return "Собрать Xcode-проект" }
        return installMethod == .adb ? "Собрать и установить" : "Собрать APK"
    }
    var buildHint: String {
        if buildPlatform == .ios {
            return "Android и подключённый телефон не требуются. После экспорта откройте проект в Xcode."
        }
        return installMethod == .adb
            ? "APK будет собран и установлен на выбранный Android."
            : "APK будет сохранён в выбранную папку без подключения телефона."
    }

    init() { refreshDevices() }

    func refreshDevices() {
        switch section {
        case .build:
            if buildPlatform == .android && installMethod == .adb { refreshAndroidDevices() }
        case .package:
            break
        case .android:
            refreshAndroidDevices()
        case .iphone:
            refreshIOSDevices()
        }
    }

    func buildPlatformChanged() {
        if buildPlatform == .android && installMethod == .adb && devices.isEmpty {
            refreshAndroidDevices()
        }
    }

    func refreshAndroidForBuild() {
        refreshAndroidDevices()
    }

    private func refreshAndroidDevices() {
        work("Поиск устройств…") { bridge in
            let found = try bridge.devices()
            let message: String
            if found.isEmpty {
                message = "ADB работает, но USB-устройство отсутствует. Разблокируйте телефон, выберите USB → Передача файлов и переподключите data-кабель."
            } else if let blocked = found.first(where: { !$0.isReady }) {
                message = blocked.stateLabel
            } else {
                message = "Телефон подключён и готов."
            }
            return (found, message)
        } completion: { [weak self] result in
            self?.devices = result.0
            if self?.selectedSerial == nil { self?.selectedSerial = result.0.first?.serial }
            self?.status = result.1
        }
    }

    private func refreshIOSDevices() {
        isBusy = true
        status = "Поиск iPhone…"
        let iosBridge = iosBridge
        Task.detached {
            do {
                let found = try iosBridge.devices()
                await MainActor.run {
                    self.isBusy = false
                    self.iosDevices = found
                    if self.selectedIOSIdentifier == nil { self.selectedIOSIdentifier = found.first?.identifier }
                    self.status = found.isEmpty
                        ? "iPhone не найден. Разблокируйте его, подключите кабель и подтвердите доверие."
                        : "iPhone найден. Можно передать PhoneXR в LiveContainer."
                }
            } catch {
                await MainActor.run {
                    self.isBusy = false
                    self.status = "Ошибка поиска iPhone: \(error.localizedDescription)"
                }
            }
        }
    }

    func installIOSCompanion() {
        guard let identifier = selectedIOSIdentifier else { return }
        guard let ipa = Bundle.main.url(forResource: "PhoneXR-iOS", withExtension: "ipa") else {
            status = "Ошибка: PhoneXR-iOS.ipa отсутствует внутри XR Bridge."
            return
        }
        isBusy = true
        status = "Передача PhoneXR на iPhone…"
        let iosBridge = iosBridge
        Task.detached {
            do {
                let message = try iosBridge.copyToLiveContainer(ipa: ipa, deviceIdentifier: identifier)
                await MainActor.run { self.isBusy = false; self.status = message }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка: \(error.localizedDescription)" }
            }
        }
    }

    func chooseSource() {
        let panel = NSOpenPanel()
        panel.title = "Unity/Gradle проект или ZIP с исходным кодом"
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.folder, .zip, UTType(filenameExtension: "pxr")!]
        if panel.runModal() == .OK { sourceURL = panel.url }
    }

    func chooseQuestApk() {
        let panel = NSOpenPanel()
        panel.title = "APK OpenXR-игры"
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.init(filenameExtension: "apk")!]
        if panel.runModal() == .OK { questApkURL = panel.url }
    }

    func choosePXRAndroid() {
        pxrAndroidURL = choosePackageFile(title: "Android APK", extension: "apk")
    }

    func choosePXRIOS() {
        pxrIOSURL = choosePackageFile(title: "iPhone IPA", extension: "ipa")
    }

    func choosePXRPackage() {
        pxrPackageURL = choosePackageFile(title: "PhoneXR Package", extension: "pxr")
        pxrPackageIsSource = pxrPackageURL.map { pxrBridge.isSourcePackage($0) } ?? false
        if pxrPackageIsSource {
            status = "Source PXR v2: выберите «Собрать для Android» или «Собрать для iPhone»."
        }
    }

    func choosePXRSource() {
        let panel = NSOpenPanel()
        panel.title = "Unity-проект или ZIP"
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.folder, .zip]
        if panel.runModal() == .OK { pxrSourceURL = panel.url }
    }

    func createSourcePXR() {
        guard let source = pxrSourceURL else { return }
        let panel = NSSavePanel()
        panel.title = "Сохранить Unity Source PXR"
        panel.nameFieldStringValue = "\(source.deletingPathExtension().lastPathComponent)-Source.pxr"
        panel.allowedContentTypes = [UTType(filenameExtension: "pxr")!]
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        isBusy = true
        status = "Упаковка Unity-кода в Source PXR…"
        let pxrBridge = pxrBridge
        Task.detached {
            do {
                let message = try pxrBridge.createSource(source: source, destination: destination)
                await MainActor.run {
                    self.isBusy = false
                    self.status = message
                    NSWorkspace.shared.activateFileViewerSelecting([destination])
                }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка Source PXR: \(error.localizedDescription)" }
            }
        }
    }

    func createPXR() {
        guard let android = pxrAndroidURL, let ios = pxrIOSURL else { return }
        let panel = NSSavePanel()
        panel.title = "Сохранить универсальный PhoneXR Package"
        panel.nameFieldStringValue = "\(android.deletingPathExtension().lastPathComponent).pxr"
        panel.allowedContentTypes = [UTType(filenameExtension: "pxr")!]
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        isBusy = true
        status = "Создание универсального .pxr…"
        let pxrBridge = pxrBridge
        Task.detached {
            do {
                let message = try pxrBridge.create(
                    title: android.deletingPathExtension().lastPathComponent,
                    androidAPK: android,
                    iosIPA: ios,
                    destination: destination
                )
                await MainActor.run {
                    self.isBusy = false
                    self.status = message
                    NSWorkspace.shared.activateFileViewerSelecting([destination])
                }
            } catch {
                await MainActor.run {
                    self.isBusy = false
                    self.status = "Ошибка .pxr: \(error.localizedDescription)"
                }
            }
        }
    }

    private func choosePackageFile(title: String, extension ext: String) -> URL? {
        let panel = NSOpenPanel()
        panel.title = title
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [UTType(filenameExtension: ext)!]
        return panel.runModal() == .OK ? panel.url : nil
    }

    func refreshPXRDevices() {
        isBusy = true
        status = "Поиск Android и iPhone…"
        let androidBridge = bridge
        let iosBridge = iosBridge
        Task.detached {
            let android = (try? androidBridge.devices()) ?? []
            let ios = (try? iosBridge.devices()) ?? []
            await MainActor.run {
                self.isBusy = false
                self.devices = android
                self.iosDevices = ios
                if self.selectedSerial == nil { self.selectedSerial = android.first?.serial }
                if self.selectedIOSIdentifier == nil { self.selectedIOSIdentifier = ios.first?.identifier }
                self.status = "Найдено: Android — \(android.count), iPhone — \(ios.count)."
            }
        }
    }

    func installPXRAndroid() {
        guard let package = pxrPackageURL else { return }
        if pxrBridge.isSourcePackage(package) {
            sourceURL = package
            buildPlatform = .android
            buildUnitySource(package)
            return
        }
        guard let serial = selectedSerial else { return }
        isBusy = true
        status = "Извлечение Android APK из .pxr…"
        let pxrBridge = pxrBridge
        let androidBridge = bridge
        Task.detached {
            do {
                let apk = try pxrBridge.extract(package, platform: .android)
                defer { try? FileManager.default.removeItem(at: apk.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()) }
                let message = try androidBridge.install(apk: apk, serial: serial)
                await MainActor.run { self.isBusy = false; self.status = message }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка .pxr: \(error.localizedDescription)" }
            }
        }
    }

    func installPXRIOS() {
        guard let package = pxrPackageURL else { return }
        if pxrBridge.isSourcePackage(package) {
            sourceURL = package
            buildPlatform = .ios
            buildUnitySource(package)
            return
        }
        guard let identifier = selectedIOSIdentifier else { return }
        isBusy = true
        status = "Извлечение iPhone IPA из .pxr…"
        let pxrBridge = pxrBridge
        let iosBridge = iosBridge
        Task.detached {
            do {
                let ipa = try pxrBridge.extract(package, platform: .ios)
                defer { try? FileManager.default.removeItem(at: ipa.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()) }
                let message = try iosBridge.copyToLiveContainer(ipa: ipa, deviceIdentifier: identifier)
                await MainActor.run { self.isBusy = false; self.status = message }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка .pxr: \(error.localizedDescription)" }
            }
        }
    }

    func installRuntime() {
        guard let runtime = Bundle.main.url(forResource: "monado-openxr-runtime", withExtension: "apk") else {
            status = "Ошибка: OpenXR Runtime отсутствует внутри приложения."
            return
        }
        if installMethod == .fileTransfer {
            guard let destination = chooseApkDestination(defaultName: "Monado-OpenXR-Runtime.apk") else { return }
            copyForTransfer(runtime, to: destination)
            return
        }
        guard let serial = selectedSerial else { return }
        work("Установка OpenXR Runtime…") { bridge in
            try bridge.install(apk: runtime, serial: serial)
        } completion: { [weak self] in self?.status = $0 + " Установите Khronos Runtime Broker, затем откройте Monado XR один раз." }
    }

    func installQuestApk() {
        guard let game = questApkURL,
              let runtime = Bundle.main.url(forResource: "monado-openxr-runtime", withExtension: "apk") else { return }
        if installMethod == .fileTransfer {
            let panel = NSOpenPanel()
            panel.title = "Папка для комплекта APK"
            panel.canChooseDirectories = true
            panel.canChooseFiles = false
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let folder = panel.url else { return }
            do {
                let runtimeOut = folder.appendingPathComponent("1-Monado-OpenXR-Runtime.apk")
                let gameOut = folder.appendingPathComponent("2-\(game.lastPathComponent)")
                try replaceCopy(runtime, to: runtimeOut)
                try replaceCopy(game, to: gameOut)
                status = "Комплект сохранён. На телефоне установите файлы по номерам: сначала Runtime, затем игру."
                NSWorkspace.shared.activateFileViewerSelecting([runtimeOut, gameOut])
            } catch { status = "Ошибка сохранения: \(error.localizedDescription)" }
            return
        }
        guard let serial = selectedSerial else { return }
        work("Установка Runtime и игры…") { bridge in
            try bridge.installOpenXR(runtime: runtime, game: game, serial: serial)
        } completion: { [weak self] in self?.status = $0 }
    }

    func installCompanion() {
        if installMethod == .fileTransfer {
            guard let source = Bundle.main.url(forResource: "cardboard-hands", withExtension: "apk"),
                  let destination = chooseApkDestination(defaultName: "Cardboard-Hands.apk") else { return }
            do {
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.copyItem(at: source, to: destination)
                status = "APK сохранён: \(destination.path). Скопируйте его в Download телефона и откройте там."
                NSWorkspace.shared.activateFileViewerSelecting([destination])
            } catch {
                status = "Ошибка сохранения: \(error.localizedDescription)"
            }
            return
        }
        guard let serial = selectedSerial else { return }
        work("Установка Cardboard Hands…") { bridge in
            guard let apk = Bundle.main.url(forResource: "cardboard-hands", withExtension: "apk") else {
                throw BridgeError.message("Cardboard Hands APK отсутствует внутри приложения.")
            }
            return try bridge.install(apk: apk, serial: serial)
        } completion: { [weak self] in self?.status = $0 }
    }

    func buildAndInstall() {
        guard let source = sourceURL else { return }
        if unityBridge.isUnitySource(source) {
            buildUnitySource(source)
            return
        }
        guard buildPlatform == .android else {
            status = "Для iPhone нужен Unity-проект с папками Assets и ProjectSettings. Gradle собирает только Android."
            return
        }
        if installMethod == .fileTransfer {
            guard let destination = chooseApkDestination(defaultName: "OpenXR-App.apk") else { return }
            work("Сборка Android APK…") { bridge in
                try bridge.build(source: source, destination: destination)
                return "APK собран: \(destination.path). Скопируйте его в Download телефона и установите вручную."
            } completion: { [weak self] message in
                self?.status = message
                NSWorkspace.shared.activateFileViewerSelecting([destination])
            }
            return
        }
        guard let serial = selectedSerial else { return }
        work("Подготовка проекта…") { bridge in
            try bridge.buildAndInstall(source: source, serial: serial)
        } completion: { [weak self] in self?.status = $0 }
    }

    private func buildUnitySource(_ source: URL) {
        if buildPlatform == .ios {
            let panel = NSOpenPanel()
            panel.title = "Папка для Xcode-проекта"
            panel.canChooseDirectories = true
            panel.canChooseFiles = false
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let folder = panel.url else { return }
            let destination = folder.appendingPathComponent("PhoneXR-iOS-Xcode", isDirectory: true)
            runUnityBuild(source: source, destination: destination, platform: .ios, installSerial: nil)
            return
        }

        if installMethod == .fileTransfer {
            guard let destination = chooseApkDestination(defaultName: "PhoneXR-OpenXR.apk") else { return }
            runUnityBuild(source: source, destination: destination, platform: .android, installSerial: nil)
        } else {
            guard let serial = selectedSerial else { return }
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent("PhoneXR-\(UUID().uuidString).apk")
            runUnityBuild(source: source, destination: destination, platform: .android, installSerial: serial)
        }
    }

    private func runUnityBuild(source: URL, destination: URL, platform: UnityBuildPlatform, installSerial: String?) {
        isBusy = true
        status = "Подготовка копии Unity-проекта и OpenXR-пакетов…"
        let unityBridge = unityBridge
        let androidBridge = bridge
        Task.detached {
            do {
                let message = try unityBridge.build(source: source, platform: platform, destination: destination)
                let finalMessage: String
                if let serial = installSerial {
                    finalMessage = try androidBridge.install(apk: destination, serial: serial)
                    try? FileManager.default.removeItem(at: destination)
                } else {
                    finalMessage = message
                }
                await MainActor.run { self.isBusy = false; self.status = finalMessage }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка Unity: \(error.localizedDescription)" }
            }
        }
    }

    private func chooseApkDestination(defaultName: String) -> URL? {
        let panel = NSSavePanel()
        panel.title = "Сохранить Android APK"
        panel.nameFieldStringValue = defaultName
        panel.allowedContentTypes = [.init(filenameExtension: "apk")!]
        return panel.runModal() == .OK ? panel.url : nil
    }

    private func copyForTransfer(_ source: URL, to destination: URL) {
        do {
            try replaceCopy(source, to: destination)
            status = "APK сохранён: \(destination.path). Скопируйте его в Download телефона и установите."
            NSWorkspace.shared.activateFileViewerSelecting([destination])
        } catch { status = "Ошибка сохранения: \(error.localizedDescription)" }
    }

    private func replaceCopy(_ source: URL, to destination: URL) throws {
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: source, to: destination)
    }

    private func work<T>(
        _ initial: String,
        operation: @escaping (AndroidBridge) throws -> T,
        completion: @escaping (T) -> Void
    ) {
        isBusy = true
        status = initial
        let bridge = bridge
        Task.detached {
            do {
                let result = try operation(bridge)
                await MainActor.run { self.isBusy = false; completion(result) }
            } catch {
                await MainActor.run { self.isBusy = false; self.status = "Ошибка: \(error.localizedDescription)" }
            }
        }
    }
}
