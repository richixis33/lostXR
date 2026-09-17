package com.samrat.cardboardhands

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Display
import android.view.Surface
import kotlin.math.atan2

/**
 * Head rotation from the phone's rotation vector, for a phone lying landscape in a VR headset.
 * [head] is head-to-world, column-major, with world y up and "straight ahead" along -z.
 */
class HeadTracker(private val sensors: SensorManager, private val display: () -> Display?) : SensorEventListener {
    val head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val rotation = FloatArray(9)
    @Volatile private var yawOffset = Float.NaN

    fun start() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() = sensors.unregisterListener(this)

    /** Makes the current head direction "straight ahead". */
    fun recenter() {
        yawOffset = Float.NaN
    }

    /** Copies the head rotation into [out] under the tracker's lock. */
    fun copyHead(out: FloatArray) = synchronized(head) { System.arraycopy(head, 0, out, 0, 16) }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val r = rotation
        // Device to world (east, north, up) -> head to GL world (x right, y up, z back).
        // Landscape in the headset: screen right is device -Y, or +Y when turned the other way.
        val turned = display()?.rotation == Surface.ROTATION_270
        val d = if (turned) arrayOf(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        else arrayOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        val rd = Array(3) { row -> FloatArray(3) { col -> r[row * 3] * d[0][col] + r[row * 3 + 1] * d[1][col] + r[row * 3 + 2] * d[2][col] } }
        // East, north, up -> x, -z, y.
        val m = arrayOf(rd[0], rd[2], FloatArray(3) { -rd[1][it] })
        val yaw = atan2(m[0][2], m[2][2])
        if (yawOffset.isNaN()) yawOffset = yaw
        val gl = FloatArray(16)
        for (row in 0..2) for (col in 0..2) gl[col * 4 + row] = m[row][col]
        gl[15] = 1f
        val recenter = FloatArray(16)
        Matrix.setRotateM(recenter, 0, Math.toDegrees(-yawOffset.toDouble()).toFloat(), 0f, 1f, 0f)
        synchronized(head) { Matrix.multiplyMM(head, 0, recenter, 0, gl, 0) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
