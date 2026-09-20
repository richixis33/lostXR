package com.samrat.cardboardhands
import com.google.mediapipe.tasks.core.Delegate

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
    private val stableLeft = StableHand(.34f) { TrackingSettings.getMinCutoff(this) to TrackingSettings.getBeta(this) }
    private val stableRight = StableHand(.66f) { TrackingSettings.getMinCutoff(this) to TrackingSettings.getBeta(this) }
    private val pinchLatches = arrayOf(HandGestures.PinchLatch(), HandGestures.PinchLatch())
    private var joyCons: JoyConTracker? = null
    private val vision = JoyConVision()
    private val markers by lazy { JoyConMarkers() }
    @Volatile private var markerPoses = arrayOf(JoyConMarkers.Pose(), JoyConMarkers.Pose())
    private val markerSeenAtMs = LongArray(2)
    /** Latest Joy-Con seen by the camera and when, per side. */
    @Volatile private var seen = arrayOf(JoyConVision.Detection(), JoyConVision.Detection())
    private val seenAtMs = LongArray(2)
    @Volatile private var settings = Settings.State()
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applySettings()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("LostXR Hand Tracking")
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
            // Markers are small in the image; they need a sharper frame than hands.
            // A sharper frame gives steadier landmarks; markers need it anyway.
            val size = android.util.Size(640, 480)
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    // Colour search is cheap, so the camera Joy-Con mode uses every frame it can.
                    val interval = if (settings.cameraJoyCons || settings.markerJoyCons) 25 else 30
                    if (timestamp - lastFrameMs >= interval && busy.compareAndSet(false, true)) {
                        lastFrameMs = timestamp
                        val frame = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        trackingExecutor.execute {
                            try {
                                if (settings.markerJoyCons) findMarkers(frame.rotate(rotation))
                                else if (settings.cameraJoyCons) findJoyCons(frame.rotate(rotation))
                                else tracker?.detect(frame, timestamp, rotation)
                            }
                            finally { if (!frame.isRecycled) frame.recycle(); busy.set(false) }
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

    private fun findMarkers(frame: Bitmap) {
        val (left, right) = markers.process(frame)
        if (!frame.isRecycled) frame.recycle()
        val now = android.os.SystemClock.elapsedRealtime()
        if (left.found) markerSeenAtMs[0] = now
        if (right.found) markerSeenAtMs[1] = now
        markerPoses = arrayOf(if (left.found) left else markerPoses[0], if (right.found) right else markerPoses[1])
    }

    private fun markerHand(slot: Int, connected: Boolean): HandState {
        val pose = markerPoses[slot]
        val visible = pose.found && android.os.SystemClock.elapsedRealtime() - markerSeenAtMs[slot] < LOST_MS
        // Monado places the hand at 0.35 + z * 0.45 m in front of the eyes.
        val depth = ((pose.distance - 0.35f) / 0.45f).coerceIn(0f, 1f)
        val x = if (pose.found) pose.x else if (slot == 0) .34f else .66f
        return HandState(visible || connected, x = x, y = pose.y, z = depth)
    }

    private fun findJoyCons(frame: Bitmap) {
        val current = settings
        val (left, right) = vision.process(frame, current.leftColor, current.rightColor)
        if (!frame.isRecycled) frame.recycle()
        val now = android.os.SystemClock.elapsedRealtime()
        listOf(left, right).forEachIndexed { slot, detection ->
            if (detection.found) {
                seenAtMs[slot] = now
            } else if (now - seenAtMs[slot] > LOST_MS) {
                vision.reset(slot)
            }
        }
        seen = arrayOf(
            if (left.found) left else seen[0],
            if (right.found) right else seen[1]
        )
    }

    /** Camera Joy-Con state in the hand message: position from the blob, no finger gestures. */
    private fun cameraHand(slot: Int, connected: Boolean): HandState {
        val detection = seen[slot]
        val visible = detection.found && android.os.SystemClock.elapsedRealtime() - seenAtMs[slot] < LOST_MS
        // Joy-Con is about 36 mm thick: 0.08 of the image width is close to the camera, 0.02 is an arm away.
        val depth = ((0.08f - detection.thickness) / 0.06f).coerceIn(0f, 1f)
        // A connected Joy-Con out of view stays where it was last seen instead of vanishing.
        // Never seen yet: rest to the side the Joy-Con belongs to.
        val x = if (detection.found) detection.x else if (slot == 0) .34f else .66f
        return HandState(visible || connected, x = x, y = detection.y, z = depth)
    }

    private fun onHands(result: HandLandmarkerResult) {
        var left = HandState()
        var right = HandState()
        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val reported = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().orEmpty()
            // MediaPipe handedness assumes a mirrored selfie image; the back camera is not mirrored.
            val physicalLeft = reported.equals("Right", true)
            val state = classify(points).let { hand ->
                val gesture = HandGestures.shape(points, physicalLeft)
                val pinch = pinchLatches[if (physicalLeft) 0 else 1].update(gesture)
                hand.copy(pinch = pinch, palmToFace = gesture.palmToFace)
            }
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
        val current = settings
        var leftJoy = joyCons?.pose(true) ?: JoyConTracker.Pose()
        var rightJoy = joyCons?.pose(false) ?: JoyConTracker.Pose()
        var left = stableLeft.snapshot(leftJoy.connected)
        var right = stableRight.snapshot(rightJoy.connected)
        val renderMode = TrackingSettings.getHandRenderMode(this)
        if (renderMode == TrackingSettings.HANDS_DISABLED) {
            left = left.copy(present = false)
            right = right.copy(present = false)
        } else if (renderMode == TrackingSettings.HANDS_RIGHT_ONLY) {
            left = left.copy(present = false)
        } else if (renderMode == TrackingSettings.HANDS_LEFT_ONLY) {
            right = right.copy(present = false)
        }
        if (current.markerJoyCons) {
            left = markerHand(0, leftJoy.connected)
            right = markerHand(1, rightJoy.connected)
            markerPoses[0].let { leftJoy = JoyConTracker.Pose(leftJoy.connected, it.qx, it.qy, it.qz, it.qw) }
            markerPoses[1].let { rightJoy = JoyConTracker.Pose(rightJoy.connected, it.qx, it.qy, it.qz, it.qw) }
        } else if (current.cameraJoyCons) {
            left = cameraHand(0, leftJoy.connected)
            right = cameraHand(1, rightJoy.connected)
            // A working Joy-Con gyroscope stays the better source of rotation; otherwise the camera gives it.
            leftJoy = cameraRotation(0, leftJoy, gyroWorks(true))
            rightJoy = cameraRotation(1, rightJoy, gyroWorks(false))
        } else if (current.handMode == Settings.HandMode.HANDS) {
            // Plain hand tracking: fingers move the hand, they never press anything.
            left = left.copy(fist = false, index = false, thumb = false)
            right = right.copy(fist = false, index = false, thumb = false)
        }
        // Quest-style gestures for games: a pinch clicks (the runtime's trigger comes from the fist field),
        // a real fist grabs (squeeze). With a Joy-Con in hand its buttons do this instead.
        var leftMask = JoyConButtons.mask(true)
        var rightMask = JoyConButtons.mask(false)
        if (current.handMode == Settings.HandMode.CONTROLLERS && !current.markerJoyCons && !current.cameraJoyCons) {
            if (!leftJoy.connected) {
                if (left.fist) leftMask = leftMask or JoyConButtons.SQUEEZE
                left = left.copy(fist = left.pinch)
            }
            if (!rightJoy.connected) {
                if (right.fist) rightMask = rightMask or JoyConButtons.SQUEEZE
                right = right.copy(fist = right.pinch)
            }
        }
        val flags = (if (current.sixDof) 1 else 0) or (if (current.handMode == Settings.HandMode.HANDS) 2 else 0)
        val leftStick = JoyConButtons.stick(left = true)
        val rightStick = JoyConButtons.stick(left = false)
        val message = String.format(
            Locale.US,
            "PH5 %d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f " +
                "%d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f %d " +
                // Appended after the runtime's fields (it ignores them): pinch and palm-to-face per hand.
                "%d %d %d %d",
            left.present.i, left.fist.i, left.index.i, left.thumb.i, left.x, left.y, left.z,
            leftJoy.x, leftJoy.y, leftJoy.z, leftJoy.w, leftMask,
            leftStick[0], leftStick[1],
            right.present.i, right.fist.i, right.index.i, right.thumb.i, right.x, right.y, right.z,
            rightJoy.x, rightJoy.y, rightJoy.z, rightJoy.w, rightMask,
            rightStick[0], rightStick[1], flags,
            left.pinch.i, left.palmToFace.i, right.pinch.i, right.palmToFace.i
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

    private fun gyroWorks(left: Boolean) = (joyCons?.motion(left)?.rateHz ?: 0f) >= 1f

    private fun cameraRotation(slot: Int, gyro: JoyConTracker.Pose, useGyro: Boolean): JoyConTracker.Pose {
        if (useGyro) return gyro
        val q = seen[slot].quaternion()
        return JoyConTracker.Pose(gyro.connected, q[0], q[1], q[2], q[3])
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
                NotificationChannel(CHANNEL, "LostXR Hands", NotificationManager.IMPORTANCE_LOW)
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
        val pinch: Boolean = false,
        val palmToFace: Boolean = false,
    )

    /** Removes landmark jitter and keeps a detected click alive long enough for games to read it. */
    private class StableHand(
        private val defaultX: Float,
        private val getParams: () -> Pair<Float, Float> = { 1.0f to 0.007f }
    ) {
        private val euroFilter = OneEuroFilter3D()
        private var last = HandState(present = false, x = defaultX, y = .5f, z = .5f)
        private var unseen = 0

        fun update(detected: HandState) {
            val (minCutoff, beta) = getParams()
            euroFilter.updateParams(minCutoff.toDouble(), beta.toDouble())

            if (detected.present) {
                unseen = 0
                val pos = euroFilter.filter(detected.x, detected.y, detected.z)
                last = detected.copy(x = pos.first, y = pos.second, z = pos.third)
            } else {
                unseen++
                if (unseen > 4) {
                    euroFilter.reset()
                    last = last.copy(present = false)
                }
            }
        }

        fun snapshot(connected: Boolean): HandState = last.copy(present = last.present || connected)
    }

    private val Boolean.i get() = if (this) 1 else 0

    companion object {
        private const val CHANNEL = "phonexr_hands"
        private const val NOTIFICATION_ID = 42
        private const val RUNTIME_PORT = 42424
        private const val SDK_PORT = 42425
        /** A Joy-Con not seen for this long no longer counts as tracked. */
        private const val LOST_MS = 400L
    }
}
