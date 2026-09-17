package com.samrat.cardboardhands

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PhoneXR Home, visionOS style, in mixed reality: the camera image fills the view, round app icons
 * float in front, apps open as windows (browser, Minecraft, Spatial Photos) with a move bar,
 * minimize to the dock and close. Pinch clicks; palm toward the face plus a pinch opens the menu.
 * With "controllers" chosen in settings and a Joy-Con connected, ZR or A clicks instead of a pinch.
 */
class VrHomeActivity : Activity(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tracker: HeadTracker
    private val panel = HomePanel()
    private val keyboard = KeyboardPanel()
    private val keyboardRedraw = AtomicBoolean(true)
    @Volatile private var hoveredKey: String? = null
    /** Window the keyboard was opened for by hand (apps that cannot ask for it themselves). */
    @Volatile private var manualKeyboard: VrWindow? = null
    private val redraw = AtomicBoolean(true)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handTracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var joyConReceiver: BroadcastReceiver? = null

    @Volatile private var frame: Bitmap? = null
    private val frameLock = Any()
    private val frameFresh = AtomicBoolean(false)

    private val windows = CopyOnWriteArrayList<VrWindow>()
    /** GL work that must run on the render thread: creating and deleting window textures. */
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val textures = ConcurrentHashMap<String, Int>()
    private val surfaceTextures = ConcurrentHashMap<String, SurfaceTexture>()
    private val framesReady = ConcurrentHashMap.newKeySet<String>()
    /**
     * How image coordinates map to view tangents on screen, set by the renderer from the eye and camera
     * shapes: a point at image x shows at tan = (x - 0.5) * 2 * viewScaleX. The cursor and hand use the
     * same mapping, so they sit exactly on the fingers seen in the passthrough.
     */
    @Volatile private var viewScaleX = 16f / 9f
    @Volatile private var viewScaleY = 1f
    /** Landmarks of visible hands (x, y pairs, 21 points each), for the white hand overlay. */
    @Volatile private var handPoints: List<FloatArray> = emptyList()
    @Volatile private var pinchPoint: FloatArray? = null

    /** Pointer ray in world space (from the eyes), or null without a hand. */
    @Volatile private var ray: FloatArray? = null
    @Volatile private var hit: Hit? = null
    @Volatile private var pressing = false
    private var focused: VrWindow? = null
        set(value) {
            field = value
            // Joy-Con play an app window (Minecraft) as a gamepad; elsewhere they point and click.
            CinemaActivity.setJoyConPassthrough(this, value?.content is ShizukuAppContent)
        }
    private var drag: Drag? = null
    private var pressedHit: Hit? = null
    private val pinchLatch = HandGestures.PinchLatch()
    private var wasPinching = false
    private var joyConDown = false
    private var stickHeldUntil = 0L
    private val filterX = HandGestures.OneEuro(minCutoff = .8f, beta = 2.5f)
    private val filterY = HandGestures.OneEuro(minCutoff = .8f, beta = 2.5f)
    private var lastMoveSent = 0L

    private var games = emptyMap<String, GameLibrary.Game>()
    private var storeApps = emptyList<WebApps.App>()

    private sealed class Hit {
        data class Panel(val u: Float, val v: Float) : Hit()
        data class Content(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class Bar(val window: VrWindow) : Hit()
        data class Minimize(val window: VrWindow) : Hit()
        data class Close(val window: VrWindow) : Hit()
        data class Toolbar(val window: VrWindow, val u: Float) : Hit()
        data class Resize(val window: VrWindow) : Hit()
        data class KeyboardButton(val window: VrWindow) : Hit()
        data class Keyboard(val window: VrWindow, val u: Float, val v: Float) : Hit()
    }

    /** The window the VR keyboard types into, if it is shown. */
    private fun keyboardWindow(): VrWindow? {
        val manual = manualKeyboard?.takeIf { it in windows && !it.minimized }
        if (manual != null) return manual
        return windows.firstOrNull { !it.minimized && it.content.keyboardRequested }
    }

    private fun keyboardCenterY(window: VrWindow) = window.height - window.heightM / 2 - BAR_OFFSET - .09f - KEYBOARD_H / 2

    private data class Drag(val window: VrWindow, val startYaw: Float, val startHeight: Float, val pointerYaw: Float, val pointerHeight: Float, val resize: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(Renderer())
            setOnClickListener { tracker.recenter() }
        }
        setContentView(surfaceView)
        trackingExecutor.execute {
            handTracker = runCatching { HandTracker(this, useGpu = true, onResult = ::onHands) }
                .getOrElse { HandTracker(this, useGpu = false, onResult = ::onHands) }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        stopService(Intent(this, HandTrackingService::class.java))
        surfaceView.onResume()
        tracker.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else toast("Разрешите PhoneXR доступ к камере")
        joyConReceiver = JoyConBridge.listen(this) { onJoyCon(it) }
        JoyConBridge.watch(this, watching = true, learning = false)
        CinemaActivity.setJoyConPassthrough(this, false)
        loadApps()
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        surfaceView.onPause()
        tracker.stop()
        cameraProvider?.unbindAll()
        JoyConBridge.watch(this, watching = false, learning = false)
        joyConReceiver?.let { unregisterReceiver(it) }
        joyConReceiver = null
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        windows.forEach { it.content.release() }
        cameraExecutor.shutdownNow()
        trackingExecutor.execute { handTracker?.close() }
        trackingExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Apps and windows

    private fun loadApps() {
        thread(name = "PhoneXR home apps") {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
                .filter { it.kind != GameLibrary.Kind.GEAR_VR_ORIGINAL && it.kind != GameLibrary.Kind.GEAR_VR_UNSUPPORTED }
            games = found.associateBy { it.packageName }
            // Minecraft stays in the PhoneXR app (PXR Bedrock), not on the MR home screen.
            val own = listOf(
                HomePanel.Entry(ID_BROWSER, "Браузер", drawBrowserIcon()),
                HomePanel.Entry(ID_PHOTOS, "Фото", drawPhotosIcon()),
                HomePanel.Entry(ID_STORE, "Магазин", drawStoreIcon()),
            )
            val vr = found.map {
                HomePanel.Entry("app:${it.packageName}", it.label, runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull())
            }
            val web = WebApps.installed(this).map { app ->
                HomePanel.Entry("web:${app.url}", app.name, WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name))
            }
            synchronized(panel) { panel.setHome(own + vr + web) }
            updateDock()
            storeApps = WebApps.fromStore()
            refreshStore()
        }
    }

    private fun refreshStore() {
        val installed = WebApps.installed(this).map { it.url }.toSet()
        val entries = storeApps.map { app ->
            HomePanel.Entry(
                "store:${app.url}", app.name,
                WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name),
                badge = if (app.url in installed) "✓" else "+"
            )
        }
        synchronized(panel) { panel.setStore(entries) }
        redraw.set(true)
    }

    private fun openEntry(entry: HomePanel.Entry) {
        val id = entry.id
        when {
            id == ID_BROWSER -> openWindow("browser", "Браузер", ID_BROWSER) { BrowserContent(BrowserContent.HOME, ::openWebXr) }
            id == ID_PHOTOS -> openWindow("photos", "Фото", ID_PHOTOS) { PhotosContent(this) }
            id == ID_MINECRAFT -> {
                if (runCatching { packageManager.getApplicationInfo(MINECRAFT, 0) }.isFailure) {
                    toast("Установите Minecraft из Google Play")
                } else {
                    openWindow("minecraft", "Minecraft", ID_MINECRAFT) { ShizukuAppContent(MINECRAFT) { toast(it) } }
                }
            }
            id == ID_STORE -> switchMode(HomePanel.Mode.STORE)
            id.startsWith("app:") -> launchGame(id.removePrefix("app:"))
            id.startsWith("web:") -> id.removePrefix("web:").let { url -> openWindow("web:$url", entry.label, id) { BrowserContent(url, ::openWebXr) } }
            id.startsWith("dock:") -> windows.firstOrNull { it.id == id.removePrefix("dock:") }?.let { restore(it) }
            id.startsWith("store:") -> {
                val app = storeApps.firstOrNull { it.url == id.removePrefix("store:") } ?: return
                if (WebApps.installed(this).any { it.url == app.url }) {
                    openWindow("web:${app.url}", app.name, "web:${app.url}") { BrowserContent(app.url, ::openWebXr) }
                } else {
                    WebApps.add(this, app)
                    toast("«${app.name}» добавлено на главный экран")
                    thread { refreshStore(); loadApps() }
                }
            }
            id == MENU_PHOTO -> takePhoto()
            id == MENU_RECENTER -> { tracker.recenter(); switchMode(HomePanel.Mode.HOME) }
            id == MENU_HOME -> switchMode(HomePanel.Mode.HOME)
            id == MENU_EXIT -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }

    /** Opens a window, or brings back the one already open with that id. */
    private fun openWindow(id: String, title: String, iconId: String, content: () -> VrWindow.Content) {
        windows.firstOrNull { it.id == id }?.let { return restore(it) }
        val window = VrWindow(id, title, iconId, content())
        val open = windows.count { !it.minimized }
        window.yaw = if (open == 0) 0f else (if (open % 2 == 1) -32f else 32f) * ((open + 1) / 2)
        window.height = .05f
        windows += window
        focused = window
        switchMode(HomePanel.Mode.HOME)
        glTasks += {
            val external = window.content.external
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textures[window.id] = ids[0]
            val surface = if (external) SurfaceTexture(ids[0]).also { texture ->
                texture.setOnFrameAvailableListener { framesReady += window.id }
                surfaceTextures[window.id] = texture
            } else null
            window.content.attach(this, surface) {}
        }
    }

    private fun restore(window: VrWindow) {
        window.minimized = false
        focused = window
        updateDock()
    }

    private fun minimize(window: VrWindow) {
        window.minimized = true
        if (focused == window) focused = null
        updateDock()
    }

    private fun close(window: VrWindow) {
        windows.remove(window)
        if (focused == window) focused = null
        window.content.release()
        glTasks += {
            textures.remove(window.id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            surfaceTextures.remove(window.id)?.release()
        }
        updateDock()
    }

    private fun updateDock() {
        val icons = synchronized(panel) { panel.homeIcons() }
        val dock = windows.filter { it.minimized }.map { window ->
            HomePanel.Entry("dock:${window.id}", window.title, icons[window.iconId] ?: letterIcon(window.title))
        }
        synchronized(panel) { panel.setDock(dock) }
        redraw.set(true)
    }

    /** A page asked for an immersive WebXR session: the WebView has none, so the PhoneXR browser (OpenXR) takes over. */
    private fun openWebXr(url: String) = runOnUiThread {
        if (WebApps.browserPackage(this) == null) return@runOnUiThread toast("Для WebXR установите «Браузер PhoneXR» с сайта или из магазина")
        cameraProvider?.unbindAll()
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        if (!WebApps.open(this, url)) toast("Не удалось открыть WebXR")
    }

    /** Real photo: the current passthrough frame goes to the gallery (Pictures/PhoneXR). */
    private fun takePhoto() {
        val bitmap = synchronized(frameLock) { frame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) }
            ?: return toast("Камера ещё не готова")
        switchMode(HomePanel.Mode.HOME)
        thread(name = "PhoneXR photo") {
            val saved = runCatching { Daydream.savePhoto(this, bitmap) }.isSuccess
            bitmap.recycle()
            toast(if (saved) "Фото сохранено в «Фото»" else "Не удалось сохранить фото")
            windows.forEach { (it.content as? PhotosContent)?.reload() }
        }
    }

    private fun launchGame(packageName: String) {
        val game = games[packageName] ?: return
        val intent = GameLibrary.launchIntent(this, game) ?: return toast("У «${game.label}» нет экрана запуска")
        if (game.kind == GameLibrary.Kind.DAYDREAM && !Daydream.servicesInstalled(this)) {
            toast("Для Daydream нужны VR Services — ставлю Opendream Services")
            runOnUiThread { Daydream.installServices(this) }
            return
        }
        recent.remove(packageName)
        recent.add(0, packageName)
        cameraProvider?.unbindAll()
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** System menu: open windows and recent games for switching, recenter, home, leave VR. */
    private fun openMenu() {
        val icons = synchronized(panel) { panel.homeIcons() }
        val entries = windows.map { HomePanel.Entry("dock:${it.id}", it.title, icons[it.iconId] ?: letterIcon(it.title)) } +
            recent.take(3).mapNotNull { name ->
                games[name]?.let { HomePanel.Entry("app:$name", it.label, runCatching { packageManager.getApplicationIcon(name) }.getOrNull()) }
            } + listOf(
            HomePanel.Entry(MENU_HOME, "Главная", symbolIcon("⌂", Color.rgb(90, 90, 100))),
            HomePanel.Entry(MENU_PHOTO, "Снять фото", symbolIcon("◉", Color.rgb(255, 159, 10))),
            HomePanel.Entry(MENU_RECENTER, "Выровнять", symbolIcon("◎", Color.rgb(48, 176, 199))),
            HomePanel.Entry(MENU_EXIT, "Выйти из VR", symbolIcon("✕", Color.rgb(255, 69, 58))),
        )
        synchronized(panel) { panel.setMenu(entries) }
        switchMode(HomePanel.Mode.MENU)
    }

    private fun switchMode(mode: HomePanel.Mode) {
        synchronized(panel) {
            panel.mode = mode
            panel.showPage(0)
        }
        redraw.set(true)
    }

    // ------------------------------------------------------------------ Camera and hands

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val upright = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                    synchronized(frameLock) {
                        val old = frame
                        frame = upright
                        if (frameFresh.getAndSet(true) && old != null && old !== upright) old.recycle()
                    }
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    if (busy.compareAndSet(false, true)) {
                        val small = Bitmap.createScaledBitmap(upright, 640, 640 * upright.height / upright.width, true)
                        trackingExecutor.execute {
                            try { handTracker?.detect(small, timestamp) } finally { small.recycle(); busy.set(false) }
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Camera frame failed", error)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
                .onFailure { toast("Камера занята другим приложением") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Joy-Con clicks only when "controllers" is chosen in settings and one is connected; otherwise hands click. */
    private fun controllersClick(): Boolean =
        Settings.load(this).handMode == Settings.HandMode.CONTROLLERS &&
            InputDevice.getDeviceIds().any { JoyConButtons.isJoyCon(InputDevice.getDevice(it)) }

    private fun onHands(result: HandLandmarkerResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        var best: HandGestures.Shape? = null
        handPoints = result.landmarks().filter { it.size >= 21 }.map { points ->
            FloatArray(42) { i -> if (i % 2 == 0) points[i / 2].x() else points[i / 2].y() }
        }
        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val physicalLeft = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true)
            val shape = HandGestures.shape(points, physicalLeft)
            val current = best
            if (current == null || shape.palmWidth > current.palmWidth) best = shape
        }
        val hand = best
        if (hand == null) {
            filterX.reset(); filterY.reset(); pinchLatch.reset()
            ray = null
            pinchPoint = null
            if (!controllersClick()) release()
            updateHit(null)
            return
        }
        val x = filterX.filter(hand.pinchX, now)
        val y = filterY.filter(hand.pinchY, now)
        val direction = pointerRay(x, y)
        ray = direction
        pinchPoint = floatArrayOf(x, y)
        val target = hitTest(direction)
        updateHit(target)
        if (controllersClick()) {
            dragOrMove(direction)
            return
        }
        val pinching = pinchLatch.update(hand)
        if (pinching && !wasPinching) {
            wasPinching = true
            if (hand.palmToFace) {
                runOnUiThread { if (panel.mode == HomePanel.Mode.MENU) switchMode(HomePanel.Mode.HOME) else openMenu() }
            } else {
                press(target, direction)
            }
        } else if (pinching) {
            dragOrMove(direction)
        } else if (wasPinching) {
            wasPinching = false
            release()
        }
    }

    private fun onJoyCon(snapshot: JoyConBridge.Snapshot) {
        if (!controllersClick()) return
        val buttons = snapshot.left.buttons or snapshot.right.buttons
        val down = buttons and (JoyConButtons.TRIGGER or JoyConButtons.PRIMARY) != 0
        if (down && !joyConDown) press(hit, ray)
        if (!down && joyConDown) release()
        joyConDown = down
        if (buttons and JoyConButtons.MENU != 0) openMenu()
        val stick = if (abs(snapshot.right.stickX) > abs(snapshot.left.stickX)) snapshot.right.stickX else snapshot.left.stickX
        if (abs(stick) > .7f && SystemClock.uptimeMillis() > stickHeldUntil) {
            stickHeldUntil = SystemClock.uptimeMillis() + 450
            synchronized(panel) { panel.turnPage(if (stick > 0) 1 else -1) }
            redraw.set(true)
        }
    }

    /** The fingers' pinch point seen from the eyes, as a world direction. */
    private fun pointerRay(x: Float, y: Float): FloatArray {
        val head = FloatArray(16)
        tracker.copyHead(head)
        val local = floatArrayOf((x - .5f) * 2f * viewScaleX, (.5f - y) * 2f * viewScaleY, -1f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, local, 0)
        return world
    }

    private fun yawOf(direction: FloatArray) = Math.toDegrees(atan2(-direction[0], -direction[2]).toDouble()).toFloat()
    private fun heightAt(direction: FloatArray, radius: Float) = radius * direction[1] / hypot(direction[0], direction[2])

    private fun hitTest(direction: FloatArray?): Hit? {
        direction ?: return null
        // The keyboard floats closest to the user.
        keyboardWindow()?.let { window ->
            val local = toLocal(direction, window.yaw, KEYBOARD_RADIUS)
            if (local != null) {
                val u = (local[0] + KEYBOARD_W / 2) / KEYBOARD_W
                val v = (keyboardCenterY(window) + KEYBOARD_H / 2 - local[1]) / KEYBOARD_H
                if (u in 0f..1f && v in 0f..1f) return Hit.Keyboard(window, u, v)
            }
        }
        // Windows are in front of the icons; the focused one first.
        val ordered = windows.filter { !it.minimized }.sortedByDescending { it == focused }
        for (window in ordered) {
            val local = toLocal(direction, window.yaw, VrWindow.RADIUS) ?: continue
            val x = local[0]
            val y = local[1] - window.height
            val w = window.width / 2
            val h = window.heightM / 2
            // Corner handle: drag to resize.
            if (x > w - .05f && x < w + .09f && y < -h + .05f && y > -h - .09f && !(abs(x) <= w - .06f)) return Hit.Resize(window)
            if (abs(x) <= w && abs(y) <= h) return Hit.Content(window, (x + w) / window.width, (h - y) / window.heightM)
            if (window.content.toolbarTitle() != null && abs(y - (h + TOOLBAR_GAP + TOOLBAR_H / 2)) < TOOLBAR_H / 2 && abs(x) < TOOLBAR_W / 2) {
                return Hit.Toolbar(window, (x + TOOLBAR_W / 2) / TOOLBAR_W)
            }
            val barY = -h - BAR_OFFSET
            if (abs(y - barY) < .05f) {
                if (abs(x) < .22f) return Hit.Bar(window)
                if (abs(x + BUTTON_X) < .05f) return Hit.Minimize(window)
                if (abs(x - BUTTON_X) < .05f) return Hit.Close(window)
                if (abs(x + BUTTON_X + KEYBOARD_BUTTON_GAP) < .05f) return Hit.KeyboardButton(window)
            }
        }
        val local = toLocal(direction, 0f, PANEL_RADIUS) ?: return null
        val u = (local[0] + PANEL_WIDTH / 2) / PANEL_WIDTH
        val v = (PANEL_HEIGHT / 2 - local[1]) / PANEL_HEIGHT
        return if (u in 0f..1f && v in 0f..1f) Hit.Panel(u, v) else null
    }

    /** Where a ray from the eyes crosses the plane of a window at [yaw] and [radius], in that window's frame. */
    private fun toLocal(direction: FloatArray, yaw: Float, radius: Float): FloatArray? {
        val rotate = FloatArray(16)
        Matrix.setRotateM(rotate, 0, -yaw, 0f, 1f, 0f)
        val d = FloatArray(4)
        Matrix.multiplyMV(d, 0, rotate, 0, direction, 0)
        if (d[2] >= -1e-3f) return null
        val t = -radius / d[2]
        return floatArrayOf(d[0] * t, d[1] * t)
    }

    private fun updateHit(target: Hit?) {
        val key = (target as? Hit.Keyboard)?.let { keyboard.hovered(it.u, it.v) }
        if (key != hoveredKey) {
            hoveredKey = key
            keyboardRedraw.set(true)
        }
        val panelTarget = (target as? Hit.Panel)?.let { synchronized(panel) { panel.hit(it.u, it.v) } }
        val previous = hit
        hit = target
        if (panelTarget != hoveredPanel || (previous is Hit.Panel) != (target is Hit.Panel)) {
            hoveredPanel = panelTarget
            redraw.set(true)
        }
    }

    @Volatile private var hoveredPanel: HomePanel.Target? = null

    private fun press(target: Hit?, direction: FloatArray?) {
        pressing = true
        pressedHit = target
        when (target) {
            is Hit.Panel -> {
                val item = synchronized(panel) { panel.hit(target.u, target.v) } ?: return
                runOnUiThread {
                    when (item) {
                        is HomePanel.Target.App -> openEntry(item.entry)
                        is HomePanel.Target.Page -> synchronized(panel) { panel.showPage(item.index) }
                    }
                    redraw.set(true)
                }
            }
            is Hit.Content -> {
                focused = target.window
                target.window.content.touch(MotionEvent.ACTION_DOWN, target.u, target.v)
            }
            is Hit.Bar -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.yaw, target.window.height, yawOf(direction), heightAt(direction, VrWindow.RADIUS))
            }
            is Hit.Toolbar -> {
                focused = target.window
                target.window.content.toolbarAction(
                    when {
                        target.u < TOOLBAR_BUTTON -> "back"
                        target.u < TOOLBAR_BUTTON * 2 -> "forward"
                        target.u > 1 - TOOLBAR_BUTTON -> "reload"
                        else -> "home"
                    }
                )
            }
            is Hit.Resize -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.scale, 0f, 0f, 0f, resize = true)
            }
            is Hit.Keyboard -> {
                val key = keyboard.press(target.u, target.v)
                keyboardRedraw.set(true)
                when (key) {
                    null -> Unit
                    KeyboardPanel.HIDE -> {
                        manualKeyboard = null
                        target.window.content.hideKeyboard()
                    }
                    else -> target.window.content.type(key)
                }
            }
            is Hit.KeyboardButton -> {
                focused = target.window
                if (keyboardWindow() == target.window) {
                    manualKeyboard = null
                    target.window.content.hideKeyboard()
                } else {
                    manualKeyboard = target.window
                }
                keyboardRedraw.set(true)
            }
            is Hit.Minimize -> runOnUiThread { minimize(target.window) }
            is Hit.Close -> runOnUiThread { close(target.window) }
            null -> Unit
        }
    }

    private fun dragOrMove(direction: FloatArray) {
        if (!pressing) return
        drag?.let { d ->
            if (d.resize) {
                // The window keeps its centre; the corner follows the pointer.
                val local = toLocal(direction, d.window.yaw, VrWindow.RADIUS) ?: return
                val byWidth = 2 * local[0] / VrWindow.WIDTH_M
                val aspect = d.window.content.pixelHeight.toFloat() / d.window.content.pixelWidth
                val byHeight = 2 * (d.window.height - local[1]) / (VrWindow.WIDTH_M * aspect)
                d.window.scale = maxOf(byWidth, byHeight).coerceIn(MIN_SCALE, MAX_SCALE)
                return
            }
            d.window.yaw = d.startYaw + (yawOf(direction) - d.pointerYaw)
            d.window.height = d.startHeight + (heightAt(direction, VrWindow.RADIUS) - d.pointerHeight)
            return
        }
        val pressed = pressedHit as? Hit.Content ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveSent < 16) return
        lastMoveSent = now
        val local = toLocal(direction, pressed.window.yaw, VrWindow.RADIUS) ?: return
        val w = pressed.window.width / 2
        val h = pressed.window.heightM / 2
        val u = ((local[0] + w) / pressed.window.width).coerceIn(0f, 1f)
        val v = ((h - (local[1] - pressed.window.height)) / pressed.window.heightM).coerceIn(0f, 1f)
        pressed.window.content.touch(MotionEvent.ACTION_MOVE, u, v)
    }

    private fun release() {
        if (!pressing) return
        pressing = false
        drag = null
        val pressed = pressedHit as? Hit.Content
        if (pressed != null) {
            val current = hit as? Hit.Content
            val u = if (current?.window == pressed.window) current.u else pressed.u
            val v = if (current?.window == pressed.window) current.v else pressed.v
            pressed.window.content.touch(MotionEvent.ACTION_UP, u, v)
        }
        pressedHit = null
    }

    // ------------------------------------------------------------------ Gamepad goes to the focused window

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_DPAD)) {
            return super.dispatchKeyEvent(event)
        }
        val window = focused?.takeIf { !it.minimized }
        if (window != null) {
            window.content.key(event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> hit?.let { press(it, ray); release() }
                KeyEvent.KEYCODE_BUTTON_START -> openMenu()
                KeyEvent.KEYCODE_BUTTON_B -> switchMode(HomePanel.Mode.HOME)
                KeyEvent.KEYCODE_BUTTON_R1 -> { synchronized(panel) { panel.turnPage(1) }; redraw.set(true) }
                KeyEvent.KEYCODE_BUTTON_L1 -> { synchronized(panel) { panel.turnPage(-1) }; redraw.set(true) }
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val window = focused?.takeIf { !it.minimized }
        if (window != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            window.content.motion(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.mode != HomePanel.Mode.HOME) switchMode(HomePanel.Mode.HOME) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ Icons of the built-in apps

    private fun drawBrowserIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, Color.rgb(90, 200, 250), Color.rgb(0, 122, 255), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .035f
        canvas.drawCircle(size / 2, size / 2, size * .32f, paint)
        paint.style = Paint.Style.FILL
        val c = size / 2
        paint.color = Color.rgb(255, 59, 48)
        canvas.drawPath(Path().apply { moveTo(c + size * .2f, c - size * .2f); lineTo(c - size * .05f, c - size * .05f); lineTo(c + size * .05f, c + size * .05f); close() }, paint)
        paint.color = Color.WHITE
        canvas.drawPath(Path().apply { moveTo(c - size * .2f, c + size * .2f); lineTo(c - size * .05f, c - size * .05f); lineTo(c + size * .05f, c + size * .05f); close() }, paint)
    }

    private fun drawStoreIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, Color.rgb(255, 159, 10), Color.rgb(255, 94, 58), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(size * .28f, size * .38f, size * .72f, size * .74f), size * .05f, size * .05f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .04f
        canvas.drawArc(RectF(size * .38f, size * .24f, size * .62f, size * .48f), 180f, 180f, false, paint)
    }

    private fun drawPhotosIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.WHITE)
        val colors = intArrayOf(
            Color.rgb(255, 204, 0), Color.rgb(255, 149, 0), Color.rgb(255, 59, 48), Color.rgb(255, 45, 85),
            Color.rgb(175, 82, 222), Color.rgb(0, 122, 255), Color.rgb(52, 199, 89), Color.rgb(163, 212, 64),
        )
        colors.forEachIndexed { i, color ->
            paint.color = (color and 0x00ffffff) or (0xd0 shl 24)
            canvas.save()
            canvas.rotate(i * 45f, size / 2, size / 2)
            canvas.drawRoundRect(RectF(size * .42f, size * .14f, size * .58f, size * .5f), size * .08f, size * .08f, paint)
            canvas.restore()
        }
    }

    private fun drawMinecraftIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint()
        paint.color = Color.rgb(121, 85, 58)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.color = Color.rgb(95, 159, 53)
        canvas.drawRect(0f, 0f, size, size * .4f, paint)
        paint.color = Color.rgb(94, 64, 42)
        for (i in 0 until 8) canvas.drawRect(i * size / 8, size * .5f + (i % 3) * size * .12f, (i + 1) * size / 8, size * .6f + (i % 3) * size * .12f, paint)
    }

    private fun symbolIcon(symbol: String, color: Int): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.color = Color.WHITE
        paint.textSize = size * .42f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(symbol, size / 2, size / 2 + size * .15f, paint)
    }

    private fun letterIcon(name: String): Drawable = symbolIcon(name.take(1).uppercase(), Color.rgb(88, 86, 214))

    private fun iconCanvas(paint: (Canvas, Float) -> Unit): Drawable {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        paint(Canvas(bitmap), 256f)
        return BitmapDrawable(resources, bitmap)
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------ Rendering

    private inner class Renderer : GLSurfaceView.Renderer {
        private var panelTexture = 0
        private var cameraTexture = 0
        private var controlsTexture = 0
        private var keyboardTexture = 0
        private var textureProgram = 0
        private var externalProgram = 0
        private var colorProgram = 0
        private var roundedProgram = 0
        private var roundedExternalProgram = 0
        private val toolbarTextures = HashMap<String, Pair<Int, Int>>()
        private var hasCamera = false
        private var cameraAspect = 16f / 9f
        private var width = 1
        private var height = 1
        private val head = FloatArray(16)
        private val worldToHead = FloatArray(16)
        private val projection = FloatArray(16)
        private val eye = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val modelView = FloatArray(16)
        private val mvp = FloatArray(16)
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            textureProgram = CinemaRenderer.program(TEXTURE_VERTEX, TEXTURE_FRAGMENT)
            externalProgram = CinemaRenderer.program(TEXTURE_VERTEX, EXTERNAL_FRAGMENT)
            colorProgram = CinemaRenderer.program(COLOR_VERTEX, COLOR_FRAGMENT)
            roundedProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_FRAGMENT)
            roundedExternalProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_EXTERNAL_FRAGMENT)
            val ids = IntArray(3)
            GLES20.glGenTextures(3, ids, 0)
            panelTexture = ids[0]
            cameraTexture = ids[1]
            controlsTexture = ids[2]
            for (id in ids) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, controlsTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawControls(), 0)
            keyboardTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            keyboardRedraw.set(true)
            redraw.set(true)
        }

        override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
            width = w
            height = h
        }

        override fun onDrawFrame(unused: GL10?) {
            while (true) glTasks.poll()?.invoke() ?: break
            if (redraw.getAndSet(false)) {
                synchronized(panel) {
                    panel.draw(hoveredPanel, pressing)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, panel.bitmap, 0)
                }
            }
            val typing = keyboardWindow()
            if (typing != null && keyboardRedraw.getAndSet(false)) {
                keyboard.draw(hoveredKey)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, keyboard.bitmap, 0)
            }
            synchronized(frameLock) {
                val bitmap = frame
                if (bitmap != null && frameFresh.getAndSet(false) && !bitmap.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    cameraAspect = bitmap.width.toFloat() / bitmap.height
                    hasCamera = true
                }
            }
            for (window in windows) {
                if (framesReady.remove(window.id)) surfaceTextures[window.id]?.updateTexImage()
                window.content.takeBitmap()?.let { bitmap ->
                    textures[window.id]?.let { id ->
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                        synchronized(window.content) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
                    }
                }
            }

            GLES20.glClearColor(.08f, .08f, .1f, 1f)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            tracker.copyHead(head)
            Matrix.transposeM(worldToHead, 0, head, 0)
            val eyeWidth = width / 2
            Matrix.perspectiveM(projection, 0, 90f, eyeWidth.toFloat() / height, .05f, 100f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val eyeAspect = eyeWidth.toFloat() / height
            // Keep the pointer mapping in step with how the camera image is laid out below.
            if (eyeAspect < cameraAspect) {
                viewScaleX = cameraAspect
                viewScaleY = 1f
            } else {
                viewScaleX = eyeAspect
                viewScaleY = eyeAspect / cameraAspect
            }
            for (index in 0..1) {
                GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
                // Passthrough fills each eye; the camera image is cropped to the eye's shape.
                if (hasCamera) {
                    val (u0, u1, v0, v1) = if (eyeAspect < cameraAspect) {
                        val span = eyeAspect / cameraAspect
                        listOf(.5f - span / 2, .5f + span / 2, 0f, 1f)
                    } else {
                        val span = cameraAspect / eyeAspect
                        listOf(0f, 1f, .5f - span / 2, .5f + span / 2)
                    }
                    quad(textureProgram, cameraTexture, identity, floatArrayOf(-1f, -1f, 0f, u0, v1, 1f, -1f, 0f, u1, v1, -1f, 1f, 0f, u0, v0, 1f, 1f, 0f, u1, v0))
                }
                Matrix.setIdentityM(eye, 0)
                Matrix.translateM(eye, 0, if (index == 0) .032f else -.032f, 0f, 0f)
                Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)

                // Home icons.
                Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
                val pw = PANEL_WIDTH / 2
                val ph = PANEL_HEIGHT / 2
                val pz = -PANEL_RADIUS
                quad(textureProgram, panelTexture, mvp, floatArrayOf(-pw, -ph, pz, 0f, 1f, pw, -ph, pz, 1f, 1f, -pw, ph, pz, 0f, 0f, pw, ph, pz, 1f, 0f))

                // Windows, focused last so it is on top.
                val ordered = windows.filter { !it.minimized }.sortedBy { it == focused }
                for (window in ordered) {
                    val texture = textures[window.id] ?: continue
                    Matrix.setRotateM(model, 0, window.yaw, 0f, 1f, 0f)
                    Matrix.translateM(model, 0, 0f, window.height, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val w = window.width / 2
                    val h = window.heightM / 2
                    val z = -VrWindow.RADIUS
                    val uv = window.content.uv(index)
                    val program = if (window.content.external) roundedExternalProgram else roundedProgram
                    GLES20.glUseProgram(program)
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHalf"), window.width / 2, window.heightM / 2)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRadius"), CORNER_RADIUS)
                    val top = uv[1]
                    val bottom = uv[3]
                    quad(program, texture, mvp, floatArrayOf(-w, -h, z, uv[0], bottom, w, -h, z, uv[2], bottom, -w, h, z, uv[0], top, w, h, z, uv[2], top), window.content.external)
                    window.content.toolbarTitle()?.let { title -> toolbar(window, title, mvp, h, z) }
                    // Controls under the window: minimize, move bar, close.
                    val barY = -h - BAR_OFFSET
                    val hovered = hit
                    val dragging = drag
                    control(mvp, -BUTTON_X - KEYBOARD_BUTTON_GAP, barY, .035f, .035f, .8f, 1f, hovered is Hit.KeyboardButton && hovered.window == window || typing == window, z)
                    control(mvp, -BUTTON_X, barY, .035f, .035f, 0f, .2f, hovered is Hit.Minimize && hovered.window == window, z)
                    control(mvp, 0f, barY, .2f, .018f, .2f, .6f, hovered is Hit.Bar && hovered.window == window || dragging?.window == window && !dragging.resize, z)
                    control(mvp, BUTTON_X, barY, .035f, .035f, .6f, .8f, hovered is Hit.Close && hovered.window == window, z)
                    resizeHandle(mvp, w, h, z, hovered is Hit.Resize && hovered.window == window || dragging?.window == window && dragging.resize)
                }
                if (typing != null) {
                    Matrix.setRotateM(model, 0, typing.yaw, 0f, 1f, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val kw = KEYBOARD_W / 2
                    val kh = KEYBOARD_H / 2
                    val ky = keyboardCenterY(typing)
                    val kz = -KEYBOARD_RADIUS
                    quad(textureProgram, keyboardTexture, mvp, floatArrayOf(-kw, ky - kh, kz, 0f, 1f, kw, ky - kh, kz, 1f, 1f, -kw, ky + kh, kz, 0f, 0f, kw, ky + kh, kz, 1f, 0f))
                }
                hand()
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        /** visionOS-style corner arc at the bottom right: drag it to resize the window. */
        private fun resizeHandle(mvp: FloatArray, w: Float, h: Float, z: Float, active: Boolean) {
            val alpha = if (active) 1f else .7f
            val thick = if (active) .014f else .01f
            val points = ArrayList<Float>()
            val cx = w - CORNER_RADIUS
            val cy = -h + CORNER_RADIUS
            val r = CORNER_RADIUS + .035f
            for (i in 0..12) {
                val angle = Math.toRadians(-90.0 + i * 90.0 / 12).toFloat()
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                for (radius in floatArrayOf(r - thick, r + thick)) {
                    points += listOf(cx + cos * radius, cy + sin * radius, z, 0f, 0f)
                }
            }
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, alpha)
            val data = points.toFloatArray()
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, data.size / 5)
        }

        private fun control(mvp: FloatArray, x: Float, y: Float, hw: Float, hh: Float, u0: Float, u1: Float, active: Boolean, z: Float) {
            val grow = if (active) 1.25f else 1f
            val w = hw * grow
            val h = hh * grow
            quad(textureProgram, controlsTexture, mvp, floatArrayOf(x - w, y - h, z, u0, 1f, x + w, y - h, z, u1, 1f, x - w, y + h, z, u0, 0f, x + w, y + h, z, u1, 0f))
        }

        /** Safari-style bar above a window: back, forward, address, reload. */
        private fun toolbar(window: VrWindow, title: String, mvp: FloatArray, h: Float, z: Float) {
            val version = window.content.toolbarVersion
            val cached = toolbarTextures[window.id]
            val texture = if (cached == null || cached.second != version) {
                val id = cached?.first ?: IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawToolbar(title), 0)
                toolbarTextures[window.id] = id to version
                id
            } else cached.first
            val y = h + TOOLBAR_GAP + TOOLBAR_H / 2
            val w = TOOLBAR_W / 2
            val th = TOOLBAR_H / 2
            quad(textureProgram, texture, mvp, floatArrayOf(-w, y - th, z, 0f, 1f, w, y - th, z, 1f, 1f, -w, y + th, z, 0f, 0f, w, y + th, z, 1f, 0f))
        }

        private fun drawToolbar(title: String): Bitmap {
            val bitmap = Bitmap.createBitmap(1200, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(185, 70, 64, 58)
            canvas.drawRoundRect(RectF(0f, 0f, 1200f, 140f), 70f, 70f, paint)
            paint.color = Color.argb(110, 30, 26, 22)
            canvas.drawRoundRect(RectF(270f, 22f, 1050f, 118f), 48f, 48f, paint)
            paint.color = Color.WHITE
            paint.textSize = 56f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("‹", 70f, 88f, paint)
            canvas.drawText("›", 190f, 88f, paint)
            canvas.drawText("↻", 1130f, 90f, paint)
            paint.textSize = 42f
            canvas.drawText(title, 660f, 84f, paint)
            return bitmap
        }

        /**
         * The user's hand as a white skeleton over the passthrough, and the cursor on the pinch point.
         * Drawn in head space with the passthrough's own mapping, so it lines up with the real fingers.
         */
        private fun hand() {
            val hands = handPoints
            Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, .92f)
            GLES20.glLineWidth(7f)
            for (points in hands) {
                val lines = FloatArray(HAND_BONES.size * 10)
                HAND_BONES.forEachIndexed { i, (a, b) ->
                    for ((k, index) in listOf(a, b).withIndex()) {
                        val offset = i * 10 + k * 5
                        lines[offset] = (points[index * 2] - .5f) * 2f * viewScaleX
                        lines[offset + 1] = (.5f - points[index * 2 + 1]) * 2f * viewScaleY
                        lines[offset + 2] = -1f
                    }
                }
                val buffer = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(lines)
                buffer.position(0)
                val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
                GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, HAND_BONES.size * 2)
            }
            val point = pinchPoint ?: return
            val x = (point[0] - .5f) * 2f * viewScaleX
            val y = (.5f - point[1]) * 2f * viewScaleY
            val r = if (pressing) .012f else .02f
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, 1f)
            draw(colorProgram, floatArrayOf(x - r, y - r, -1f, 0f, 0f, x + r, y - r, -1f, 0f, 0f, x - r, y + r, -1f, 0f, 0f, x + r, y + r, -1f, 0f, 0f), false)
        }

        private fun quad(program: Int, texture: Int, matrix: FloatArray, data: FloatArray, external: Boolean = false) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, matrix, 0)
            draw(program, data, true)
        }

        private fun draw(program: Int, data: FloatArray, textured: Boolean) {
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            if (textured) {
                val uv = GLES20.glGetAttribLocation(program, "aUv")
                buffer.position(3)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
                GLES20.glEnableVertexAttribArray(uv)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Atlas: minimize button, move bar, close button, keyboard button (visionOS window controls). */
        private fun drawControls(): Bitmap {
            val bitmap = Bitmap.createBitmap(640, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(170, 60, 60, 66)
            canvas.drawCircle(64f, 64f, 56f, paint)
            canvas.drawCircle(448f, 64f, 56f, paint)
            paint.color = Color.argb(215, 255, 255, 255)
            canvas.drawRoundRect(RectF(136f, 44f, 376f, 84f), 20f, 20f, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 9f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(40f, 64f, 88f, 64f, paint)
            canvas.drawLine(426f, 42f, 470f, 86f, paint)
            canvas.drawLine(470f, 42f, 426f, 86f, paint)
            paint.color = Color.argb(170, 60, 60, 66)
            canvas.drawCircle(576f, 64f, 56f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawRoundRect(RectF(540f, 44f, 612f, 86f), 8f, 8f, paint)
            paint.style = Paint.Style.FILL
            for (row in 0..1) for (column in 0..3) canvas.drawCircle(551f + column * 16.5f, 56f + row * 12f, 3.5f, paint)
            canvas.drawLine(556f, 79f, 596f, 79f, paint)
            return bitmap
        }
    }

    companion object {
        private const val TAG = "PhoneXR-Home"
        const val MINECRAFT = "com.mojang.minecraftpe"
        private const val ID_BROWSER = "own:browser"
        private const val ID_STORE = "own:store"
        private const val ID_PHOTOS = "own:photos"
        private const val ID_MINECRAFT = "own:minecraft"
        private const val MENU_RECENTER = "menu:recenter"
        private const val MENU_HOME = "menu:home"
        private const val MENU_EXIT = "menu:exit"
        private const val MENU_PHOTO = "menu:photo"
        private const val KEYBOARD_W = 1.05f
        private val KEYBOARD_H = KEYBOARD_W * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH
        private const val KEYBOARD_RADIUS = 1.2f
        private const val KEYBOARD_BUTTON_GAP = .1f
        private const val MIN_SCALE = .45f
        private const val MAX_SCALE = 2.2f
        private val recent = ArrayList<String>()

        private const val PANEL_RADIUS = 1.7f
        private const val PANEL_WIDTH = 2.2f
        private val PANEL_HEIGHT = PANEL_WIDTH * HomePanel.HEIGHT / HomePanel.WIDTH
        private const val BAR_OFFSET = .07f
        private const val BUTTON_X = .3f
        private const val CORNER_RADIUS = .06f
        private const val TOOLBAR_W = .95f
        private val TOOLBAR_H = TOOLBAR_W * 140f / 1200f
        private const val TOOLBAR_GAP = .03f
        /** Share of the bar width taken by each button at its ends. */
        private const val TOOLBAR_BUTTON = .1f
        /** MediaPipe hand skeleton. */
        private val HAND_BONES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
        )

        private const val TEXTURE_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val TEXTURE_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        private const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        /** Windows with rounded corners, like visionOS; uHalf is half the size in metres. */
        private const val ROUNDED_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() { vUv = aUv; vLocal = aPosition.xy; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val ROUNDED_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                vec4 color = texture2D(uTexture, vUv);
                gl_FragColor = vec4(color.rgb, color.a * alpha);
            }"""
        private const val ROUNDED_EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, alpha);
            }"""
        private const val COLOR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }"""
    }
}
