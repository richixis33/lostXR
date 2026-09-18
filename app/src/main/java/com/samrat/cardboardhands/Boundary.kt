package com.samrat.cardboardhands

import android.content.Context
import org.json.JSONArray
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The play area (like Quest's Guardian): a floor outline traced by walking around it. Points are
 * head positions on the floor plane (x, z) in the VR home's world, which starts where the user
 * started 6DoF — so start from the same spot, or trace it again.
 */
class Boundary(private val context: Context) {
    /** Closed outline, x and z interleaved. Empty when there is no boundary. */
    @Volatile var outline: FloatArray = load()
        private set
    /** While tracing: the path walked so far. */
    @Volatile var tracing: FloatArray? = null
        private set

    val defined get() = outline.size >= 6

    fun startTracing() {
        tracing = FloatArray(0)
    }

    fun cancelTracing() {
        tracing = null
    }

    /** Adds the head's floor position while tracing; closes the loop when back at the start. */
    fun addPoint(x: Float, z: Float): Boolean {
        val path = tracing ?: return false
        if (path.size >= 2 && hypot(path[path.size - 2] - x, path[path.size - 1] - z) < STEP) return false
        val next = path + floatArrayOf(x, z)
        tracing = next
        val length = pathLength(next)
        val closed = next.size >= 12 && length > 3f && hypot(next[0] - x, next[1] - z) < CLOSE_DISTANCE
        if (closed) finish()
        return closed
    }

    /** Ends tracing: the walked path becomes the boundary (at least a triangle). */
    fun finish(): Boolean {
        val path = tracing ?: return false
        tracing = null
        if (path.size < 6) return false
        outline = path
        save(path)
        return true
    }

    fun clear() {
        outline = FloatArray(0)
        save(outline)
    }

    /** Inside the outline (even-odd rule). */
    fun contains(x: Float, z: Float): Boolean {
        val p = outline
        if (p.size < 6) return true
        var inside = false
        var j = p.size - 2
        var i = 0
        while (i < p.size) {
            val xi = p[i]; val zi = p[i + 1]; val xj = p[j]; val zj = p[j + 1]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    /** Distance from the point to the nearest edge of the outline, metres. */
    fun distance(x: Float, z: Float): Float {
        val p = outline
        if (p.size < 6) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        var j = p.size - 2
        var i = 0
        while (i < p.size) {
            best = min(best, segmentDistance(x, z, p[j], p[j + 1], p[i], p[i + 1]))
            j = i
            i += 2
        }
        return best
    }

    private fun segmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax; val dz = bz - az
        val length = dx * dx + dz * dz
        val t = if (length < 1e-6f) 0f else max(0f, min(1f, ((px - ax) * dx + (pz - az) * dz) / length))
        return hypot(px - (ax + t * dx), pz - (az + t * dz))
    }

    private fun pathLength(path: FloatArray): Float {
        var total = 0f
        var i = 2
        while (i < path.size) {
            total += hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1])
            i += 2
        }
        return total
    }

    private fun load(): FloatArray = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }.getOrDefault(FloatArray(0))

    private fun save(points: FloatArray) {
        val array = JSONArray().apply { points.forEach { put(it.toDouble()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "boundary"
        private const val KEY = "outline"
        /** A new point every 15 cm of walking. */
        private const val STEP = .15f
        /** Back within 40 cm of the start after at least 3 m: the loop closes by itself. */
        private const val CLOSE_DISTANCE = .4f
        /** Walls start to show this close to the edge. */
        const val WARN_DISTANCE = .6f
        /** The walls reach from below the floor to above the head (eye level is 0). */
        const val WALL_BOTTOM = -1.8f
        const val WALL_TOP = .6f
    }
}
