package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import zone.ien.hig.CupertinoActivityIndicator
import zone.ien.hig.CupertinoAlertDialog
import zone.ien.hig.CupertinoIcon
import zone.ien.hig.CupertinoNavigationBar
import zone.ien.hig.CupertinoNavigationBarItem
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.cancel
import zone.ien.hig.default
import zone.ien.hig.destructive
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.filled.Cart
import zone.ien.hig.icons.filled.Gearshape
import zone.ien.hig.icons.filled.House
import zone.ien.hig.icons.filled.Person
import zone.ien.hig.section.SectionLink
import zone.ien.hig.section.SectionScope
import zone.ien.hig.theme.CupertinoTheme
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var games by mutableStateOf<List<GameLibrary.Game>?>(null)
    private var busy by mutableStateOf<String?>(null)
    /** A game waiting for the user's decision: patch it, or install the patched build. */
    private var pendingPatch by mutableStateOf<GameLibrary.Game?>(null)
    private var ready by mutableStateOf<Ready?>(null)
    private var error by mutableStateOf<String?>(null)
    private var startAfterPermission = false
    /** Bumped on every resume, so dialogs re-check what is installed after the system uninstaller. */
    private var resumes by mutableStateOf(0)

    private var shizuku by mutableStateOf(VirtualScreen.Access.NOT_RUNNING)
    private var cinemaScene by mutableStateOf(CinemaActivity.SCENE_ROOM)
    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { shizuku = VirtualScreen.access() }
    }

    private var storeItems by mutableStateOf<List<GameStore.Item>?>(null)
    private var androidApps by mutableStateOf(false)
    private var languagePicker by mutableStateOf(false)

    // ---------------------------------------------------------------- Friends
    private var friendsProfile by mutableStateOf<Friends.Person?>(null)
    private var friendsMine by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsAddedMe by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsFound by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsQuery by mutableStateOf("")
    private var friendsUsername by mutableStateOf("")
    private var friendsStatus by mutableStateOf<String?>(null)
    private var friendsLoading by mutableStateOf(false)

    private fun refreshFriends() {
        if (Account.current(this) == null) return
        friendsLoading = true
        Thread {
            val result = runCatching {
                val profile = Friends.myProfile(this)
                val mine = if (profile != null) Friends.mine(this) else emptyList()
                Triple(profile, mine, if (profile != null) Friends.addedMe(this, mine) else emptyList())
            }
            runOnUiThread {
                friendsLoading = false
                result.onSuccess { (profile, mine, addedMe) ->
                    friendsProfile = profile; friendsMine = mine; friendsAddedMe = addedMe; friendsStatus = null
                }.onFailure { friendsStatus = it.message }
            }
        }.start()
    }

    private fun friendsAction(action: () -> String?) {
        Thread {
            val error = runCatching { action() }.getOrElse { it.message }
            runOnUiThread { friendsStatus = error; refreshFriends() }
        }.start()
    }

    @Composable
    private fun FriendsTab() {
        HigPage(title = tr("Друзья"), subtitle = tr("Добавляйте друзей по юзернейму и звоните им персоной в VR"), bottomInset = TAB_BAR_ROOM) {
            if (Account.current(this@MainActivity) == null) {
                HigSection(footer = tr("Друзья и звонки работают с аккаунтом PhoneXR.")) {
                    HigLink(tr("Войти")) { start(AccountActivity::class.java) }
                }
                return@HigPage
            }
            friendsStatus?.let { HigSection { HigRow(it) } }
            val profile = friendsProfile
            if (profile == null) {
                HigSection(title = tr("Ваш юзернейм"), footer = tr("3–20 символов: a–z, 0–9, _ и . По нему вас найдут друзья.")) {
                    InputRow("username", friendsUsername) { friendsUsername = it.lowercase().replace(" ", "") }
                    HigLink(if (friendsLoading) tr("Загрузка…") else tr("Готово"), enabled = !friendsLoading) {
                        val name = friendsUsername
                        friendsAction { Friends.setUsername(this@MainActivity, name) }
                    }
                }
                return@HigPage
            }
            HigSection { HigRow("@${profile.username}", profile.name) }
            HigSection(title = tr("Найти по юзернейму")) {
                InputRow("@username", friendsQuery) { value ->
                    friendsQuery = value
                    Thread {
                        val found = runCatching { Friends.search(this@MainActivity, value) }.getOrDefault(emptyList())
                        runOnUiThread { if (friendsQuery == value) friendsFound = found }
                    }.start()
                }
                friendsFound.forEach { person ->
                    val added = friendsMine.any { it.id == person.id }
                    HigLink("@${person.username}", value = if (added) "✓" else tr("Добавить"), enabled = !added) {
                        friendsAction { Friends.add(this@MainActivity, person) }
                    }
                }
            }
            if (friendsAddedMe.isNotEmpty()) HigSection(title = tr("Добавили вас")) {
                friendsAddedMe.forEach { person ->
                    HigLink("@${person.username}", value = tr("Добавить")) { friendsAction { Friends.add(this@MainActivity, person) } }
                }
            }
            HigSection(title = tr("Мои друзья"), footer = tr("Позвонить можно из приложения «Звонки» в шлеме, когда друг в сети.")) {
                if (friendsMine.isEmpty()) HigRow(tr("Пока пусто"))
                friendsMine.forEach { person ->
                    val online = Calls.online.any { it.id == person.id }
                    HigLink("@${person.username}", value = if (online) tr("В сети") else tr("Удалить")) {
                        if (!online) friendsAction { Friends.remove(this@MainActivity, person) }
                    }
                }
            }
        }
    }

    /** A one-line text field in a section row. */
    @Composable
    private fun InputRow(hint: String, value: String, onChange: (String) -> Unit) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value.isEmpty()) CupertinoText(hint, color = CupertinoTheme.colorScheme.secondaryLabel)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = CupertinoTheme.colorScheme.label, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFF0A84FF)),
            )
        }
    }
    /** A VR mode from the store whose activation steps are shown. */
    private var guide by mutableStateOf<VrMode?>(null)
    private var webApps by mutableStateOf<List<WebApps.App>>(emptyList())
    private var installedWeb by mutableStateOf<Set<String>>(emptySet())
    private var storeError by mutableStateOf<String?>(null)
    private var storeLoading by mutableStateOf(false)
    /** Download progress per store path: 0..1, or -1 while the size is unknown. */
    private val downloads = mutableStateMapOf<String, Float>()
    private val storeIcons = mutableStateMapOf<String, Bitmap>()
    private val storeDescriptions = mutableStateMapOf<String, String>()

    /**
     * A patched APK ready to install. [replacesPackage] is set when the same package is installed
     * with a different signature, so the old copy must be removed first.
     */
    private data class Ready(val result: ApkPatcher.Result, val label: String?, val replacesPackage: String?)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) startTracking()
        else if (!granted) status = "Для рук нужен доступ к камере"
        startAfterPermission = false
    }
    /** The VR home needs the camera (passthrough, hands) and the gallery (Spatial Photos). */
    private val enterVr = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startActivity(Intent(this, VrHomeActivity::class.java))
    }
    private val chooseApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) patch(uri, replaces = null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
        L10n.init(this)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        setContent { PhoneXRTheme { Root() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        androidApps = AndroidAppsContent.enabled(this)
        refreshGames()
        checkUpdateOnce()
        shizuku = VirtualScreen.access()
    }

    private fun refreshGames() {
        Thread {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
            runOnUiThread { games = found }
        }.start()
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun Root() {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                when (tab) {
                    0 -> MenuTab()
                    1 -> StoreTab()
                    2 -> FriendsTab()
                    else -> SettingsTab()
                }
            }
            CupertinoNavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                backdrop = backdrop,
                selectedTabIndex = { tab },
                onTabSelected = { selectTab(it) },
                tabsCount = 4
            ) {
                CupertinoNavigationBarItem(
                    onClick = { selectTab(0) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.House, null) },
                    label = { CupertinoText(tr("Меню")) }
                )
                CupertinoNavigationBarItem(
                    onClick = { selectTab(1) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.Cart, null) },
                    label = { CupertinoText(tr("Магазин")) }
                )
                CupertinoNavigationBarItem(
                    onClick = { selectTab(2) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.Person, null) },
                    label = { CupertinoText(tr("Друзья")) }
                )
                CupertinoNavigationBarItem(
                    onClick = { selectTab(3) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.Gearshape, null) },
                    label = { CupertinoText(tr("Настройки")) }
                )
            }
        }

        pendingPatch?.let { PatchDialog(it) }
        guide?.let { GuideDialog(it) }
        if (languagePicker) {
            CupertinoAlertDialog(
                onDismissRequest = { languagePicker = false },
                title = { CupertinoText(tr("Язык")) },
                message = { CupertinoText("PhoneXR") }
            ) {
                L10n.Lang.values().forEach { lang ->
                    default(onClick = {
                        languagePicker = false
                        L10n.set(this@MainActivity, lang)
                        recreate()
                    }) { CupertinoText((if (lang == L10n.current) "✓ " else "") + lang.title) }
                }
                cancel(onClick = { languagePicker = false }) { CupertinoText(tr("Отмена")) }
            }
        }
        update?.let { found ->
            CupertinoAlertDialog(
                onDismissRequest = { update = null },
                title = { CupertinoText("Доступно PhoneXR ${found.version}") },
                message = { CupertinoText(Updates.formatSize(found.size)) }
            ) {
                cancel(onClick = { update = null }) { CupertinoText(tr("Позже")) }
                default(onClick = { update = null; start(UpdateActivity::class.java) }) { CupertinoText(tr("Подробнее")) }
            }
        }
        ready?.let { ReadyDialog(it) }
        error?.let { message ->
            CupertinoAlertDialog(
                onDismissRequest = { error = null },
                title = { CupertinoText(tr("Не получилось")) },
                message = { CupertinoText(message) }
            ) { default(onClick = { error = null }) { CupertinoText("OK") } }
        }
    }

    private var updateChecked = false
    private var update by mutableStateOf<Updates.Release?>(null)

    /** Automatic updates: once per start, a new version opens the update screen. */
    private fun checkUpdateOnce() {
        if (updateChecked || !Updates.autoUpdate(this)) return
        updateChecked = true
        Thread {
            val found = runCatching { Updates.check(this) }.getOrNull() ?: return@Thread
            runOnUiThread { update = found }
        }.start()
    }

    private fun selectTab(index: Int) {
        tab = index
        if (index == 2) refreshFriends()
        if (index == 1 && storeItems == null && !storeLoading) refreshStore()
    }

    // ---------------------------------------------------------------- Menu

    @Composable
    private fun MenuTab() {
        HigPage(
            title = "PhoneXR",
            subtitle = "VR на телефоне: руки в камере, Joy‑Con вместо контроллеров",
            bottomInset = TAB_BAR_ROOM
        ) {
            HigSection(footer = "VR‑дом в смешанной реальности: щипок — открыть, кулак — перетащить иконки, ладонь к лицу + щипок — меню. Joy‑Con: ZR или A.") {
                HigLink(tr("Войти в VR")) {
                    enterVr.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.RECORD_AUDIO)
                        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }

            HigSection(
                title = tr("Игры"),
                footer = "Нажмите на игру, чтобы включить трекинг и запустить её. " +
                    "Игры Gear VR сначала нужно пропатчить: PhoneXR подменит в них VrApi на OpenXR."
            ) {
                val list = games
                when {
                    list == null -> HigRow("Поиск игр…", trailing = { CupertinoActivityIndicator() })
                    list.isEmpty() -> HigRow("VR-игры не найдены", "Установите игру из магазина или из файла")
                    else -> list.forEach { game -> GameRow(game) }
                }
            }

            HigSection(
                title = tr("Установка"),
                footer = "APK OpenXR-игры, игры Gear VR (64 и 32 бита) или пакет .pxr. " +
                    "PhoneXR подготовит сборку, подпишет её и откроет установку."
            ) {
                HigLink(busy ?: tr("Установить игру из файла"), enabled = busy == null) {
                    chooseApk.launch(
                        arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")
                    )
                }
                HigLink(tr("Магазин игр")) { selectTab(1) }
            }

            RuntimeSection()

            HigSection(
                title = "Daydream",
                footer = "Игры Daydream ищут Google VR Services. PhoneXR ставит Opendream Services 1.13 — " +
                    "после этого игры Daydream и Cardboard появляются в списке игр и в VR‑доме."
            ) {
                if (Daydream.servicesInstalled(this@MainActivity)) {
                    HigRow("VR Services", "Opendream установлен")
                } else {
                    HigLink("Установить Opendream Services") { Daydream.installServices(this@MainActivity) }
                }
            }

            HigSection(title = tr("Трекинг"), footer = status) {
                HigLink(tr("Остановить трекинг")) {
                    stopService(Intent(this@MainActivity, HandTrackingService::class.java))
                    status = "Трекинг остановлен"
                }
            }
        }
    }

    /** PhoneXR Runtime for OpenXR games: install or update it, then pick it in the OpenXR broker. */
    @Composable
    private fun RuntimeSection() {
        val state = if (resumes >= 0) PhoneXrRuntime.state(this) else PhoneXrRuntime.State.MISSING
        HigSection(
            title = "OpenXR",
            footer = "PhoneXR Runtime заменяет Monado: OpenXR‑игры получают руки, Joy‑Con и голову от PhoneXR. " +
                "После установки выберите «PhoneXR Runtime» в OpenXR Runtime Broker."
        ) {
            when (state) {
                PhoneXrRuntime.State.READY -> HigRow("PhoneXR Runtime", "Установлен")
                PhoneXrRuntime.State.OUTDATED -> HigLink("Обновить PhoneXR Runtime") { PhoneXrRuntime.install(this@MainActivity) }
                PhoneXrRuntime.State.MISSING -> if (PhoneXrRuntime.bundled(this@MainActivity)) {
                    HigLink("Установить PhoneXR Runtime") { PhoneXrRuntime.install(this@MainActivity) }
                } else {
                    HigRow("PhoneXR Runtime", "Не входит в эту сборку")
                }
            }
            HigLink(
                "OpenXR Runtime Broker",
                value = if (PhoneXrRuntime.brokerInstalled(this@MainActivity)) tr("Открыть") else "Google Play"
            ) { PhoneXrRuntime.openBroker(this@MainActivity) }
        }
    }

    private fun openCinema(target: String, scene: String = cinemaScene) {
        startActivity(
            Intent(this, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, target)
                .putExtra(CinemaActivity.EXTRA_SCENE, scene)
        )
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun SectionScope.GameRow(game: GameLibrary.Game) {
        SectionLink(
            onClick = { open(game) },
            icon = { AppIcon(game.packageName) },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CupertinoText(game.label)
                    CupertinoText(
                        GameLibrary.describe(game.kind) + if (game.checksPurchase) " · проверка покупки Oculus" else "",
                        style = CupertinoTheme.typography.footnote,
                        color = if (game.kind == GameLibrary.Kind.GEAR_VR_ORIGINAL) CupertinoTheme.colorScheme.accent
                        else CupertinoTheme.colorScheme.secondaryLabel
                    )
                }
            }
        )
    }

    @Composable
    private fun AppIcon(packageName: String) {
        val drawable: Drawable? = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        Canvas(modifier = Modifier.size(30.dp)) {
            drawIntoCanvas { canvas ->
                drawable?.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable?.draw(canvas.nativeCanvas)
            }
        }
    }

    // ---------------------------------------------------------------- Store

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun StoreTab() {
        HigPage(
            title = tr("Магазин"),
            subtitle = "VR-игры с сервера PhoneXR: скачиваются и ставятся как есть.",
            bottomInset = TAB_BAR_ROOM
        ) {
            HigSection(
                title = tr("VR‑режимы"),
                footer = "Обычные Minecraft и Roblox на большом экране в VR: своя комната, поворот головы, " +
                    "игра двумя руками или Joy‑Con. Нажмите на режим — появится инструкция."
            ) {
                VR_MODES.forEach { mode -> VrModeRow(mode) }
            }
            HigSection(
                title = tr("Приложения PhoneXR"),
                footer = "Android‑приложения: любые приложения телефона окнами в VR (нужен Shizuku). Появляется на главном экране VR."
            ) {
                val added = resumes >= 0 && androidApps
                HigLink(tr("Android‑приложения"), value = if (added) tr("Удалить") else tr("Получить")) {
                    androidApps = !added
                    AndroidAppsContent.setEnabled(this@MainActivity, !added)
                }
            }
            val items = storeItems
            HigSection(
                title = tr("Игры"),
                footer = when {
                    storeError != null -> storeError
                    items != null && items.isEmpty() -> GameStore.EMPTY_HINT
                    else -> null
                }
            ) {
                when {
                    storeLoading && items == null -> HigRow(tr("Загрузка…"), trailing = { CupertinoActivityIndicator() })
                    items.isNullOrEmpty() -> HigRow(if (storeError != null) "Магазин недоступен" else tr("Пока пусто"))
                    else -> items.forEach { item -> StoreRow(item) }
                }
            }
            if (webApps.isNotEmpty()) {
                HigSection(
                    title = tr("Веб‑приложения"),
                    footer = "Открываются в браузере PhoneXR прямо в VR. Добавленные появляются на главном экране VR."
                ) {
                    webApps.forEach { app ->
                        val added = app.url in installedWeb
                        HigLink(app.name, value = if (added) tr("Открыть") else tr("Добавить")) {
                            if (added) {
                                if (!WebApps.open(this@MainActivity, app.url)) error = "Установите браузер PhoneXR"
                            } else {
                                WebApps.add(this@MainActivity, app)
                                installedWeb = installedWeb + app.url
                            }
                        }
                    }
                }
            }
            HigSection {
                HigLink(if (storeLoading) tr("Обновление…") else tr("Обновить"), enabled = !storeLoading) { refreshStore() }
            }
        }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun SectionScope.VrModeRow(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        SectionLink(
            onClick = { guide = mode },
            icon = { AppIcon(if (installed) mode.packageName else packageName) },
            caption = {
                if (installed) {
                    CupertinoText(tr("Играть"), color = CupertinoTheme.colorScheme.accent)
                } else {
                    CupertinoText(tr("Скачать"), color = CupertinoTheme.colorScheme.accent)
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CupertinoText(mode.title)
                    CupertinoText(
                        mode.subtitle,
                        style = CupertinoTheme.typography.footnote,
                        color = CupertinoTheme.colorScheme.secondaryLabel
                    )
                }
            }
        )
    }

    /** How to switch a VR mode on: the game from Google Play, Shizuku, then play from PhoneXR. */
    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun GuideDialog(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        val ready = shizuku == VirtualScreen.Access.READY
        CupertinoAlertDialog(
            onDismissRequest = { guide = null },
            title = { CupertinoText("Как включить ${mode.title}") },
            message = {
                CupertinoText(
                    listOf(
                        (if (installed) "✓ " else "1. ") + "Установите ${mode.game} из Google Play.",
                        (if (ready) "✓ " else "2. ") + "Установите и запустите Shizuku (через отладку по Wi‑Fi), разрешите доступ PhoneXR.",
                        "3. Нажмите «Играть»: игра откроется на большом экране — ${mode.scene}.",
                        if (mode.packageName == MINECRAFT)
                            "4. Minecraft VR: поворот головы — камера, «пистолет» из пальцев — идти, кулак — ломать и бить, " +
                                "щипок — поставить блок. В настройках Minecraft включите «Раздельное управление» (Split controls)."
                        else "4. Управление: щипок любой руки — нажатие по экрану, две руки — два пальца. " +
                            "Joy‑Con и геймпад работают как в самой игре. Тап по телефону выравнивает вид.",
                    ).joinToString("\n")
                )
            }
        ) {
            cancel(onClick = { guide = null }) { CupertinoText(tr("Закрыть")) }
            when {
                !installed -> default(onClick = {
                    guide = null
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${mode.packageName}")))
                }) { CupertinoText(tr("Скачать")) }
                !ready -> default(onClick = {
                    guide = null
                    if (shizuku == VirtualScreen.Access.NEEDS_PERMISSION) VirtualScreen.requestPermission()
                    else packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                        ?: startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                }) { CupertinoText("Shizuku") }
                else -> default(onClick = {
                    guide = null
                    openCinema(mode.packageName, mode.sceneId)
                }) { CupertinoText(tr("Играть")) }
            }
        }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun SectionScope.StoreRow(item: GameStore.Item) {
        val progress = downloads[item.path]
        SectionLink(
            onClick = { if (progress == null && busy == null) downloadAndInstall(item) },
            enabled = progress == null,
            icon = { StoreIcon(item) },
            caption = {
                when {
                    progress == null -> CupertinoText(tr("Загрузить"), color = CupertinoTheme.colorScheme.accent)
                    progress < 0f -> CupertinoActivityIndicator()
                    progress >= 1f -> CupertinoText("Подготовка…")
                    else -> CupertinoText("${(progress * 100).roundToInt()}%")
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CupertinoText(item.title)
                    val details = listOfNotNull(
                        storeDescriptions[item.path],
                        item.extension.uppercase() + if (item.size > 0) " · " + formatSize(item.size) else ""
                    )
                    details.forEach { line ->
                        CupertinoText(
                            line,
                            style = CupertinoTheme.typography.footnote,
                            color = CupertinoTheme.colorScheme.secondaryLabel
                        )
                    }
                }
            }
        )
    }

    @Composable
    private fun StoreIcon(item: GameStore.Item) {
        val icon = storeIcons[item.path]
        if (icon != null) {
            Image(
                icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                AppIcon(packageName)
            }
        }
    }

    private fun refreshStore() {
        storeLoading = true
        storeError = null
        Thread {
            try {
                val items = GameStore.list()
                val web = WebApps.fromStore()
                runOnUiThread {
                    webApps = web
                    installedWeb = WebApps.installed(this).map { it.url }.toSet()
                    storeItems = items
                    storeLoading = false
                }
                for (item in items) {
                    GameStore.description(item)?.let { text -> runOnUiThread { storeDescriptions[item.path] = text } }
                    GameStore.icon(item)?.let { bitmap -> runOnUiThread { storeIcons[item.path] = bitmap } }
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Store listing failed", failure)
                runOnUiThread {
                    storeLoading = false
                    storeError = failure.localizedMessage ?: "Магазин недоступен"
                }
            }
        }.start()
    }

    private fun downloadAndInstall(item: GameStore.Item) {
        downloads[item.path] = 0f
        Thread {
            try {
                val file = GameStore.download(item, File(cacheDir, "patched/store")) { value ->
                    runOnUiThread { downloads[item.path] = value }
                }
                runOnUiThread {
                    downloads.remove(item.path)
                    // Store games install exactly as published; only .pxr packages still need unpacking.
                    if (item.extension == "pxr") patch(Uri.fromFile(file), replaces = null, label = item.title)
                    else installApk(file)
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Download failed: ${item.path}", failure)
                runOnUiThread {
                    downloads.remove(item.path)
                    error = "«${item.title}» не скачалась: ${failure.localizedMessage}"
                }
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f КБ".format(bytes / 1024.0)
    }

    // ---------------------------------------------------------------- Settings

    @Composable
    private fun SettingsTab() {
        HigPage(title = tr("Настройки"), bottomInset = TAB_BAR_ROOM) {
            HigSection(title = tr("Управление")) {
                HigLink(tr("Управление и Joy‑Con")) { start(SettingsActivity::class.java) }
                HigLink(tr("Joy‑Con через камеру")) { start(JoyConCameraActivity::class.java) }
            }
            HigSection(title = tr("Проверка")) {
                HigLink(tr("Проверить гироскоп Joy‑Con")) { start(GyroTestActivity::class.java) }
            }
            HigSection(title = tr("Магазин"), footer = "Игры берутся из папки «${GameStore.FOLDER}» в Supabase и из файлов .json в корне репозитория PhoneXR на GitHub.") {
                HigRow(tr("Сервер"), GameStore.URL_BASE.removePrefix("https://"))
            }
            HigSection {
                HigLink(tr("Язык"), value = L10n.current.title) { languagePicker = true }
                HigLink(tr("Аккаунт"), value = if (resumes >= 0) Account.current(this@MainActivity)?.name ?: tr("Войти") else null) { start(AccountActivity::class.java) }
                HigLink(tr("Обновление ПО")) { start(UpdateActivity::class.java) }
                HigLink(tr("О приложении")) { start(AboutActivity::class.java) }
            }
        }
    }

    private fun start(screen: Class<*>) = startActivity(Intent(this, screen))

    // ---------------------------------------------------------------- Patching and installing

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun PatchDialog(game: GameLibrary.Game) {
        CupertinoAlertDialog(
            onDismissRequest = { pendingPatch = null },
            title = { CupertinoText("Пропатчить «${game.label}»?") },
            message = {
                CupertinoText(
                    "Это игра Gear VR: без телефона Samsung и драйвера Oculus она сразу закрывается. " +
                        "PhoneXR заменит в ней libvrapi.so переходником на OpenXR и подпишет своей подписью. " +
                        "Оригинал придётся удалить (подпись другая), сохранения игры при этом пропадут."
                )
            }
        ) {
            cancel(onClick = { pendingPatch = null }) { CupertinoText(tr("Отмена")) }
            default(onClick = {
                pendingPatch = null
                patch(Uri.fromFile(game.apk), replaces = game)
            }) { CupertinoText("Пропатчить") }
        }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun ReadyDialog(value: Ready) {
        val replaces = value.replacesPackage
        val installed = resumes >= 0 && replaces != null && isInstalled(replaces)
        CupertinoAlertDialog(
            onDismissRequest = { ready = null },
            title = {
                CupertinoText(
                    value.label?.let { "«$it» готова" } ?: if (value.result.gearVr) "Игра Gear VR готова" else "Сборка готова"
                )
            },
            message = {
                CupertinoText(
                    value.result.changes.joinToString("\n") { "• $it" } +
                        if (installed) "\n\nУстановлена версия с другой подписью: сначала удалите её, " +
                            "затем нажмите «Установить». Сохранения игры пропадут." else ""
                )
            }
        ) {
            cancel(onClick = { ready = null }) { CupertinoText(tr("Позже")) }
            if (installed) {
                destructive(onClick = { uninstall(replaces!!) }) { CupertinoText("Удалить старую") }
            } else {
                default(onClick = {
                    ready = null
                    install(value.result)
                }) { CupertinoText("Установить") }
            }
        }
    }

    private fun open(game: GameLibrary.Game) {
        when (game.kind) {
            GameLibrary.Kind.GEAR_VR_ORIGINAL -> pendingPatch = game
            GameLibrary.Kind.DAYDREAM -> {
                if (!Daydream.servicesInstalled(this) && Daydream.bundled(this)) {
                    status = "Сначала установите Opendream Services, затем снова откройте игру"
                    Daydream.installServices(this)
                } else {
                    requestStart(game)
                }
            }
            GameLibrary.Kind.GEAR_VR_UNSUPPORTED -> error =
                "«${game.label}» — игра Gear VR, которую PhoneXR запустить не может: " +
                    "у неё нет сборки для ARM (arm64-v8a или armeabi-v7a)."
            else -> requestStart(game)
        }
    }

    private var gameToStart: GameLibrary.Game? = null

    private fun requestStart(game: GameLibrary.Game) {
        gameToStart = game
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            startAfterPermission = true
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTracking() {
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        status = "Камера рук включена"
        val game = gameToStart ?: return
        gameToStart = null
        val intent = GameLibrary.launchIntent(this, game)
        if (intent == null) {
            error = "У «${game.label}» нет экрана запуска"
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun patch(uri: Uri, replaces: GameLibrary.Game?, label: String? = replaces?.label) {
        if (replaces?.hasSplits == true) {
            error = "«${replaces.label}» установлена из нескольких APK (split). Такую игру установите из исходного файла."
            return
        }
        busy = "Подготовка…"
        Thread {
            try {
                val payload = PxrPackage.androidPayload(this, uri)
                val result = ApkPatcher.patch(this, payload)
                val conflict = signatureConflict(result.apk)
                runOnUiThread {
                    busy = null
                    ready = Ready(result, label, conflict)
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Patch", "APK preparation failed for $uri", failure)
                runOnUiThread {
                    busy = null
                    error = failure.localizedMessage ?: failure.javaClass.simpleName
                }
            }
        }.start()
    }

    /** Package name of [apk] when that package is installed with other signing keys, else null. */
    private fun signatureConflict(apk: File): String? {
        val archive = packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val installed = runCatching {
            packageManager.getPackageInfo(archive.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return null
        fun signers(info: android.content.pm.PackageInfo) =
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        return archive.packageName.takeIf { signers(archive) != signers(installed) }
    }

    /** Opens the system installer for an APK in the shared cache folder. */
    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            error = "Разрешите PhoneXR устанавливать приложения, вернитесь и скачайте снова."
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        signatureConflict(file)?.let { conflict ->
            error = "Эта игра уже установлена с другой подписью ($conflict). Удалите её и скачайте снова."
            return
        }
        val content = FileProvider.getUriForFile(this, "$packageName.patched.apks", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(content, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun install(result: ApkPatcher.Result) {
        if (!packageManager.canRequestPackageInstalls()) {
            error = "Разрешите PhoneXR устанавливать приложения, вернитесь и нажмите «Установить» снова."
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val content = FileProvider.getUriForFile(this, "$packageName.patched.apks", result.apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(content, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    @Suppress("DEPRECATION")
    private fun uninstall(target: String) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$target")))
    }

    private fun isInstalled(target: String) =
        runCatching { packageManager.getApplicationInfo(target, 0) }.isSuccess

    /** A normal game played on the big VR screen (cinema) with its own scene. */
    private data class VrMode(
        val title: String, val game: String, val packageName: String,
        val subtitle: String, val scene: String, val sceneId: String,
    )

    companion object {
        private const val KEY_TAB = "tab"
        private const val MINECRAFT = "com.mojang.minecraftpe"
        private val VR_MODES = listOf(
            VrMode("Minecraft VR", "Minecraft", MINECRAFT, "Bedrock в гостиной с камином", "гостиная из Minecraft VR", CinemaActivity.SCENE_ROOM),
            VrMode("Roblox VR", "Roblox", "com.roblox.client", "Roblox в доме из Brookhaven", "дом из Roblox с камином", CinemaActivity.SCENE_ROBLOX),
            VrMode("Brawl Stars VR", "Brawl Stars", "com.supercell.brawlstars", "Brawl Stars посреди арены", "360° панорама арены", CinemaActivity.SCENE_BRAWL),
        )
        /** Height of the floating tab bar plus its margin. */
        private val TAB_BAR_ROOM = 120.dp
    }
}
