package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LostXRTheme {
                MainDashboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("LostXR", fontWeight = FontWeight.Bold)
                        Text("Мобильный 6DoF VR & Hand Tracking", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Статус разрешений
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Статус системы", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Доступ к камере (MediaPipe)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hasCameraPermission) "Разрешено" else "Требуется разрешение",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasCameraPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!hasCameraPermission) {
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Дать доступ")
                            }
                        }
                    }
                }
            }

            // 2. Быстрый запуск VR
            Text("Запуск окружения", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        context.startActivity(Intent(context, VrHomeActivity::class.java))
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("VR Home")
                }

                FilledTonalButton(
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        context.startActivity(Intent(context, CinemaActivity::class.java))
                    }
                ) {
                    Text("Cinema экран")
                }
            }

            // 3. Управление трекингом (Сервис)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Фоновый трекинг", style = MaterialTheme.typography.titleMedium)
                    Text("Служба трансляции координат рук и контроллеров по UDP", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                context.startService(Intent(context, HandTrackingService::class.java))
                            }
                        ) {
                            Text("Запустить службу")
                        }

                        OutlinedButton(
                            onClick = {
                                context.stopService(Intent(context, HandTrackingService::class.java))
                            }
                        ) {
                            Text("Остановить")
                        }
                    }
                }
            }

            // 4. Раздел настроек и калибровки
            Text("Конфигурация", style = MaterialTheme.typography.titleMedium)

            // Настройки трекинга (One Euro Filter, GPU/CPU, руки)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(Intent(context, TrackingSettingsActivity::class.java))
                }
            ) {
                ListItem(
                    headlineContent = { Text("Параметры трекинга LostXR") },
                    supportingContent = { Text("One Euro Filter, выбор GPU/CPU, режим отображения рук") },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) }
                )
            }

            // Калибровка Joy-Con
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(Intent(context, JoyConCameraActivity::class.java))
                }
            ) {
                ListItem(
                    headlineContent = { Text("Калибровка Joy-Con маркеров") },
                    supportingContent = { Text("Оптическая привязка контроллеров через камеру (OpenCV)") }
                )
            }

            // Тест гироскопа
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(Intent(context, GyroTestActivity::class.java))
                }
            ) {
                ListItem(
                    headlineContent = { Text("Тест гироскопа") },
                    supportingContent = { Text("Проверка ориентации и дрифта датчиков головы") }
                )
            }
        }
    }
}
