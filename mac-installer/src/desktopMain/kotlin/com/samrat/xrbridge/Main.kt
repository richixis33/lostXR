package com.samrat.xrbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zone.ien.hig.CupertinoButton
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.theme.CupertinoTheme
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "XR Bridge"
    ) {
        CupertinoTheme { InstallerScreen() }
    }
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
private fun InstallerScreen() {
    val controller = remember { AdbController() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var selectedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Подключите Android-телефон по USB и включите USB-отладку.") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            val found = withContext(Dispatchers.IO) { controller.devices() }
            devices = found
            selectedDevice = found.firstOrNull()
            status = when {
                found.isEmpty() -> "Телефон не найден. Проверьте кабель и разрешение USB-отладки."
                controller.hasOpenXrRuntime(found.first().serial) -> "Устройство готово. OpenXR runtime найден."
                else -> "Телефон подключён, но OpenXR runtime не найден. APK можно установить, но XR-игра может не запуститься."
            }
            busy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF11131A), Color(0xFF252136), Color(0xFF171922)))
        ).padding(28.dp)
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            GlassCard(Modifier.width(280.dp).fillMaxSize()) {
                TextLabel("XR Bridge", 30, Color.White)
                TextLabel("Установка Android XR игр", 14, Color(0xFFB8B8C8))
                Spacer(Modifier.height(26.dp))
                TextLabel("УСТРОЙСТВО", 12, Color(0xFF9898A8))
                Spacer(Modifier.height(8.dp))
                TextLabel(selectedDevice?.model ?: "Не подключено", 20, Color.White)
                TextLabel(selectedDevice?.serial ?: "—", 12, Color(0xFFB8B8C8))
                Spacer(Modifier.height(18.dp))
                CupertinoButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(if (busy) "Проверка…" else "Обновить устройства")
                }
                Spacer(Modifier.height(20.dp))
                TextLabel("Важно", 15, Color.White)
                TextLabel(
                    "Устанавливаются только Android APK. PCVR EXE и SteamVR-игры на телефоне не запускаются.",
                    13,
                    Color(0xFFFFC66D)
                )
            }

            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Установить OpenXR APK", 22, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(
                        selectedApk?.absolutePath ?: "Выберите APK игры, собранный для Android и архитектуры телефона.",
                        13,
                        Color(0xFFCACAD7)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CupertinoButton(onClick = {
                            chooseApk()?.let { selectedApk = it; status = "Выбран ${it.name}" }
                        }) { androidx.compose.material3.Text("Выбрать APK") }
                        CupertinoButton(
                            onClick = {
                                val device = selectedDevice ?: return@CupertinoButton
                                val apk = selectedApk ?: return@CupertinoButton
                                scope.launch {
                                    busy = true
                                    status = withContext(Dispatchers.IO) { controller.install(device.serial, apk) }
                                    busy = false
                                }
                            },
                            enabled = !busy && selectedDevice != null && selectedApk != null
                        ) { androidx.compose.material3.Text("Установить по USB") }
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Трекинг рук для Cardboard", 22, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(
                        "Устанавливает приложение камеры и трекинга рук. Оно не может внедрить руки в чужую OpenXR-игру — игра должна поддерживать hand-tracking сама.",
                        13,
                        Color(0xFFCACAD7)
                    )
                    Spacer(Modifier.height(16.dp))
                    CupertinoButton(
                        onClick = {
                            val device = selectedDevice ?: return@CupertinoButton
                            scope.launch {
                                busy = true
                                status = withContext(Dispatchers.IO) { controller.installCompanion(device.serial) }
                                busy = false
                            }
                        },
                        enabled = !busy && selectedDevice != null
                    ) { androidx.compose.material3.Text("Установить Cardboard Hands") }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Состояние", 16, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(status, 14, Color(0xFFD5D5E0))
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Инструкция", 18, Color.White)
                    Spacer(Modifier.height(10.dp))
                    TextLabel("1. На телефоне: Для разработчиков → Отладка по USB.", 13, Color(0xFFD5D5E0))
                    TextLabel("2. Подключите кабель и подтвердите RSA-ключ.", 13, Color(0xFFD5D5E0))
                    TextLabel("3. Выберите Android APK и нажмите «Установить по USB».", 13, Color(0xFFD5D5E0))
                    TextLabel("4. Запускайте игру на телефоне без Mac, если на нём есть совместимый OpenXR runtime.", 13, Color(0xFFD5D5E0))
                }
            }
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(Color(0xB82A2B36), RoundedCornerShape(24.dp)).padding(22.dp),
        verticalArrangement = Arrangement.Top,
        content = content
    )
}

@Composable
private fun TextLabel(text: String, size: Int, color: Color) {
    androidx.compose.material3.Text(text, color = color, fontSize = size.sp)
}

private fun chooseApk(): File? {
    val dialog = FileDialog(null as Frame?, "Выберите Android APK", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}

private data class Device(val serial: String, val model: String)

private class AdbController {
    private val adb: String? by lazy { findAdb() }

    fun devices(): List<Device> {
        val path = adb ?: return emptyList()
        val output = run(listOf(path, "devices", "-l"))
        return output.lineSequence().drop(1).mapNotNull { line ->
            if (!line.contains("\tdevice")) return@mapNotNull null
            val serial = line.substringBefore('\t').trim()
            val model = Regex("model:([^ ]+)").find(line)?.groupValues?.get(1) ?: "Android"
            Device(serial, model.replace('_', ' '))
        }.toList()
    }

    fun hasOpenXrRuntime(serial: String): Boolean {
        val path = adb ?: return false
        val packages = run(listOf(path, "-s", serial, "shell", "pm", "list", "packages")).lowercase()
        return listOf("openxr", "monado", "spaces", "oculus", "pico").any(packages::contains)
    }

    fun install(serial: String, apk: File): String {
        val path = adb ?: return "ADB не найден. Установите Android Platform Tools."
        if (!apk.isFile || apk.extension.lowercase() != "apk") return "Выбран некорректный APK."
        val output = run(listOf(path, "-s", serial, "install", "-r", apk.absolutePath))
        return if (output.contains("Success")) "${apk.name} успешно установлен." else output.takeLast(900)
    }

    fun installCompanion(serial: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("cardboard-hands.apk")
            ?: return "В сборке нет Cardboard Hands APK. Пересоберите установщик."
        val temp = kotlin.io.path.createTempFile("cardboard-hands-", ".apk").toFile()
        return try {
            stream.use { input -> temp.outputStream().use(input::copyTo) }
            install(serial, temp)
        } finally {
            temp.delete()
        }
    }

    private fun findAdb(): String? {
        val candidates = buildList {
            System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
            System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
            add("${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb")
            add("/opt/homebrew/bin/adb")
            add("/usr/local/bin/adb")
        }
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun run(command: List<String>): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.trim()
    } catch (error: Exception) {
        error.localizedMessage ?: "Ошибка запуска команды"
    }
}
