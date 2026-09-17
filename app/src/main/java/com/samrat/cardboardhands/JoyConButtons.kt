package com.samrat.cardboardhands

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicIntegerArray

/** Joy-Con state shared between the accessibility filter and the hand-tracking service. */
object JoyConButtons {
    const val PRIMARY = 1
    const val SECONDARY = 1 shl 1
    const val TRIGGER = 1 shl 2
    const val SQUEEZE = 1 shl 3
    const val MENU = 1 shl 4
    const val STICK_CLICK = 1 shl 5
    const val SYSTEM = 1 shl 6

    /** Live state of one Joy-Con, used by the settings screen to show what is arriving. */
    data class Live(
        val connected: Boolean,
        /** Bits of the VR inputs currently pressed. */
        val buttons: Int,
        /** Bits over Settings.KNOWN_KEYS: which physical buttons are held. */
        val rawKeys: Int,
        val stickX: Float,
        val stickY: Float
    )

    private val masks = AtomicIntegerArray(2)
    private val rawMasks = AtomicIntegerArray(2)
    private val sticks = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
    @Volatile private var bindings: Map<Int, Settings.Action> = Settings.defaults().bindings
    /** While set, key presses are reported here instead of being turned into VR buttons. */
    @Volatile private var learner: ((keyCode: Int, left: Boolean) -> Unit)? = null

    fun mask(left: Boolean): Int = masks.get(if (left) 0 else 1)

    fun stick(left: Boolean): FloatArray = sticks[if (left) 0 else 1]

    fun live(left: Boolean): Live {
        val slot = if (left) 0 else 1
        return Live(connected(left), masks.get(slot), rawMasks.get(slot), sticks[slot][0], sticks[slot][1])
    }

    fun apply(state: Settings.State) {
        bindings = state.bindings
        clear()
    }

    /** Puts the filter into "press a button" mode for the settings screen. */
    fun learn(callback: ((keyCode: Int, left: Boolean) -> Unit)?) {
        learner = callback
        clear()
    }

    fun isJoyCon(device: InputDevice?): Boolean {
        device ?: return false
        val name = device.name.lowercase()
        return name.contains("joy-con") || name.contains("joycon") ||
            (device.vendorId == 0x057e && (device.productId == 0x2006 || device.productId == 0x2007))
    }

    fun isLeft(device: InputDevice): Boolean {
        val name = device.name.lowercase()
        return device.productId == 0x2006 || name.contains("left") || name.contains("(l)")
    }

    fun connected(left: Boolean): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id)
        isJoyCon(device) && isLeft(device!!) == left
    }

    /** Returns true when the event came from a Joy-Con, so the game never sees it. */
    fun onKey(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        if (!isJoyCon(device)) return false
        val left = isLeft(device!!)
        val slot = if (left) 0 else 1
        val rawBit = Settings.KNOWN_KEYS.indexOf(event.keyCode).takeIf { it >= 0 }?.let { 1 shl it } ?: 0
        if (rawBit != 0) {
            val pressedNow = event.action != KeyEvent.ACTION_UP
            while (true) {
                val current = rawMasks.get(slot)
                val next = if (pressedNow) current or rawBit else current and rawBit.inv()
                if (rawMasks.compareAndSet(slot, current, next)) break
            }
        }
        val teacher = learner
        if (teacher != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) teacher(event.keyCode, left)
            return true
        }
        val bit = bindings[event.keyCode]?.bit ?: return true
        if (bit == 0) return true
        val pressed = event.action != KeyEvent.ACTION_UP
        while (true) {
            val current = masks.get(slot)
            val next = if (pressed) current or bit else current and bit.inv()
            if (masks.compareAndSet(slot, current, next)) break
        }
        return true
    }

    /** Joy-Con sticks arrive as joystick axes; a single Joy-Con is held sideways, so axes are swapped. */
    fun onMotion(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        if (!isJoyCon(device)) return false
        val left = isLeft(device!!)
        val rawX: Float
        val rawY: Float
        if (left) {
            rawX = event.getAxisValue(MotionEvent.AXIS_X)
            rawY = event.getAxisValue(MotionEvent.AXIS_Y)
        } else {
            rawX = event.getAxisValue(MotionEvent.AXIS_Z)
            rawY = event.getAxisValue(MotionEvent.AXIS_RZ)
        }
        // Sideways: the stick's own up is the player's left on the left Joy-Con, right on the right one.
        val turn = if (left) 1f else -1f
        val slot = sticks[if (left) 0 else 1]
        slot[0] = deadzone(-rawY * turn)
        slot[1] = deadzone(rawX * turn)
        return true
    }

    private fun deadzone(value: Float): Float {
        val limited = value.coerceIn(-1f, 1f)
        return if (kotlin.math.abs(limited) < .12f) 0f else limited
    }

    fun clear() {
        masks.set(0, 0)
        masks.set(1, 0)
        rawMasks.set(0, 0)
        rawMasks.set(1, 0)
        sticks.forEach { it[0] = 0f; it[1] = 0f }
    }
}
