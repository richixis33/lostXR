package com.samrat.cardboardhands

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import zone.ien.hig.CupertinoAlertDialog
import zone.ien.hig.CupertinoSwitch
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.cancel
import zone.ien.hig.default
import zone.ien.hig.section.SectionItem
import zone.ien.hig.theme.CupertinoColors
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.systemGreen
import zone.ien.hig.theme.systemRed
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private var state by mutableStateOf(Settings.State())
    private var live by mutableStateOf(JoyConBridge.Snapshot())
    /** Key code waiting for an action, or "learning" while the user presses a button. */
    private var pickedKey by mutableStateOf<Int?>(null)
    private var learning by mutableStateOf(false)
    private var receiver: android.content.BroadcastReceiver? = null
    /** Re-read on resume: the user comes back from accessibility settings. */
    private var interceptEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = Settings.load(this)
        setContent { LostXRTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        // The camera Joy-Con screen may have changed settings.
        state = Settings.load(this)
        interceptEnabled = JoyConInputService.isEnabled(this)
    }

    override fun onStart() {
        super.onStart()
        receiver = JoyConBridge.listen(this) { snapshot ->
            live = snapshot
            val learned = snapshot.learnedKey
            if (learning && learned != null) {
                learning = false
                pickedKey = learned
                JoyConBridge.watch(this, watching = true, learning = false)
            }
        }
        JoyConBridge.watch(this, watching = true, learning = false)
    }

    override fun onStop() {
        super.onStop()
        JoyConBridge.watch(this, watching = false, learning = false)
        receiver?.let { unregisterReceiver(it) }
        receiver = null
    }

    private fun update(next: Settings.State) {
        state = next
        Settings.save(this, next)
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun Screen() {
        HigPage(title = "Управление", onBack = ::finish) {
            HigSection(
                title = "Отслеживание",
                footer = "Без 6DoF рука остаётся на постоянном расстоянии и только поворачивается."
            ) {
                SectionItem(trailingContent = {
                    CupertinoSwitch(checked = state.sixDof, onCheckedChange = { update(state.copy(sixDof = it)) })
                }) { CupertinoText("6DoF по камере") }
            }

            HigSection(title = "Руки") {
                HigChoice(
                    "Жесты нажимают",
                    "Щипок и кулак работают как кнопки контроллера",
                    state.handMode == Settings.HandMode.CONTROLLERS
                ) { update(state.copy(handMode = Settings.HandMode.CONTROLLERS)) }
                HigChoice(
                    "Только руки",
                    "Игра получает руки без нажатий",
                    state.handMode == Settings.HandMode.HANDS
                ) { update(state.copy(handMode = Settings.HandMode.HANDS)) }
            }

            HigSection(
                title = "Joy‑Con",
                footer = if (interceptEnabled) "Нажмите кнопку на Joy‑Con — она подсветится на схеме. " +
                    "Нажмите на кнопку на схеме, чтобы назначить ей действие."
                else "Без перехвата кнопки Joy‑Con уходят игре как геймпад, а не как контроллеры VR. " +
                    "Включите «LostXR Joy‑Con» в «Специальных возможностях»."
            ) {
                HigRow(
                    "Перехват кнопок",
                    if (interceptEnabled) "Включён" else "Выключен",
                    detailColor = if (interceptEnabled) CupertinoColors.systemGreen else CupertinoColors.systemRed
                )
                if (!interceptEnabled) {
                    HigLink("Включить перехват") { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                JoyConDiagram(state, live.left, live.right) { pickedKey = it }
            }

            HigSection(
                title = "Поворот и положение Joy‑Con",
                footer = "Если гироскоп Joy‑Con недоступен, камера может сама находить Joy‑Con по цвету."
            ) {
                val gyro = gyroStatus()
                HigRow("Гироскоп Joy‑Con", gyro.first, detailColor = gyro.second)
                HigLink("Проверить гироскоп") {
                    startActivity(Intent(this@SettingsActivity, GyroTestActivity::class.java))
                }
                HigLink("Joy‑Con через камеру", value = if (state.cameraJoyCons) "Вкл." else "Выкл.") {
                    startActivity(Intent(this@SettingsActivity, JoyConCameraActivity::class.java))
                }
            }

            HigSection(
                title = "Метки на Joy‑Con",
                footer = "Самый точный режим: камера видит напечатанные метки ArUco и даёт положение и полный поворот. " +
                    "Распечатайте markers/joycon_markers_A4.pdf в масштабе 100%. ID 0–3 — левый Joy‑Con, 4–7 — правый: " +
                    "слева, середина (сторона с кнопками), справа, сверху. Ровно держите Joy‑Con кнопками к себе, " +
                    "верхом вверх — это «вперёд». После включения остановите и снова запустите трекинг."
            ) {
                SectionItem(trailingContent = {
                    CupertinoSwitch(
                        checked = state.markerJoyCons,
                        onCheckedChange = { update(state.copy(markerJoyCons = it)) }
                    )
                }) { CupertinoText("Отслеживать по меткам") }
            }

            HigSection(
                title = "Сейчас",
                footer = if (!JoyConInputService.sticksSupported) "Стик читается только на Android 14 и новее." else null
            ) {
                HigRow("Левый стик", stick(live.left))
                HigRow("Правый стик", stick(live.right))
                HigRow("Левая рука", pressed(live.left.buttons))
                HigRow("Правая рука", pressed(live.right.buttons))
            }

            HigSection {
                HigLink("Назначить нажатием кнопки") {
                    learning = true
                    JoyConBridge.watch(this@SettingsActivity, watching = true, learning = true)
                }
                HigLink("Сбросить раскладку") { update(state.copy(bindings = Settings.defaults().bindings)) }
            }
        }

        if (learning) LearningDialog()
        pickedKey?.let { ActionDialog(it) }
    }

    /** Joy-Con rotation needs a gyroscope, and not every Android kernel exposes one. */
    @Composable
    private fun gyroStatus(): Pair<String, androidx.compose.ui.graphics.Color> {
        val secondary = CupertinoTheme.colorScheme.secondaryLabel
        if (android.os.Build.VERSION.SDK_INT < 31) return "Нужен Android 12 или новее" to CupertinoColors.systemRed
        val devices = android.view.InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> android.view.InputDevice.getDevice(id) }
            .filter { device -> JoyConButtons.isJoyCon(device) }
        if (devices.isEmpty()) return "Joy‑Con не найдены — подключите их по Bluetooth" to secondary
        val withGyro = devices.count { device ->
            device.sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).any { sensor ->
                sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE ||
                    sensor.type == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR
            }
        }
        return if (withGyro > 0) "Есть у $withGyro из ${devices.size}: поворот руки работает без камеры" to CupertinoColors.systemGreen
        else "Недоступны — поворот руки берётся только с камеры" to CupertinoColors.systemRed
    }

    private fun stick(live: JoyConButtons.Live) =
        "вперёд ${(live.stickY * 100).roundToInt()}%, вбок ${(live.stickX * 100).roundToInt()}%"

    private fun pressed(mask: Int) =
        Settings.Action.entries.filter { it.bit != 0 && mask and it.bit != 0 }
            .joinToString(", ") { it.title }
            .ifEmpty { "ничего не нажато" }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun LearningDialog() {
        val stop = {
            learning = false
            JoyConBridge.watch(this, watching = true, learning = false)
        }
        CupertinoAlertDialog(
            onDismissRequest = stop,
            title = { CupertinoText("Нажмите кнопку на Joy‑Con") },
            message = { CupertinoText("LostXR ждёт нажатия. Дальше выберите, что эта кнопка делает в VR.") }
        ) { cancel(onClick = stop) { CupertinoText("Отмена") } }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun ActionDialog(keyCode: Int) {
        CupertinoAlertDialog(
            onDismissRequest = { pickedKey = null },
            title = { CupertinoText("Кнопка ${Settings.keyName(keyCode)}") },
            message = { CupertinoText("Что она делает в VR:") },
            buttonsOrientation = Orientation.Vertical
        ) {
            Settings.Action.entries.forEach { action ->
                default(onClick = {
                    update(state.copy(bindings = state.bindings + (keyCode to action)))
                    pickedKey = null
                }) {
                    val current = if (state.bindings[keyCode] == action) " ✓" else ""
                    CupertinoText("${action.title}$current")
                }
            }
            cancel(onClick = { pickedKey = null }) { CupertinoText("Отмена") }
        }
    }
}
