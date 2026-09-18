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
    private var cinemaApps by mutableStateOf<List<Pair<String, String>>?>(null)
    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { shizuku = VirtualScreen.access() }
    }

    private var storeItems by mutableStateOf<List<GameStore.Item>?>(null)
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
        refreshGames()
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
                    else -> SettingsTab()
                }
            }
            CupertinoNavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                backdrop = backdrop,
                selectedTabIndex = { tab },
                onTabSelected = { selectTab(it) },
                tabsCount = 3
            ) {
                CupertinoNavigationBarItem(
                    onClick = { selectTab(0) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.House, null) },
                    label = { CupertinoText("Меню") }
                )
                CupertinoNavigationBarItem(
                    onClick = { selectTab(1) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.Cart, null) },
                    label = { CupertinoText("Магазин") }
                )
                CupertinoNavigationBarItem(
                    onClick = { selectTab(2) },
                    icon = { CupertinoIcon(CupertinoIcons.Filled.Gearshape, null) },
                    label = { CupertinoText("Настройки") }
                )
            }
        }

        pendingPatch?.let { PatchDialog(it) }
        ready?.let { ReadyDialog(it) }
        error?.let { message ->
            CupertinoAlertDialog(
                onDismissRequest = { error = null },
                title = { CupertinoText("Не получилось") },
                message = { CupertinoText(message) }
            ) { default(onClick = { error = null }) { CupertinoText("OK") } }
        }
    }

    private fun selectTab(index: Int) {
        tab = index
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
                HigLink("Войти в VR") {
                    enterVr.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
                        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }
            }

            HigSection(
                title = "Игры",
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
                title = "Установка",
                footer = "APK OpenXR-игры, игры Gear VR (64 и 32 бита) или пакет .pxr. " +
                    "PhoneXR подготовит сборку, подпишет её и откроет установку."
            ) {
                HigLink(busy ?: "Установить игру из файла", enabled = busy == null) {
                    chooseApk.launch(
                        arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")
                    )
                }
                HigLink("Магазин игр") { selectTab(1) }
            }

            CinemaSection()

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

            HigSection(title = "Трекинг", footer = status) {
                HigLink("Остановить трекинг") {
                    stopService(Intent(this@MainActivity, HandTrackingService::class.java))
                    status = "Трекинг остановлен"
                }
            }
        }
    }

    /** PXR Bedrock: Minecraft (or any app) on a big screen in VR, played with a gamepad or Joy-Con. */
    @Composable
    private fun CinemaSection() {
        val minecraft = isInstalled(MINECRAFT)
        HigSection(
            title = "PXR Bedrock · кинотеатр",
            footer = "Minecraft Bedrock (любая версия) на большом экране в VR: голова поворачивается, экран стоит на месте. " +
                "Играйте геймпадом или Joy‑Con: они управляют Minecraft напрямую. Нужен запущенный Shizuku. " +
                "Нажатие на экран телефона выравнивает вид."
        ) {
            when (shizuku) {
                VirtualScreen.Access.NOT_RUNNING -> HigLink("Shizuku не запущен", value = "Открыть") {
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                        ?: run { error = "Установите и запустите Shizuku" }
                }
                VirtualScreen.Access.NEEDS_PERMISSION -> HigLink("Разрешить доступ Shizuku") {
                    VirtualScreen.requestPermission()
                }
                VirtualScreen.Access.READY -> HigRow("Shizuku", "Готов")
            }
            HigChoice("Комната", "Гостиная из Minecraft VR, экран над камином", cinemaScene == CinemaActivity.SCENE_ROOM) {
                cinemaScene = CinemaActivity.SCENE_ROOM
            }
            HigChoice("В воздухе", "Парящий остров над миром и облаками", cinemaScene == CinemaActivity.SCENE_SKY) {
                cinemaScene = CinemaActivity.SCENE_SKY
            }
            if (minecraft) {
                HigLink("Играть в Minecraft", enabled = shizuku == VirtualScreen.Access.READY) { openCinema(MINECRAFT) }
            } else {
                HigLink("Установить Minecraft", value = "Google Play") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$MINECRAFT")))
                }
            }
            HigLink(
                if (cinemaApps == null) "Другое приложение…" else "Скрыть приложения",
                enabled = shizuku == VirtualScreen.Access.READY
            ) {
                cinemaApps = if (cinemaApps != null) null else launchableApps()
            }
            cinemaApps?.forEach { (packageName, label) ->
                HigLink(label) { openCinema(packageName) }
            }
        }
    }

    private fun launchableApps(): List<Pair<String, String>> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

    private fun openCinema(target: String) {
        startActivity(
            Intent(this, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, target)
                .putExtra(CinemaActivity.EXTRA_SCENE, cinemaScene)
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
            title = "Магазин",
            subtitle = "VR-игры с сервера PhoneXR. Игры Gear VR подготавливаются автоматически.",
            bottomInset = TAB_BAR_ROOM
        ) {
            val items = storeItems
            HigSection(
                title = "Игры",
                footer = when {
                    storeError != null -> storeError
                    items != null && items.isEmpty() -> GameStore.EMPTY_HINT
                    else -> null
                }
            ) {
                when {
                    storeLoading && items == null -> HigRow("Загрузка…", trailing = { CupertinoActivityIndicator() })
                    items.isNullOrEmpty() -> HigRow(if (storeError != null) "Магазин недоступен" else "Пока пусто")
                    else -> items.forEach { item -> StoreRow(item) }
                }
            }
            if (webApps.isNotEmpty()) {
                HigSection(
                    title = "Веб‑приложения",
                    footer = "Открываются в браузере PhoneXR прямо в VR. Добавленные появляются на главном экране VR."
                ) {
                    webApps.forEach { app ->
                        val added = app.url in installedWeb
                        HigLink(app.name, value = if (added) "Открыть" else "Добавить") {
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
                HigLink(if (storeLoading) "Обновление…" else "Обновить", enabled = !storeLoading) { refreshStore() }
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
                    progress == null -> CupertinoText("Загрузить", color = CupertinoTheme.colorScheme.accent)
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
                val file = GameStore.download(item, File(cacheDir, "store")) { value ->
                    runOnUiThread { downloads[item.path] = value }
                }
                runOnUiThread {
                    downloads.remove(item.path)
                    patch(Uri.fromFile(file), replaces = null, label = item.title)
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
        HigPage(title = "Настройки", bottomInset = TAB_BAR_ROOM) {
            HigSection(title = "Управление") {
                HigLink("Управление и Joy‑Con") { start(SettingsActivity::class.java) }
                HigLink("Joy‑Con через камеру") { start(JoyConCameraActivity::class.java) }
            }
            HigSection(title = "Проверка") {
                HigLink("Проверить руки") {
                    stopService(Intent(this@MainActivity, HandTrackingService::class.java))
                    start(HandTestActivity::class.java)
                }
                HigLink("Проверить гироскоп Joy‑Con") { start(GyroTestActivity::class.java) }
            }
            HigSection(title = "Магазин", footer = "Игры берутся из папки «${GameStore.FOLDER}» в Supabase и из файлов .json в корне репозитория PhoneXR на GitHub.") {
                HigRow("Сервер", GameStore.URL_BASE.removePrefix("https://"))
            }
            HigSection {
                HigLink("О приложении") { start(AboutActivity::class.java) }
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
            cancel(onClick = { pendingPatch = null }) { CupertinoText("Отмена") }
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
            cancel(onClick = { ready = null }) { CupertinoText("Позже") }
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

    companion object {
        private const val KEY_TAB = "tab"
        private const val MINECRAFT = "com.mojang.minecraftpe"
        /** Height of the floating tab bar plus its margin. */
        private val TAB_BAR_ROOM = 120.dp
    }
}
