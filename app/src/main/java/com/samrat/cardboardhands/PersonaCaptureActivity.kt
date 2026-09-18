package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Persona capture with the phone out of the headset: the front camera looks at the user, waits
 * for a steady, straight face in the middle, takes the picture and builds the Persona from it.
 */
class PersonaCaptureActivity : ComponentActivity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private val done = AtomicBoolean(false)
    private var goodFrames = 0
    private var lastTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            text = "Держите телефон перед лицом и смотрите в камеру"
        }
        val skip = Button(this).apply { text = "Пропустить"; setOnClickListener { finishWith(false) } }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 0, 48, 96)
            addView(status)
            addView(skip)
        }
        setContentView(FrameLayout(this).apply {
            addView(preview)
            addView(FaceGuide(this@PersonaCaptureActivity))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        executor.execute {
            landmarker = runCatching {
                FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build())
            }.getOrNull()
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 960))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (!done.get()) analyze(image.toBitmap().rotate(image.imageInfo.rotationDegrees), image.imageInfo.timestamp / 1_000_000L)
                } finally {
                    image.close()
                }
            }
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis) }
                .onFailure { status.text = "Фронтальная камера недоступна" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(frame: Bitmap, timestamp: Long) {
        // The user sees themselves like in a mirror; the Persona keeps the real (unmirrored) face.
        val mirrored = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { preScale(-1f, 1f) }, true)
        runOnUiThread { preview.setImageBitmap(mirrored) }
        val face = landmarker ?: return
        val stamp = maxOf(timestamp, lastTimestamp + 1).also { lastTimestamp = it }
        val points = face.detectForVideo(BitmapImageBuilder(frame).build(), stamp).faceLandmarks().firstOrNull()
        val message = when {
            points == null -> { goodFrames = 0; "Лицо не видно" }
            else -> {
                val nose = points[1]; val left = points[234]; val right = points[454]
                val width = right.x() - left.x()
                val centred = abs((left.x() + right.x()) / 2 - .5f) < .12f && abs(nose.y() - .45f) < .15f
                val straight = abs((nose.x() - left.x()) / width - .5f) < .1f
                val near = abs(width) > .28f
                when {
                    !near -> { goodFrames = 0; "Поднесите телефон ближе" }
                    !centred -> { goodFrames = 0; "Лицо в центр рамки" }
                    !straight -> { goodFrames = 0; "Смотрите прямо в камеру" }
                    else -> { goodFrames++; "Не двигайтесь…" }
                }
            }
        }
        runOnUiThread { status.text = message }
        if (goodFrames >= 12 && done.compareAndSet(false, true)) {
            runOnUiThread { status.text = "Создаю персону…" }
            val picture = frame.copy(Bitmap.Config.ARGB_8888, false)
            thread {
                val error = Persona.build(this, picture)
                runOnUiThread {
                    if (error == null) finishWith(true)
                    else { status.text = error; done.set(false); goodFrames = 0 }
                }
            }
        }
    }

    private fun finishWith(ok: Boolean) {
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        executor.execute { landmarker?.close() }
        executor.shutdown()
        super.onDestroy()
    }

    /** An oval showing where to put the face. */
    private class FaceGuide(context: android.content.Context) : android.view.View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.WHITE }
        override fun onDraw(canvas: Canvas) {
            val w = width * .62f; val h = w * 1.3f
            canvas.drawOval(width / 2f - w / 2, height * .42f - h / 2, width / 2f + w / 2, height * .42f + h / 2, paint)
        }
    }
}
