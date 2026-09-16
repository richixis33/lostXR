package com.samrat.cardboardhands

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Camera tracking that remains active while an OpenXR game is in front. */
class HandTrackingService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val transportExecutor = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)
    private var tracker: HandTracker? = null
    private var lastFrameMs = 0L
    private val socket = DatagramSocket()
    private val stableLeft = StableHand(.34f)
    private val stableRight = StableHand(.66f)
    private var joyCons: JoyConTracker? = null
    @Volatile private var settings = Settings.State()
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applySettings()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PhoneXR Hand Tracking")
            .setContentText("Жесты рук передаются в OpenXR")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        )
        applySettings()
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            IntentFilter(Settings.ACTION_APPLY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        joyCons = JoyConTracker(this)
        transportExecutor.scheduleAtFixedRate({ sendLatest() }, 0L, 16L, TimeUnit.MILLISECONDS)
        // GPU delegates must be created and invoked on the same thread.
        trackingExecutor.execute {
            tracker = try {
                HandTracker(this, useGpu = true, onResult = ::onHands)
            } catch (_: Throwable) {
                HandTracker(this, useGpu = false, onResult = ::onHands)
            }
            ContextCompat.getMainExecutor(this).execute { bindCamera() }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    if (timestamp - lastFrameMs >= 45 && busy.compareAndSet(false, true)) {
                        lastFrameMs = timestamp
                        val frame = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        trackingExecutor.execute {
                            try { tracker?.detect(frame, timestamp, rotation) }
                            finally { frame.recycle(); busy.set(false) }
                        }
                    }
                } catch (_: Throwable) {
                    busy.set(false)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHands(result: HandLandmarkerResult) {
        var left = HandState()
        var right = HandState()
        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val reported = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().orEmpty()
            // MediaPipe handedness assumes a mirrored selfie image; the back camera is not mirrored.
            val physicalLeft = reported.equals("Right", true)
            val state = classify(points)
            if (physicalLeft) left = state else right = state
        }
        stableLeft.update(left)
        stableRight.update(right)
    }

    private fun classify(p: List<NormalizedLandmark>): HandState {
        fun d(a: Int, b: Int): Float {
            val dx = p[a].x() - p[b].x()
            val dy = p[a].y() - p[b].y()
            return sqrt(dx * dx + dy * dy)
        }
        fun extended(mcp: Int, pip: Int, tip: Int): Boolean {
            val ax = p[mcp].x() - p[pip].x()
            val ay = p[mcp].y() - p[pip].y()
            val bx = p[tip].x() - p[pip].x()
            val by = p[tip].y() - p[pip].y()
            val length = sqrt((ax * ax + ay * ay) * (bx * bx + by * by)).coerceAtLeast(.0001f)
            val jointCosine = (ax * bx + ay * by) / length
            return jointCosine < -.48f && d(0, tip) > d(0, pip) * 1.01f
        }
        val index = extended(5, 6, 8)
        val middle = extended(9, 10, 12)
        val ring = extended(13, 14, 16)
        val pinky = extended(17, 18, 20)
        val palmWidth = d(5, 17).coerceAtLeast(0.035f)
        val thumb = extended(2, 3, 4) && d(4, 5) > palmWidth * .62f
        val folded = listOf(index, middle, ring, pinky).count { !it }
        val indexOnly = index && !middle && !ring && !pinky
        val thumbOnly = thumb && !index && folded >= 3
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        val tightlyFolded = tips.indices.count { d(0, tips[it]) < d(0, pips[it]) * 1.04f }
        // A pointing finger or a visible thumb is a button gesture, never a fist.
        val fist = tightlyFolded >= 3 && !indexOnly && !thumbOnly
        val palmX = (p[0].x() + p[5].x() + p[9].x() + p[13].x() + p[17].x()) / 5f
        val palmY = (p[0].y() + p[5].y() + p[9].y() + p[13].y() + p[17].y()) / 5f
        val depth = ((0.17f - palmWidth) / 0.13f).coerceIn(0f, 1f)
        return HandState(true, fist, indexOnly, thumbOnly, palmX, palmY, depth)
    }

    private fun applySettings() {
        settings = Settings.load(this)
        JoyConButtons.apply(settings)
    }

    private fun sendLatest() {
        val leftJoy = joyCons?.pose(true) ?: JoyConTracker.Pose()
        val rightJoy = joyCons?.pose(false) ?: JoyConTracker.Pose()
        var left = stableLeft.snapshot(leftJoy.connected)
        var right = stableRight.snapshot(rightJoy.connected)
        val current = settings
        if (current.handMode == Settings.HandMode.HANDS) {
            // Plain hand tracking: fingers move the hand, they never press anything.
            left = left.copy(fist = false, index = false, thumb = false)
            right = right.copy(fist = false, index = false, thumb = false)
        }
        val flags = (if (current.sixDof) 1 else 0) or (if (current.handMode == Settings.HandMode.HANDS) 2 else 0)
        val message = String.format(
            Locale.US,
            "PH4 %d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d " +
                "%d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %d",
            left.present.i, left.fist.i, left.index.i, left.thumb.i, left.x, left.y, left.z,
            leftJoy.x, leftJoy.y, leftJoy.z, leftJoy.w, JoyConButtons.mask(true),
            right.present.i, right.fist.i, right.index.i, right.thumb.i, right.x, right.y, right.z,
            rightJoy.x, rightJoy.y, rightJoy.z, rightJoy.w, JoyConButtons.mask(false), flags
        )
        val bytes = message.toByteArray(Charsets.US_ASCII)
        // Monado listens on IPv4. Android may resolve getLoopbackAddress() to ::1.
        val loopback = InetAddress.getByName("127.0.0.1")
        // RUNTIME_PORT feeds Monado, SDK_PORT feeds a game that wants the raw hand and Joy-Con data.
        for (port in intArrayOf(RUNTIME_PORT, SDK_PORT)) {
            try { socket.send(DatagramPacket(bytes, bytes.size, loopback, port)) }
            catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(settingsReceiver)
        tracker?.close()
        joyCons?.close()
        cameraExecutor.shutdownNow()
        trackingExecutor.shutdownNow()
        transportExecutor.shutdownNow()
        socket.close()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "PhoneXR Hands", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private data class HandState(
        val present: Boolean = false,
        val fist: Boolean = false,
        val index: Boolean = false,
        val thumb: Boolean = false,
        val x: Float = .5f,
        val y: Float = .5f,
        val z: Float = .5f,
    )

    /** Removes landmark jitter and keeps a detected click alive long enough for games to read it. */
    private class StableHand(private val restingX: Float = .5f) {
        private var x = restingX
        private var y = .5f
        private var z = .5f
        private var lastSeenMs = 0L
        private var fistUntilMs = 0L
        private var indexUntilMs = 0L
        private var thumbUntilMs = 0L

        @Synchronized
        fun update(raw: HandState) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (raw.present) {
                val first = lastSeenMs == 0L || now - lastSeenMs > 300L
                val positionWeight = if (first) 1f else .72f
                val depthWeight = if (first) 1f else .46f
                x += (raw.x - x) * positionWeight
                y += (raw.y - y) * positionWeight
                z += (raw.z - z) * depthWeight
                lastSeenMs = now
                if (raw.fist) {
                    fistUntilMs = now + 110L
                    indexUntilMs = 0L
                    thumbUntilMs = 0L
                } else {
                    // An observed open/pointing hand releases a stale false fist immediately.
                    fistUntilMs = 0L
                    if (raw.index) indexUntilMs = now + 150L
                    if (raw.thumb) thumbUntilMs = now + 150L
                }
            }
        }

        @Synchronized
        fun snapshot(controllerConnected: Boolean): HandState {
            val now = android.os.SystemClock.elapsedRealtime()
            val handPresent = now - lastSeenMs < 420L
            return HandState(
                present = handPresent || controllerConnected,
                fist = handPresent && now < fistUntilMs,
                index = handPresent && now < indexUntilMs,
                thumb = handPresent && now < thumbUntilMs,
                x = x,
                y = y,
                z = z
            )
        }
    }

    private val Boolean.i get() = if (this) 1 else 0

    companion object {
        private const val CHANNEL = "phonexr_hands"
        private const val NOTIFICATION_ID = 42
        private const val RUNTIME_PORT = 42424
        private const val SDK_PORT = 42425
    }
}
