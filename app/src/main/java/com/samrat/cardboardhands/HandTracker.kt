package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandTracker(
    context: Context,
    useGpu: Boolean = false,
    private val onResult: (HandLandmarkerResult) -> Unit
) : AutoCloseable {
    private val landmarker: HandLandmarker
    private var lastTimestamp = -1L

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.35f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int = 0) {
        val safeTimestamp = maxOf(timestampMs, lastTimestamp + 1)
        lastTimestamp = safeTimestamp
        val image = BitmapImageBuilder(bitmap).build()
        val processing = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        onResult(landmarker.detectForVideo(image, processing, safeTimestamp))
        image.close()
    }

    override fun close() = landmarker.close()
}
