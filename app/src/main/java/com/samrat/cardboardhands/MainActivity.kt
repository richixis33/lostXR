package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.hardware.Sensor
import android.view.Gravity
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var patchButton: MaterialButton
    private var startAfterPermission = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) startTrackingAndChooseApp()
        else if (!granted) status.text = "Для рук нужен доступ к камере"
        startAfterPermission = false
    }
    private val chooseApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) patchApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(30))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "PhoneXR"
            textSize = 42f
            setTextColor(getColor(com.google.android.material.R.color.material_dynamic_primary80))
        }, margins(-1, -2, 0, 0, 0, 4))
        root.addView(TextView(this).apply {
            text = "Запускайте приложения с камерой и жестами рук"
            textSize = 17f
        }, margins(-1, -2, 0, 0, 0, 26))

        root.addView(card(
            "Трекинг рук",
            "Кулак — trigger  •  ☝ — Y/B  •  👍 — X/A\nJoy‑Con: подключите оба в Bluetooth — поворот включится автоматически",
            primaryButton("Запустить") { requestStart() }
        ), margins(-1, -2, 0, 0, 0, 16))

        root.addView(card(
            "Проверка камеры",
            "Посмотрите, видит ли PhoneXR обе руки и скелет пальцев.",
            secondaryButton("Проверить руки") {
                stopService(Intent(this, HandTrackingService::class.java))
                startActivity(Intent(this, HandTestActivity::class.java))
            }
        ), margins(-1, -2, 0, 0, 0, 16))

        root.addView(card(
            "Joy‑Con",
            "Камера отслеживает руки, а гироскопы Joy‑Con — их плавный поворот.",
            secondaryButton("Проверить Joy‑Con") { status.text = joyConStatus() }
        ), margins(-1, -2, 0, 0, 0, 16))

        patchButton = secondaryButton("Выбрать APK или .pxr") {
            chooseApk.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
        }
        root.addView(card(
            "Установка игр",
            "Выберите Quest/OpenXR APK или универсальный .pxr — PhoneXR возьмёт Android-сборку, подготовит её и откроет установку.",
            patchButton
        ), margins(-1, -2, 0, 0, 0, 16))

        root.addView(secondaryButton("Остановить трекинг") {
            stopService(Intent(this, HandTrackingService::class.java))
            status.text = "Трекинг остановлен"
        }, margins(-1, dp(52), 0, 4, 0, 18))

        status = TextView(this).apply {
            text = "Готово к запуску"
            gravity = Gravity.CENTER
            textSize = 14f
        }
        root.addView(status, margins(-1, -2, 0, 0, 0, 0))
        setContentView(scroll)
    }

    private fun requestStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTrackingAndChooseApp()
        } else {
            startAfterPermission = true
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTrackingAndChooseApp() {
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        status.text = "Камера рук включена"
        showApps()
    }

    private fun showApps() {
        val xrCategories = listOf(
            "org.khronos.openxr.intent.category.IMMERSIVE_HMD",
            "com.oculus.intent.category.VR"
        )
        val apps = xrCategories.flatMap { category ->
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(category),
                PackageManager.MATCH_ALL
            )
        }
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) {
            status.text = "OpenXR-приложения не найдены"
            return
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        apps.forEach { app ->
            val item = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = app.loadLabel(packageManager)
                icon = app.loadIcon(packageManager)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                iconSize = dp(42)
                insetTop = 0
                insetBottom = 0
                maxLines = 2
                setOnClickListener {
                    dismissDialog?.dismiss()
                    val info = app.activityInfo
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        setClassName(info.packageName, info.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(104)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        dismissDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Выберите OpenXR-приложение")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Отмена", null)
            .create()
        dismissDialog?.show()
    }

    private var dismissDialog: android.app.Dialog? = null

    private fun patchApk(uri: Uri) {
        patchButton.isEnabled = false
        status.text = "Подготовка APK…"
        Thread {
            try {
                val payload = PxrPackage.androidPayload(this, uri)
                val apk = ApkPatcher.patch(this, payload)
                runOnUiThread {
                    patchButton.isEnabled = true
                    if (!packageManager.canRequestPackageInstalls()) {
                        status.text = "Разрешите установку и выберите APK снова"
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } else {
                        status.text = "APK готов"
                        val content = FileProvider.getUriForFile(this, "$packageName.patched.apks", apk)
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(content, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    patchButton.isEnabled = true
                    status.text = "Ошибка APK: ${error.localizedMessage}"
                }
            }
        }.start()
    }

    private fun joyConStatus(): String {
        if (Build.VERSION.SDK_INT < 31) return "Для датчиков Joy‑Con нужен Android 12 или новее"
        val devices = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.filter {
            val name = it.name.lowercase()
            name.contains("joy-con") || name.contains("joycon") ||
                (it.vendorId == 0x057e && (it.productId == 0x2006 || it.productId == 0x2007))
        }.toList()
        if (devices.isEmpty()) return "Joy‑Con не найдены — подключите их в настройках Bluetooth"
        val withMotion = devices.count { device ->
            device.sensorManager.getSensorList(Sensor.TYPE_ALL).any { sensor ->
                sensor.type == Sensor.TYPE_GYROSCOPE || sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    sensor.type == Sensor.TYPE_ROTATION_VECTOR
            }
        }
        return "Joy‑Con: ${devices.size}, с датчиками движения: $withMotion"
    }

    private fun card(title: String, description: String, action: MaterialButton): MaterialCardView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(TextView(this@MainActivity).apply { text = title; textSize = 21f }, margins(-1, -2, 0, 0, 0, 7))
            addView(TextView(this@MainActivity).apply { text = description; textSize = 14f }, margins(-1, -2, 0, 0, 0, 15))
            addView(action, margins(-1, dp(52), 0, 0, 0, 0))
        }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            addView(content)
        }
    }

    private fun primaryButton(title: String, action: () -> Unit) = MaterialButton(this).apply {
        text = title; textSize = 16f; cornerRadius = dp(18); setOnClickListener { action() }
    }
    private fun secondaryButton(title: String, action: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title; textSize = 15f; cornerRadius = dp(18); setOnClickListener { action() }
        }
    private fun margins(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
