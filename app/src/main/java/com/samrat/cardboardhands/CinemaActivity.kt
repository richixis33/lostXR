package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

/**
 * PhoneXR Cinema: runs any app (Minecraft Bedrock first of all) on a virtual display and shows it
 * as a big screen in VR with head tracking. Gamepads and Joy-Con play the game.
 */
class CinemaActivity : Activity() {
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
        // The model loads in a moment; a missing or broken file falls back to the simple room.
        val room = runCatching { GlbRoom.load(this, "cinema/living_room.glb") }
            .onFailure { Log.w(TAG, "Living room model failed to load", it) }
            .getOrNull()
        renderer = CinemaRenderer(room, onSurface = { created ->
            surface = created
            startDisplay()
        })
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        renderer.head = tracker.head
        renderer.scene = if (intent.getStringExtra(EXTRA_SCENE) == SCENE_SKY) CinemaRenderer.Scene.SKY
        else CinemaRenderer.Scene.ROOM
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            // A tap on the phone recenters the view to where the head looks now.
            setOnClickListener { tracker.recenter() }
        }
        setContentView(surfaceView)

        // Hand tracking would hold the camera and the Joy-Con for VR controllers; the cinema needs neither.
        stopService(Intent(this, HandTrackingService::class.java))
        connection = VirtualScreen.bind(this) { bound ->
            service = bound
            if (bound == null) toast("Служба Shizuku отключилась") else startDisplay()
        }
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        tracker.start()
        setJoyConPassthrough(this, true)
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        tracker.stop()
        setJoyConPassthrough(this, false)
    }

    override fun onDestroy() {
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

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    companion object {
        private const val TAG = "PhoneXR-Cinema"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_SCENE = "scene"
        const val SCENE_ROOM = "room"
        const val SCENE_SKY = "sky"
        const val ACTION_JOYCON_PASSTHROUGH = "com.samrat.cardboardhands.JOYCON_PASSTHROUGH"

        /** While the cinema is in front, Joy-Con buttons go to the game as a gamepad, not to VR controllers. */
        fun setJoyConPassthrough(context: Context, enabled: Boolean) {
            context.sendBroadcast(
                Intent(ACTION_JOYCON_PASSTHROUGH).setPackage(context.packageName).putExtra("enabled", enabled)
            )
        }
    }
}
