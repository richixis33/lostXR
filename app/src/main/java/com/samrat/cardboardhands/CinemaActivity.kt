package com.samrat.cardboardhands

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

/**
 * PhoneXR Cinema: runs any app (Minecraft, Roblox, Brawl Stars...) on a virtual display and shows it
 * as a big screen in VR with head tracking. Two hands touch the screen (a pinch is a finger);
 * gamepads and Joy-Con play the game directly.
 */
class CinemaActivity : Activity(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handTracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var hands: CinemaHands? = null
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: CinemaRenderer
    private lateinit var tracker: HeadTracker
    private var connection: ServiceConnection? = null
    @Volatile private var service: IDisplayService? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var displayId = -1
    @Volatile private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // The place loads in a moment; a missing or broken file falls back to a built-in scene.
        val sceneName = intent.getStringExtra(EXTRA_SCENE) ?: SCENE_ROOM
        val place: CinemaScene? = runCatching {
            when (sceneName) {
                SCENE_ROBLOX -> GlbScene.load(this, "cinema/roblox_house.glb", GlbScene.ROBLOX_HOUSE)
                SCENE_BRAWL -> PanoramaScene(assets.open("cinema/brawl.jpg").use { BitmapFactory.decodeStream(it) })
                SCENE_SKY -> null
                else -> LivingRoomScene(GlbRoom.load(this, "cinema/living_room.glb"))
            }
        }.onFailure { Log.w(TAG, "Scene $sceneName failed to load", it) }.getOrNull()
        renderer = CinemaRenderer(place, onSurface = { created ->
            surface = created
            startDisplay()
        })
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        renderer.head = tracker.head
        renderer.scene = if (sceneName == SCENE_ROOM) CinemaRenderer.Scene.ROOM else CinemaRenderer.Scene.SKY
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            // A tap on the phone recenters the view to where the head looks now.
            setOnClickListener { tracker.recenter() }
        }
        setContentView(surfaceView)

        // The OpenXR hand service would hold the camera; the cinema tracks hands itself.
        stopService(Intent(this, HandTrackingService::class.java))
        connection = VirtualScreen.bind(this) { bound ->
            service = bound
            if (bound == null) toast("Служба Shizuku отключилась") else startDisplay()
        }
        hands = CinemaHands(
            tracker,
            placement = { renderer.screenPlacement },
            onCursors = { renderer.cursors = it },
            onGhosts = { renderer.ghosts = it },
            inject = { event ->
                val id = displayId
                if (id >= 0) runCatching { service?.injectMotion(event, id) }
            },
        )
        // Minecraft VR: head turns the game's camera, gestures play (see CinemaHands.minecraft).
        val minecraft = intent.getStringExtra(EXTRA_PACKAGE) == "com.mojang.minecraftpe"
        hands?.minecraft = minecraft
        hands?.mask = HandProfile.mask(this)
        renderer.headLocked = minecraft
        if (minecraft) {
            MinecraftBridge.start()
            hands?.onConnectGesture = { typeConnect() }
        }
        if (minecraft) thread(name = "PhoneXR Minecraft look") {
            val head = FloatArray(16)
            var lastYaw = Float.NaN
            var lastPitch = 0f
            var bridgeTick = 0
            while (running) {
                tracker.copyHead(head)
                val yaw = Math.toDegrees(kotlin.math.atan2(head[8], head[10]).toDouble()).toFloat()
                val pitch = Math.toDegrees(kotlin.math.asin((-head[9]).coerceIn(-1f, 1f).toDouble())).toFloat()
                // With the mod linked, the head and hands go to it; otherwise the touch controls drive the camera.
                if (MinecraftBridge.connected) {
                    if (++bridgeTick % 3 == 0) hands?.let { MinecraftBridge.sendPose(yaw, pitch, it.bridgeHands) }
                } else if (!lastYaw.isNaN() && displayId >= 0) {
                    var dy = yaw - lastYaw
                    if (dy > 180f) dy -= 360f
                    if (dy < -180f) dy += 360f
                    hands?.look(dy, pitch - lastPitch)
                }
                lastYaw = yaw
                lastPitch = pitch
                Thread.sleep(16)
            }
        }
        trackingExecutor.execute {
            handTracker = runCatching { HandTracker(this, useGpu = true) { hands?.onResult(it) } }
                .getOrElse { HandTracker(this, useGpu = false) { hands?.onResult(it) } }
        }
    }

    /** The back camera feeds the hands; the picture itself is never shown in the cinema. */
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (busy.compareAndSet(false, true)) {
                        val upright: Bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                        val timestamp = image.imageInfo.timestamp / 1_000_000L
                        trackingExecutor.execute {
                            try {
                                handTracker?.detect(upright, timestamp)
                            } finally {
                                upright.recycle()
                                busy.set(false)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    busy.set(false)
                    Log.w(TAG, "Camera frame failed", error)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
                .onFailure { toast("Камера занята: руки в кинотеатре не работают") }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        surfaceView.onResume()
        tracker.start()
        setJoyConPassthrough(this, true)
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        cameraProvider?.unbindAll()
        hands?.releaseAll()
        surfaceView.onPause()
        tracker.stop()
        setJoyConPassthrough(this, false)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        MinecraftBridge.stop()
        cameraExecutor.shutdownNow()
        trackingExecutor.execute { handTracker?.close() }
        trackingExecutor.shutdown()
        running = false
        runCatching { service?.releaseDisplay() }
        connection?.let { VirtualScreen.unbind(this, it) }
        super.onDestroy()
    }

    /** Creates the display once both the GL surface and the Shizuku service exist, then starts the app. */
    @Synchronized
    private fun startDisplay() {
        val target = surface ?: return
        val shell = service ?: return
        if (displayId >= 0) return
        thread(name = "PhoneXR cinema start") {
            val metrics = resources.displayMetrics
            val id = runCatching {
                shell.createDisplay(target, CinemaRenderer.SCREEN_PIXELS_W, CinemaRenderer.SCREEN_PIXELS_H, 320)
            }.getOrDefault(-1)
            if (id < 0) {
                toast("Не удалось создать экран кинотеатра")
                return@thread
            }
            displayId = id
            val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return@thread
            val component = VirtualScreen.launcherComponent(this, packageName)
            if (component == null) {
                toast("У приложения нет экрана запуска")
                return@thread
            }
            val error = runCatching { shell.launch(component, id) }.getOrElse { it.message }
            if (error != null) {
                Log.w(TAG, "Launch failed: $error (density ${metrics.densityDpi})")
                toast("Игра не запустилась: $error")
            }
        }
    }

    // ------------------------------------------------------------------ Input to the game

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val gamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD) && event.keyCode != KeyEvent.KEYCODE_BACK
        if (!gamepad && event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val id = displayId
        val shell = service
        if (id >= 0 && shell != null) {
            runCatching { shell.injectKey(event, id) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val id = displayId
        val shell = service
        if (id >= 0 && shell != null && (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD))) {
            runCatching { shell.injectMotion(event, id) }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Types "/connect localhost:19144" into Minecraft's chat on the virtual screen, then Enter. */
    private fun typeConnect() {
        val id = displayId
        val shell = service ?: return
        if (id < 0 || MinecraftBridge.connected) return
        thread {
            val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            fun press(code: Int) {
                val now = android.os.SystemClock.uptimeMillis()
                runCatching { shell.injectKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), id) }
                runCatching { shell.injectKey(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), id) }
            }
            press(KeyEvent.KEYCODE_SLASH)
            Thread.sleep(600)
            map.getEvents(MinecraftBridge.CONNECT.removePrefix("/").toCharArray())?.forEach { event ->
                runCatching { shell.injectKey(event, id) }
                Thread.sleep(8)
            }
            Thread.sleep(150)
            press(KeyEvent.KEYCODE_ENTER)
            toast("Подключаю мод PhoneXR VR…")
        }
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    companion object {
        private const val TAG = "PhoneXR-Cinema"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_SCENE = "scene"
        const val SCENE_ROOM = "room"
        const val SCENE_SKY = "sky"
        const val SCENE_ROBLOX = "roblox"
        const val SCENE_BRAWL = "brawl"
        const val ACTION_JOYCON_PASSTHROUGH = "com.samrat.cardboardhands.JOYCON_PASSTHROUGH"

        /** While the cinema is in front, Joy-Con buttons go to the game as a gamepad, not to VR controllers. */
        fun setJoyConPassthrough(context: Context, enabled: Boolean) {
            context.sendBroadcast(
                Intent(ACTION_JOYCON_PASSTHROUGH).setPackage(context.packageName).putExtra("enabled", enabled)
            )
        }
    }
}
