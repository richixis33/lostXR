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
     * Fingers keep the lengths scanned in setup: only their directions come from the camera, so
     * jittery landmarks no longer make fingers stretch and shrink. Palm bones are left as seen.
     */
    private fun steady(xs: FloatArray, ys: FloatArray, palm: Float, profile: FloatArray) {
        BONES.forEachIndexed { index, (a, b) ->
            if (b !in FINGER_JOINTS) return@forEachIndexed
            val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
            val length = hypot(dx, dy)
            if (length < 1e-6f) return@forEachIndexed
            // Foreshortening makes a finger look shorter, never longer: cap it at the real length.
            val target = kotlin.math.min(length, profile[index] * palm)
            xs[b] = xs[a] + dx / length * target
            ys[b] = ys[a] + dy / length * target
        }
    }

    private val FINGER_JOINTS = setOf(2, 3, 4, 6, 7, 8, 10, 11, 12, 14, 15, 16, 18, 19, 20)

    /**
     * Triangles (x, y, z per vertex) for a hand whose landmark i sits at ([xs][i], [ys][i]) on the
     * plane [z]. Sizes follow the hand's own palm width, so a nearer hand is drawn bigger.
     */
    fun triangles(xs: FloatArray, ys: FloatArray, z: Float, profile: FloatArray? = null, grow: Float = 1f): FloatArray {
        val palm = hypot(xs[5] - xs[17], ys[5] - ys[17]).coerceAtLeast(1e-4f)
        if (profile != null) steady(xs, ys, palm, profile)
        val out = ArrayList<Float>(2400)
        fun vertex(x: Float, y: Float) { out += x; out += y; out += z }
        fun radius(i: Int) = grow * palm * when {
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

/**
 * The real hand over everything: the hand-shaped area of the camera picture drawn on top of the
 * VR content, so the user's own hands stay visible in front of windows (like Quest and visionOS).
 * The shape comes from the landmarks, a little wider than the fingers so a moving hand stays covered.
 */
object RealHand {
    /** How much wider than the fingers the cut-out is. */
    const val GROW = 1.45f

    /**
     * Triangles for one hand as x, y, z, u, v per vertex. [us], [vs]: landmarks in the space the
     * caller maps from; [position] turns such a point into head space (z = -1), [uv] into the
     * camera texture's coordinates.
     */
    fun mesh(us: FloatArray, vs: FloatArray, position: (Float, Float) -> FloatArray, uv: (Float, Float) -> FloatArray): FloatArray {
        val flat = GhostHand.triangles(us, vs, 0f, null, GROW)
        val out = FloatArray(flat.size / 3 * 5)
        var o = 0
        var i = 0
        while (i < flat.size) {
            val p = position(flat[i], flat[i + 1])
            val t = uv(flat[i], flat[i + 1])
            out[o] = p[0]; out[o + 1] = p[1]; out[o + 2] = -1f; out[o + 3] = t[0]; out[o + 4] = t[1]
            o += 5
            i += 3
        }
        return out
    }
}
