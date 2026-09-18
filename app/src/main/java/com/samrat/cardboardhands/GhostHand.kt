package com.samrat.cardboardhands

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A see-through hand like Quest's: rounded fingers and a filled palm built from the 21 MediaPipe
 * landmarks. The mesh is flat (one depth), so it can be drawn with a depth test to blend once.
 */
object GhostHand {
    /** MediaPipe hand skeleton. */
    val BONES = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
    )
    private val PALM = intArrayOf(0, 1, 2, 5, 9, 13, 17)
    private val TIPS = setOf(4, 8, 12, 16, 20)
    private const val SEGMENTS = 12

    /**
     * Triangles (x, y, z per vertex) for a hand whose landmark i sits at ([xs][i], [ys][i]) on the
     * plane [z]. Sizes follow the hand's own palm width, so a nearer hand is drawn bigger.
     */
    fun triangles(xs: FloatArray, ys: FloatArray, z: Float): FloatArray {
        val palm = hypot(xs[5] - xs[17], ys[5] - ys[17]).coerceAtLeast(1e-4f)
        val out = ArrayList<Float>(2400)
        fun vertex(x: Float, y: Float) { out += x; out += y; out += z }
        fun radius(i: Int) = palm * when {
            i in TIPS -> .10f
            i <= 4 -> .14f
            else -> .12f
        }
        // Palm: a fan around its centre.
        val cx = PALM.map { xs[it] }.average().toFloat()
        val cy = PALM.map { ys[it] }.average().toFloat()
        val ring = PALM.toList() + PALM[0]
        for (k in 0 until ring.size - 1) {
            vertex(cx, cy); vertex(xs[ring[k]], ys[ring[k]]); vertex(xs[ring[k + 1]], ys[ring[k + 1]])
        }
        // Bones: quads that taper from joint to joint.
        for ((a, b) in BONES) {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val length = hypot(dx, dy).coerceAtLeast(1e-6f)
            val nx = -dy / length
            val ny = dx / length
            val ra = radius(a)
            val rb = radius(b)
            val a1x = xs[a] + nx * ra; val a1y = ys[a] + ny * ra
            val a2x = xs[a] - nx * ra; val a2y = ys[a] - ny * ra
            val b1x = xs[b] + nx * rb; val b1y = ys[b] + ny * rb
            val b2x = xs[b] - nx * rb; val b2y = ys[b] - ny * rb
            vertex(a1x, a1y); vertex(a2x, a2y); vertex(b1x, b1y)
            vertex(a2x, a2y); vertex(b2x, b2y); vertex(b1x, b1y)
        }
        // Round joints and fingertips.
        for (i in 0 until 21) {
            val r = radius(i)
            for (k in 0 until SEGMENTS) {
                val t0 = (k * 2 * Math.PI / SEGMENTS).toFloat()
                val t1 = ((k + 1) * 2 * Math.PI / SEGMENTS).toFloat()
                vertex(xs[i], ys[i])
                vertex(xs[i] + cos(t0) * r, ys[i] + sin(t0) * r)
                vertex(xs[i] + cos(t1) * r, ys[i] + sin(t1) * r)
            }
        }
        return out.toFloatArray()
    }
}
