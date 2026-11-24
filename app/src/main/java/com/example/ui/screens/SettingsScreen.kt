package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.StreamProtocol
import com.example.data.StreamSettings
import com.example.data.VideoResolution
import com.example.stream.StreamTelemetry
import com.example.ui.theme.BroadcastCoral
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: StreamSettings,
    telemetry: StreamTelemetry,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectFps: (Int) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onJpegQualityChange: (Int) -> Unit,
    onKeyframeIntervalChange: (Float) -> Unit,
    onPortApply: (Int) -> Unit,
    onLowLatencyHardwareChange: (Boolean) -> Unit,
    onBackgroundServiceChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onStabilizationChange: (Boolean) -> Unit,
    onMirrorFrontChange: (Boolean) -> Unit,
    onAutoStreamOnUsbChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var portInput by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    val parsedPort = portInput.toIntOrNull()
    val isPortValid = parsedPort != null && parsedPort in 1024..65535

    LaunchedEffect(settings.port) {
        portInput = settings.port.toString()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Text(
                        text = "Ajustes de Video",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Calibra resolución, FPS y compresión para lograr mínima latencia.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            // 1. Resolution Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Resolución de Transmisión",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        VideoResolution.entries.forEach { res ->
                            val selected = settings.resolution == res
                            Surface(
                                onClick = { onSelectResolution(res) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) ElectricCyan.copy(alpha = 0.14f) else StudioSurfaceElevated,
                                border = BorderStroke(1.dp, if (selected) ElectricCyan else StudioOutline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("resolution_chip_${res.shortLabel}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = res.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) ElectricCyan else TextPrimary
                                    )
                                    Text(
                                        text = res.shortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) ElectricCyan else TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. FPS + Bitrate / Quality Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Fluidez y Tasa de Datos",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Cuadros por segundo (FPS)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(60, 30, 24).forEach { fps ->
                                    val supported = telemetry.supportedFpsPresets.contains(fps)
                                    val selected = settings.targetFps == fps
                                    FilterChip(
                                        selected = selected,
                                        enabled = supported,
                                        onClick = { onSelectFps(fps) },
                                        label = {
                                            Text(
                                                text = "$fps FPS",
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ElectricCyan.copy(alpha = 0.18f),
                                            selectedLabelColor = ElectricCyan,
                                            containerColor = StudioSurfaceElevated,
                                            labelColor = TextPrimary
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = supported,
                                            selected = selected,
                                            borderColor = StudioOutline,
                                            selectedBorderColor = ElectricCyan
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("fps_chip_$fps")
                                    )
                                }
                            }
                        }

                        if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Calidad JPEG",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${settings.jpegQuality}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan
                                    )
                                }
                                Slider(
                                    value = settings.jpegQuality.toFloat(),
                                    onValueChange = { onJpegQualityChange(it.roundToInt()) },
                                    valueRange = 50f..95f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = ElectricCyan,
                                        activeTrackColor = ElectricCyan,
                                        inactiveTrackColor = StudioOutline
                                    )
                                )
                            }
                        } else {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Bitrate de Video (CBR)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${settings.bitrateMbps} Mbps",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan
                                    )
                                }
                                Slider(
                                    value = settings.bitrateMbps.toFloat(),
                                    onValueChange = { onBitrateChange(it.roundToInt()) },
                                    valueRange = 2f..25f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = ElectricCyan,
                                        activeTrackColor = ElectricCyan,
                                        inactiveTrackColor = StudioOutline
                                    )
                                )
                                Text(
                                    text = "Por USB 2.0/3.0 soporta hasta 25 Mbps sin pérdidas. 6-12 Mbps es ideal.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Intervalo Keyframe (IDR)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextSecondary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(0.5f to "0.5 s", 1.0f to "1.0 s", 2.0f to "2.0 s").forEach { (sec, label) ->
                                        val selected = settings.keyframeIntervalSec == sec
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onKeyframeIntervalChange(sec) },
                                            label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ElectricCyan.copy(alpha = 0.18f),
                                                selectedLabelColor = ElectricCyan,
                                                containerColor = StudioSurfaceElevated,
                                                labelColor = TextPrimary
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = selected,
                                                borderColor = StudioOutline,
                                                selectedBorderColor = ElectricCyan
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Port Configuration Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Puerto de Socket USB",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { raw ->
                                    if (raw.length <= 5 && raw.all { it.isDigit() }) {
                                        portInput = raw
                                    }
                                },
                                label = { Text("Puerto TCP/HTTP (1024-65535)") },
                                singleLine = true,
                                isError = !isPortValid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = StudioOutline,
                                    errorBorderColor = BroadcastCoral,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("port_input")
                            )

                            Button(
                                onClick = { parsedPort?.let(onPortApply) },
                                enabled = isPortValid && parsedPort != settings.port,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = ObsidianCanvas
                                ),
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("apply_port_button")
                            ) {
                                Text("Aplicar", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 4. Low Latency & System Toggles
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Optimización de Latencia y Sistema",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )

                        SettingSwitchRow(
                            title = "Codificación Baja Latencia Hardware",
                            subtitle = "Activa KEY_LOW_LATENCY, prioridad en tiempo real y 0 B-frames.",
                            checked = settings.lowLatencyHardware,
                            onCheckedChange = onLowLatencyHardwareChange,
                            testTag = "switch_low_latency"
                        )

                        SettingSwitchRow(
                            title = "Servicio en Segundo Plano",
                            subtitle = "Mantiene la transmisión activa aunque apagues o cambies la pantalla.",
                            checked = settings.backgroundServiceEnabled,
                            onCheckedChange = onBackgroundServiceChange,
                            testTag = "switch_background_service"
                        )

                        SettingSwitchRow(
                            title = "Mantener Pantalla Encendida",
                            subtitle = "Evita que el teléfono entre en reposo durante la transmisión.",
                            checked = settings.keepScreenOn,
                            onCheckedChange = onKeepScreenOnChange,
                            testTag = "switch_keep_screen_on"
                        )

                        SettingSwitchRow(
                            title = "Estabilización de Video (EIS)",
                            subtitle = "Desactívalo para evitar ~15-30 ms de búfer adicional del sensor.",
                            checked = settings.stabilizationEnabled,
                            onCheckedChange = onStabilizationChange,
                            testTag = "switch_stabilization"
                        )

                        SettingSwitchRow(
                            title = "Auto-transmitir al conectar USB",
                            subtitle = "Inicia la transmisión automáticamente cuando enchufes el cable USB al PC.",
                            checked = settings.autoStreamOnUsb,
                            onCheckedChange = onAutoStreamOnUsbChange,
                            testTag = "switch_auto_usb"
                        )

                        SettingSwitchRow(
                            title = "Espejo en Cámara Frontal",
                            subtitle = "Voltea horizontalmente la vista de la cámara selfie.",
                            checked = settings.mirrorFrontCamera,
                            onCheckedChange = onMirrorFrontChange,
                            testTag = "switch_mirror_front"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianCanvas,
                checkedTrackColor = SignalEmerald,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = StudioSurfaceElevated,
                uncheckedBorderColor = StudioOutline
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
