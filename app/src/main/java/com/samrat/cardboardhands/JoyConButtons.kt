package com.samrat.cardboardhands

import android.view.InputDevice
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicIntegerArray

/** Joy-Con button state shared between the accessibility filter and the hand-tracking service. */
object JoyConButtons {
    const val PRIMARY = 1
    const val SECONDARY = 1 shl 1
    const val TRIGGER = 1 shl 2
    const val SQUEEZE = 1 shl 3
    const val MENU = 1 shl 4
    const val STICK_CLICK = 1 shl 5
    const val SYSTEM = 1 shl 6

    private val masks = AtomicIntegerArray(2)
    @Volatile private var bindings: Map<Int, Int> = bindingsFor(Settings.State())

    fun mask(left: Boolean): Int = masks.get(if (left) 0 else 1)

    fun apply(state: Settings.State) {
        bindings = bindingsFor(state)
        clear()
    }

    private fun bindingsFor(state: Settings.State): Map<Int, Int> =
        Settings.Button.entries.associate { it.keyCode to state.actionFor(it).bit }

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

    /** Returns true when the key came from a Joy-Con, so the game never sees it. */
    fun onKey(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        if (!isJoyCon(device)) return false
        val bit = bindings[event.keyCode] ?: return true
        if (bit == 0) return true
        val slot = if (isLeft(device!!)) 0 else 1
        val pressed = event.action != KeyEvent.ACTION_UP
        while (true) {
            val current = masks.get(slot)
            val next = if (pressed) current or bit else current and bit.inv()
            if (masks.compareAndSet(slot, current, next)) break
        }
        return true
    }

    fun clear() {
        masks.set(0, 0)
        masks.set(1, 0)
    }
}
