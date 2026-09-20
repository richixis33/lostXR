package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** User settings. The tracking service runs in its own process, so changes travel as a broadcast. */
object Settings {
    const val ACTION_APPLY = "com.samrat.cardboardhands.APPLY_SETTINGS"

    /** What the camera reports to OpenXR. */
    enum class HandMode { CONTROLLERS, HANDS }

    /** A VR controller input a Joy-Con button can be bound to. */
    enum class Action(val title: String, val hint: String, val bit: Int) {
        TRIGGER("Курок", "удар, выстрел, выбор в меню", JoyConButtons.TRIGGER),
        SQUEEZE("Захват", "взять предмет, держать саблю", JoyConButtons.SQUEEZE),
        PRIMARY("A / X", "нижняя кнопка контроллера", JoyConButtons.PRIMARY),
        SECONDARY("B / Y", "верхняя кнопка контроллера", JoyConButtons.SECONDARY),
        MENU("Меню", "пауза, выход в меню игры", JoyConButtons.MENU),
        STICK_CLICK("Нажатие стика", "бег, приседание — зависит от игры", JoyConButtons.STICK_CLICK),
        SYSTEM("Системная", "редко используется играми", JoyConButtons.SYSTEM),
        NONE("Не назначено", "кнопка ничего не делает", 0),
    }

    /**
     * Joy-Con key codes as Android reports them. Several physical buttons share a code
     * (SL with L, SR with ZL), so buttons are bound by pressing them, never by a guessed name.
     */
    private val DEFAULT_BINDINGS = mapOf(
        KeyEvent.KEYCODE_BUTTON_R2 to Action.TRIGGER,   // ZR
        KeyEvent.KEYCODE_BUTTON_L2 to Action.TRIGGER,   // ZL
        KeyEvent.KEYCODE_BUTTON_R1 to Action.SQUEEZE,   // R и SR
        KeyEvent.KEYCODE_BUTTON_L1 to Action.SQUEEZE,   // L и SL
        KeyEvent.KEYCODE_BUTTON_A to Action.PRIMARY,
        KeyEvent.KEYCODE_BUTTON_B to Action.SECONDARY,
        KeyEvent.KEYCODE_BUTTON_X to Action.PRIMARY,
        KeyEvent.KEYCODE_BUTTON_Y to Action.SECONDARY,
        KeyEvent.KEYCODE_DPAD_DOWN to Action.PRIMARY,
        KeyEvent.KEYCODE_DPAD_LEFT to Action.PRIMARY,
        KeyEvent.KEYCODE_DPAD_UP to Action.SECONDARY,
        KeyEvent.KEYCODE_DPAD_RIGHT to Action.SECONDARY,
        KeyEvent.KEYCODE_BUTTON_START to Action.MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to Action.MENU,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Action.STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_THUMBR to Action.STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_MODE to Action.SYSTEM,
    )

    /** Every Joy-Con key LostXR knows about, in the order used by the live diagram. */
    val KNOWN_KEYS: List<Int> = listOf(
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_MODE
    )

    private const val PREFS = "phonexr"
    private const val KEY_SIX_DOF = "six_dof"
    private const val KEY_HAND_MODE = "hand_mode"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_CAMERA_JOYCONS = "camera_joycons"
    private const val KEY_MARKER_JOYCONS = "marker_joycons"
    private const val KEY_LEFT_COLOR = "left_joycon_color"
    private const val KEY_RIGHT_COLOR = "right_joycon_color"

    data class State(
        val sixDof: Boolean = true,
        val handMode: HandMode = HandMode.CONTROLLERS,
        /** Android key code of a Joy-Con button to the VR input it presses. */
        val bindings: Map<Int, Action> = DEFAULT_BINDINGS,
        /** Position and rotation of the Joy-Con come from the camera, which finds them by colour. */
        val cameraJoyCons: Boolean = false,
        /** Position and full rotation from printed ArUco markers on the Joy-Con (markers/joycon_markers_A4.pdf). */
        val markerJoyCons: Boolean = false,
        val leftColor: JoyConVision.Target = JoyConVision.Target.NEON_BLUE,
        val rightColor: JoyConVision.Target = JoyConVision.Target.NEON_RED
    ) {
        fun keysFor(action: Action): List<Int> =
            bindings.filterValues { it == action }.keys.sorted()
    }

    fun load(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BINDINGS, null)
        val bindings = stored?.split(',')?.mapNotNull { pair ->
            val (key, action) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val code = key.toIntOrNull() ?: return@mapNotNull null
            code to (Action.entries.firstOrNull { it.name == action } ?: return@mapNotNull null)
        }?.toMap() ?: DEFAULT_BINDINGS
        return State(
            sixDof = prefs.getBoolean(KEY_SIX_DOF, true),
            handMode = HandMode.valueOf(prefs.getString(KEY_HAND_MODE, HandMode.CONTROLLERS.name)!!),
            bindings = bindings,
            cameraJoyCons = prefs.getBoolean(KEY_CAMERA_JOYCONS, false),
            markerJoyCons = prefs.getBoolean(KEY_MARKER_JOYCONS, false),
            leftColor = JoyConVision.Target.decode(prefs.getString(KEY_LEFT_COLOR, null)) ?: JoyConVision.Target.NEON_BLUE,
            rightColor = JoyConVision.Target.decode(prefs.getString(KEY_RIGHT_COLOR, null)) ?: JoyConVision.Target.NEON_RED
        )
    }

    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SIX_DOF, state.sixDof)
            .putString(KEY_HAND_MODE, state.handMode.name)
            .putString(KEY_BINDINGS, state.bindings.entries.joinToString(",") { "${it.key}:${it.value.name}" })
            .putBoolean(KEY_CAMERA_JOYCONS, state.cameraJoyCons)
            .putBoolean(KEY_MARKER_JOYCONS, state.markerJoyCons)
            .putString(KEY_LEFT_COLOR, state.leftColor.encode())
            .putString(KEY_RIGHT_COLOR, state.rightColor.encode())
            .apply()
        // The tracking service keeps its own copy in another process.
        context.sendBroadcast(Intent(ACTION_APPLY).setPackage(context.packageName))
    }

    fun defaults() = State()

    /** The name chosen in the first setup, shown in VR. */
    fun userName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_NAME, "") ?: ""

    fun setUserName(context: Context, name: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_USER_NAME, name).apply()

    /** The first-start setup in the headset has been completed (or skipped to the end). */
    fun setupDone(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, done: Boolean = true) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DONE, done).apply()

    private const val KEY_USER_NAME = "user_name"
    private const val KEY_SETUP_DONE = "setup_done"

    /** Human-readable name for a key code, used when showing what is bound. */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L / SL"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R / SR"
        KeyEvent.KEYCODE_BUTTON_L2 -> "ZL"
        KeyEvent.KEYCODE_BUTTON_R2 -> "ZR"
        KeyEvent.KEYCODE_BUTTON_START -> "+"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "−"
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> "стик"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Home"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        else -> "код $code"
    }
}
