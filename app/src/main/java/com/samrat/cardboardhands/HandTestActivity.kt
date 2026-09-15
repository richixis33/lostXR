package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HandTestActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var cameraView: CardboardCameraView
    private lateinit var label: TextView
    private var tracker: HandTracker? = null
    private var lastFrame = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraView = CardboardCameraView(this)
        root.addView(cameraView, FrameLayout.LayoutParams(-1, -1))
        label = TextView(this).apply {
            text = "Покажите обе руки камере"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 14, 24, 14)
        }
        root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        tracker = HandTracker(this) { result ->
            cameraView.updateHands(result)
            runOnUiThread { label.text = "Найдено рук: ${result.landmarks().size}" }
        }
        bindCamera()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val oriented = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                    cameraView.updateFrame(oriented)
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    if (timestamp - lastFrame >= 70 && busy.compareAndSet(false, true)) {
                        lastFrame = timestamp
                        val frame = Bitmap.createScaledBitmap(oriented, 320, 240, true)
                        trackingExecutor.execute {
                            try { tracker?.detect(frame, timestamp) }
                            finally { frame.recycle(); busy.set(false) }
                        }
                    }
                } finally { image.close() }
            }
            future.get().bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        tracker?.close()
        cameraExecutor.shutdownNow()
        trackingExecutor.shutdownNow()
        super.onDestroy()
    }
}
