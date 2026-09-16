package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** User settings. The tracking service runs in its own process, so they travel as intent extras. */
object Settings {
    const val ACTION_APPLY = "com.samrat.cardboardhands.APPLY_SETTINGS"

    /** What the camera reports to OpenXR. */
    enum class HandMode { CONTROLLERS, HANDS }

    /** Which controller the Joy-Con pretends to be. */
    enum class Layout { QUEST, NINTENDO }

    private const val PREFS = "phonexr"
    private const val KEY_SIX_DOF = "six_dof"
    private const val KEY_HAND_MODE = "hand_mode"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_BINDING = "binding_"

    /** Actions a Joy-Con button can be bound to; the order matches the VR controller inputs. */
    enum class Action(val title: String, val bit: Int) {
        NONE("—", 0),
        PRIMARY("A / X", JoyConButtons.PRIMARY),
        SECONDARY("B / Y", JoyConButtons.SECONDARY),
        TRIGGER("Курок", JoyConButtons.TRIGGER),
        SQUEEZE("Хват", JoyConButtons.SQUEEZE),
        MENU("Меню", JoyConButtons.MENU),
        STICK_CLICK("Нажатие стика", JoyConButtons.STICK_CLICK),
        SYSTEM("Система", JoyConButtons.SYSTEM),
    }

    /** Joy-Con buttons that can be remapped, named as they are printed on the controller. */
    enum class Button(val title: String, val keyCode: Int, val quest: Action, val nintendo: Action) {
        FACE_DOWN("B / A (нижняя)", KeyEvent.KEYCODE_BUTTON_A, Action.PRIMARY, Action.TRIGGER),
        FACE_RIGHT("A / B (боковая)", KeyEvent.KEYCODE_BUTTON_B, Action.PRIMARY, Action.PRIMARY),
        FACE_UP("X / Y (верхняя)", KeyEvent.KEYCODE_BUTTON_X, Action.SECONDARY, Action.SECONDARY),
        FACE_LEFT("Y / X (боковая)", KeyEvent.KEYCODE_BUTTON_Y, Action.SECONDARY, Action.SECONDARY),
        DPAD_DOWN("↓ на левом", KeyEvent.KEYCODE_DPAD_DOWN, Action.PRIMARY, Action.TRIGGER),
        DPAD_RIGHT("→ на левом", KeyEvent.KEYCODE_DPAD_RIGHT, Action.SECONDARY, Action.PRIMARY),
        DPAD_UP("↑ на левом", KeyEvent.KEYCODE_DPAD_UP, Action.SECONDARY, Action.SECONDARY),
        DPAD_LEFT("← на левом", KeyEvent.KEYCODE_DPAD_LEFT, Action.PRIMARY, Action.PRIMARY),
        ZR("ZR / ZL", KeyEvent.KEYCODE_BUTTON_R2, Action.TRIGGER, Action.TRIGGER),
        R("R / L", KeyEvent.KEYCODE_BUTTON_R1, Action.SQUEEZE, Action.SQUEEZE),
        SL("SL", KeyEvent.KEYCODE_BUTTON_L1, Action.SQUEEZE, Action.SQUEEZE),
        SR("SR", KeyEvent.KEYCODE_BUTTON_L2, Action.TRIGGER, Action.TRIGGER),
        PLUS("+ / −", KeyEvent.KEYCODE_BUTTON_START, Action.MENU, Action.MENU),
        MINUS("− (левый)", KeyEvent.KEYCODE_BUTTON_SELECT, Action.MENU, Action.MENU),
        STICK("Нажатие стика", KeyEvent.KEYCODE_BUTTON_THUMBR, Action.STICK_CLICK, Action.STICK_CLICK),
        HOME("Home / Capture", KeyEvent.KEYCODE_BUTTON_MODE, Action.SYSTEM, Action.SYSTEM),
    }

    data class State(
        val sixDof: Boolean = true,
        val handMode: HandMode = HandMode.CONTROLLERS,
        val layout: Layout = Layout.QUEST,
        val bindings: Map<Button, Action> = emptyMap()
    ) {
        fun actionFor(button: Button): Action =
            bindings[button] ?: if (layout == Layout.NINTENDO) button.nintendo else button.quest
    }

    fun load(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val layout = Layout.valueOf(prefs.getString(KEY_LAYOUT, Layout.QUEST.name) ?: Layout.QUEST.name)
        val bindings = Button.entries.mapNotNull { button ->
            val stored = prefs.getString(KEY_BINDING + button.name, null) ?: return@mapNotNull null
            button to Action.valueOf(stored)
        }.toMap()
        return State(
            sixDof = prefs.getBoolean(KEY_SIX_DOF, true),
            handMode = HandMode.valueOf(prefs.getString(KEY_HAND_MODE, HandMode.CONTROLLERS.name)!!),
            layout = layout,
            bindings = bindings
        )
    }

    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_SIX_DOF, state.sixDof)
            putString(KEY_HAND_MODE, state.handMode.name)
            putString(KEY_LAYOUT, state.layout.name)
            Button.entries.forEach { button ->
                val action = state.bindings[button]
                if (action == null) remove(KEY_BINDING + button.name) else putString(KEY_BINDING + button.name, action.name)
            }
        }.apply()
        // The service keeps its own copy in another process; a broadcast applies changes live.
        context.sendBroadcast(Intent(ACTION_APPLY).setPackage(context.packageName))
    }
}
