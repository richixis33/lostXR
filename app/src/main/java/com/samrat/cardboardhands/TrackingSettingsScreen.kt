package com.samrat.cardboardhands

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var handMode by remember { mutableIntStateOf(TrackingSettings.getHandRenderMode(context)) }
    var delegate by remember { mutableIntStateOf(TrackingSettings.getDelegate(context)) }
    var smoothing by remember { mutableStateOf(TrackingSettings.isSmoothingEnabled(context)) }
    var minCutoff by remember { mutableFloatStateOf(TrackingSettings.getMinCutoff(context)) }
    var beta by remember { mutableFloatStateOf(TrackingSettings.getBeta(context)) }
    var confidence by remember { mutableFloatStateOf(TrackingSettings.getConfidence(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки трекинга LostXR") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Назад") }
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
            // 1. Аппаратное ускорение (Обработка)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Ускоритель обработки (MediaPipe)", style = MaterialTheme.typography.titleMedium)
                    Text("GPU снижает задержку трекинга, CPU снижает нагрев", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = delegate == TrackingSettings.DELEGATE_GPU,
                            onClick = {
                                delegate = TrackingSettings.DELEGATE_GPU
                                TrackingSettings.setDelegate(context, TrackingSettings.DELEGATE_GPU)
                            },
                            label = { Text("GPU (Быстрее)") }
                        )
                        FilterChip(
                            selected = delegate == TrackingSettings.DELEGATE_CPU,
                            onClick = {
                                delegate = TrackingSettings.DELEGATE_CPU
                                TrackingSettings.setDelegate(context, TrackingSettings.DELEGATE_CPU)
                            },
                            label = { Text("CPU (Экономно)") }
                        )
                    }
                }
            }

            // 2. Отображение и количество рук
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Отображение рук в VR", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val modes = listOf(
                        TrackingSettings.HANDS_BOTH to "Обе руки",
                        TrackingSettings.HANDS_RIGHT_ONLY to "Только правая рука",
                        TrackingSettings.HANDS_LEFT_ONLY to "Только левая рука",
                        TrackingSettings.HANDS_DISABLED to "Отключить трекинг рук"
                    )

                    modes.forEach { (m, title) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (handMode == m),
                                    onClick = {
                                        handMode = m
                                        TrackingSettings.setHandRenderMode(context, m)
                                    }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (handMode == m), onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(title)
                        }
                    }
                }
            }

            // 3. Сглаживание 1€ Filter
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Фильтр сглаживания (1€)", style = MaterialTheme.typography.titleMedium)
                            Text("Гасит дрожание пальцев в покое", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = smoothing,
                            onCheckedChange = {
                                smoothing = it
                                TrackingSettings.setSmoothingEnabled(context, it)
                            }
                        )
                    }

                    if (smoothing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Стабильность покоя (Min Cutoff): ${"%.2f".format(minCutoff)}")
                        Slider(
                            value = minCutoff,
                            onValueChange = {
                                minCutoff = it
                                TrackingSettings.setMinCutoff(context, it)
                            },
                            valueRange = 0.1f..3.0f
                        )

                        Text("Отзывчивость при резком движении (Beta): ${"%.4f".format(beta)}")
                        Slider(
                            value = beta,
                            onValueChange = {
                                beta = it
                                TrackingSettings.setBeta(context, it)
                            },
                            valueRange = 0.001f..0.04f
                        )
                    }
                }
            }

            // 4. Чувствительность распознавания
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Порог уверенности детектора: ${"%.2f".format(confidence)}")
                    Slider(
                        value = confidence,
                        onValueChange = {
                            confidence = it
                            TrackingSettings.setConfidence(context, it)
                        },
                        valueRange = 0.3f..0.9f
                    )
                }
            }
        }
    }
}
