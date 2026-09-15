package com.samrat.cardboardhands

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice
import java.util.Locale
import kotlin.math.sqrt

/** Reads motion sensors exposed by paired Joy-Con through Android's controller API. */
class JoyConTracker(context: Context) : AutoCloseable, InputManager.InputDeviceListener {
    data class Pose(
        val connected: Boolean = false,
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val w: Float = 1f
    )

    private val inputManager = context.getSystemService(InputManager::class.java)
    private val thread = HandlerThread("PhoneXR Joy-Con").apply { start() }
    private val handler = Handler(thread.looper)
    private val slots = arrayOf(Slot(), Slot())

    init {
        inputManager.registerInputDeviceListener(this, handler)
        handler.post { refresh() }
    }

    fun pose(left: Boolean): Pose = slots[if (left) 0 else 1].snapshot()

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        if (Build.VERSION.SDK_INT < 31) return
        slots.forEach { it.detach() }
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val name = device.name.lowercase(Locale.ROOT)
            val isNintendoJoyCon = device.vendorId == 0x057e && (device.productId == 0x2006 || device.productId == 0x2007)
            if (!name.contains("joy-con") && !name.contains("joycon") && !isNintendoJoyCon) continue
            val side = when {
                device.productId == 0x2006 -> 0
                device.productId == 0x2007 -> 1
                name.contains("(l)") || name.contains("left") -> 0
                name.contains("(r)") || name.contains("right") -> 1
                !slots[0].attached -> 0
                else -> 1
            }
            slots[side].attach(device)
        }
    }

    private inner class Slot : SensorEventListener {
        @Volatile private var pose = Pose()
        var attached = false
            private set
        private var manager: SensorManager? = null
        private var rotation: Sensor? = null
        private var gyro: Sensor? = null
        private var lastGyroNs = 0L
        private var reference: FloatArray? = null

        fun attach(device: InputDevice) {
            if (Build.VERSION.SDK_INT < 31) return
            manager = device.sensorManager
            rotation = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val sensor = rotation ?: gyro ?: return
            attached = manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler) == true
            pose = Pose(connected = attached)
        }

        fun detach() {
            manager?.unregisterListener(this)
            manager = null
            rotation = null
            gyro = null
            attached = false
            lastGyroNs = 0L
            reference = null
            pose = Pose()
        }

        fun snapshot() = pose

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val raw = FloatArray(4)
                SensorManager.getQuaternionFromVector(raw, event.values)
                val current = floatArrayOf(raw[1], raw[2], raw[3], raw[0])
                if (reference == null) reference = floatArrayOf(-current[0], -current[1], -current[2], current[3])
                val relative = multiply(reference!!, current)
                pose = Pose(true, relative[0], relative[1], relative[2], relative[3])
                return
            }
            if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val previous = lastGyroNs
            lastGyroNs = event.timestamp
            if (previous == 0L) return
            val dt = ((event.timestamp - previous) / 1_000_000_000.0f).coerceIn(0f, .05f)
            val current = pose
            val delta = floatArrayOf(event.values[0] * dt * .5f, event.values[1] * dt * .5f, event.values[2] * dt * .5f, 1f)
            val next = multiply(floatArrayOf(current.x, current.y, current.z, current.w), delta)
            pose = Pose(true, next[0], next[1], next[2], next[3])
        }

        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
            val y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
            val z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
            val w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
            val length = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(.0001f)
            return floatArrayOf(x / length, y / length, z / length, w / length)
        }
    }

    override fun close() {
        inputManager.unregisterInputDeviceListener(this)
        slots.forEach { it.detach() }
        thread.quitSafely()
    }
}
