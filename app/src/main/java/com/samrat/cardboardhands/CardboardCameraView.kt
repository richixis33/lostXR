package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class CardboardCameraView(context: Context) : View(context) {
    @Volatile private var frame: Bitmap? = null
    private var pendingFrame: Bitmap? = null
    private var framePostScheduled = false
    @Volatile private var hands: List<List<NormalizedLandmark>> = emptyList()

    private val videoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE8F5F5F7.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val palmFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE8F5F5F7.toInt()
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = resources.displayMetrics.density * 10f
    }
    private val lensBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202027.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    fun updateFrame(newFrame: Bitmap) {
        synchronized(this) {
            pendingFrame?.takeIf { !it.isRecycled }?.recycle()
            pendingFrame = newFrame
            if (framePostScheduled) return
            framePostScheduled = true
        }
        post {
            val next = synchronized(this) {
                framePostScheduled = false
                pendingFrame.also { pendingFrame = null }
            } ?: return@post
            val old = frame
            frame = next
            invalidate()
            old?.takeIf { it !== next && !it.isRecycled }?.recycle()
        }
    }

    fun updateHands(result: HandLandmarkerResult) {
        hands = result.landmarks().map { it.toList() }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = frame ?: return
        if (bitmap.isRecycled) return

        val eyeWidth = width / 2f
        drawEye(canvas, bitmap, 0f, eyeWidth, -1)
        drawEye(canvas, bitmap, eyeWidth, eyeWidth, 1)
        canvas.drawLine(eyeWidth, 0f, eyeWidth, height.toFloat(), dividerPaint)
    }

    private fun drawEye(canvas: Canvas, bitmap: Bitmap, left: Float, eyeWidth: Float, eye: Int) {
        val scale = maxOf(eyeWidth / bitmap.width, height.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val eyeShift = eye * eyeWidth * 0.018f
        val offsetX = left + (eyeWidth - drawnWidth) / 2f + eyeShift
        val offsetY = (height - drawnHeight) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(offsetX, offsetY)
        }

        canvas.save()
        val lensMarginX = eyeWidth * 0.025f
        val lensMarginY = height * 0.025f
        val lens = android.graphics.RectF(
            left + lensMarginX,
            lensMarginY,
            left + eyeWidth - lensMarginX,
            height - lensMarginY
        )
        val lensPath = Path().apply { addRoundRect(lens, eyeWidth * 0.13f, eyeWidth * 0.13f, Path.Direction.CW) }
        canvas.clipPath(lensPath)
        canvas.drawBitmap(bitmap, matrix, videoPaint)
        hands.forEach { drawStylizedHand(canvas, it, offsetX, offsetY, drawnWidth, drawnHeight) }
        canvas.restore()
        canvas.drawRoundRect(lens, eyeWidth * 0.13f, eyeWidth * 0.13f, lensBorderPaint)
    }

    private fun drawStylizedHand(
        canvas: Canvas,
        points: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float,
        drawnWidth: Float,
        drawnHeight: Float
    ) {
        if (points.size < 21) return
        fun x(index: Int) = offsetX + points[index].x() * drawnWidth
        fun y(index: Int) = offsetY + points[index].y() * drawnHeight

        val palmWidth = distance(x(5), y(5), x(17), y(17)).coerceAtLeast(28f)
        val fingerWidth = (palmWidth * 0.31f).coerceIn(20f, 62f)
        val silhouette = Path()

        val wristDx = x(0) - x(9)
        val wristDy = y(0) - y(9)
        val wristLength = kotlin.math.sqrt(wristDx * wristDx + wristDy * wristDy).coerceAtLeast(1f)
        val sideX = -wristDy / wristLength * palmWidth * 0.43f
        val sideY = wristDx / wristLength * palmWidth * 0.43f
        val extensionX = wristDx / wristLength * palmWidth * 0.65f
        val extensionY = wristDy / wristLength * palmWidth * 0.65f

        silhouette.moveTo(x(0) + sideX + extensionX, y(0) + sideY + extensionY)
        silhouette.lineTo(x(1), y(1))
        silhouette.lineTo(x(5), y(5))
        silhouette.lineTo(x(9), y(9))
        silhouette.lineTo(x(13), y(13))
        silhouette.lineTo(x(17), y(17))
        silhouette.lineTo(x(0) - sideX + extensionX, y(0) - sideY + extensionY)
        silhouette.close()

        val fingers = arrayOf(
            intArrayOf(0, 1, 2, 3, 4),
            intArrayOf(0, 5, 6, 7, 8),
            intArrayOf(0, 9, 10, 11, 12),
            intArrayOf(0, 13, 14, 15, 16),
            intArrayOf(0, 17, 18, 19, 20)
        )

        fingers.forEachIndexed { fingerIndex, finger ->
            val centerLine = Path().apply {
                moveTo(x(finger[0]), y(finger[0]))
                for (i in 1 until finger.size) lineTo(x(finger[i]), y(finger[i]))
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = fingerWidth * if (fingerIndex == 0) 1.15f else 1f
            }
            val filledFinger = Path()
            stroke.getFillPath(centerLine, filledFinger)
            silhouette.op(filledFinger, Path.Op.UNION)
        }
        whitePaint.strokeWidth = resources.displayMetrics.density * 4.5f
        canvas.drawPath(silhouette, palmFill)
        canvas.drawPath(silhouette, whitePaint)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun onDetachedFromWindow() {
        frame?.takeIf { !it.isRecycled }?.recycle()
        pendingFrame?.takeIf { !it.isRecycled }?.recycle()
        frame = null
        pendingFrame = null
        super.onDetachedFromWindow()
    }
}
