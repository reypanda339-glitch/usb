package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.PcClientTool
import com.example.data.StreamProtocol
import com.example.data.StreamSettings
import com.example.stream.PcAutoLauncherScript
import com.example.stream.StreamPipelineState
import com.example.stream.StreamTelemetry
import com.example.stream.UsbLinkState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SignalEmeraldContainer
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioAmberContainer
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PcSetupScreen(
    settings: StreamSettings,
    telemetry: StreamTelemetry,
    usbState: UsbLinkState,
    onSelectProtocol: (StreamProtocol) -> Unit,
    onToggleAutoStreamOnUsb: (Boolean) -> Unit,
    onRefreshUsb: () -> Unit,
    onRequestKeyFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTool by remember { mutableStateOf(PcClientTool.FFPLAY) }

    val saveBatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val script = PcAutoLauncherScript.generateWindowsBatchScript(
                context = context,
                port = settings.port,
                protocol = settings.protocol
            )
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(script.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                scope.launch {
                    snackbarHostState.showSnackbar("Guardado CamLink-AutoPC.bat. Pásalo a tu PC y ejecútalo.")
                }
            }.onFailure {
                scope.launch {
                    snackbarHostState.showSnackbar("No se pudo guardar el archivo.")
                }
            }
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val copied = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            clipboard != null
        }.getOrDefault(false)
        scope.launch {
            snackbarHostState.showSnackbar(
                if (copied) "Copiado: $label" else "No se pudo acceder al portapapeles"
            )
        }
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
                        text = "Conexión USB con PC",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Túnel USB ADB (mínima latencia) o USB Tethering RNDIS directo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            // 1. Live USB Hardware Diagnostics Card
            item {
                UsbDiagnosticsCard(
                    usbState = usbState,
                    port = settings.port,
                    onRefresh = onRefreshUsb,
                    onCopyIp = { ip -> copyToClipboard("IP USB", ip) }
                )
            }

            // 2. Automatic PC Screen Launcher Card
            item {
                AutoOpenPcCard(
                    autoStreamOnUsb = settings.autoStreamOnUsb,
                    protocol = settings.protocol,
                    onToggleAutoStreamOnUsb = onToggleAutoStreamOnUsb,
                    onSaveScript = { saveBatLauncher.launch("CamLink-AutoPC.bat") },
                    onCopyScript = {
                        val script = PcAutoLauncherScript.generateWindowsBatchScript(
                            context = context,
                            port = settings.port,
                            protocol = settings.protocol
                        )
                        copyToClipboard("Script CamLink-AutoPC.bat", script)
                    }
                )
            }

            // 3. Protocol Selection
            item {
                ProtocolSelectorCard(
                    selectedProtocol = settings.protocol,
                    hevcSupported = telemetry.hevcSupported,
                    onSelect = onSelectProtocol
                )
            }

            // 3. PC Commands & Setup Guide
            item {
                PcCommandGuideCard(
                    settings = settings,
                    usbState = usbState,
                    selectedTool = selectedTool,
                    onSelectTool = { selectedTool = it },
                    onCopy = { label, cmd -> copyToClipboard(label, cmd) }
                )
            }

            // 4. Real-time Socket Diagnostics
            item {
                SocketTelemetryCard(
                    telemetry = telemetry,
                    protocol = settings.protocol,
                    onRequestKeyFrame = onRequestKeyFrame
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun UsbDiagnosticsCard(
    usbState: UsbLinkState,
    port: Int,
    onRefresh: () -> Unit,
    onCopyIp: (String) -> Unit
) {
    val accent = when {
        usbState.isRndisActive && usbState.usbInterfaceIps.isNotEmpty() -> SignalEmerald
        usbState.isUsbConnected && usbState.isAdbActive -> ElectricCyan
        usbState.isUsbConnected -> StudioAmber
        else -> StudioAmber
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioOutline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = null,
                            tint = accent
                        )
                    }
                    Column {
                        Text(
                            text = usbState.statusHeadline,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Puerto local activo: $port",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_usb_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Actualizar diagnóstico USB",
                        tint = ElectricCyan
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticBadge(
                    label = "Cable USB",
                    active = usbState.isUsbConnected,
                    modifier = Modifier.weight(1f)
                )
                DiagnosticBadge(
                    label = "Depuración ADB",
                    active = usbState.isAdbActive,
                    modifier = Modifier.weight(1f)
                )
                DiagnosticBadge(
                    label = "RNDIS Red",
                    active = usbState.isRndisActive,
                    modifier = Modifier.weight(1f)
                )
            }

            if (usbState.isUsbConnected && !usbState.isRndisActive && !usbState.isAdbActive) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioAmberContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, StudioAmber.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = "Cable detectado en modo carga/archivos. Activa 'Anclaje USB' (botón abajo) o 'Depuración USB' para enviar video por el cable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (usbState.usbInterfaceIps.isNotEmpty()) {
                usbState.usbInterfaceIps.forEach { ip ->
                    Surface(
                        onClick = { onCopyIp(ip) },
                        shape = RoundedCornerShape(12.dp),
                        color = SignalEmeraldContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "IP directa por USB Tethering",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SignalEmerald
                                )
                                Text(
                                    text = "$ip:$port",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_command),
                                tint = SignalEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticBadge(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (active) SignalEmeraldContainer.copy(alpha = 0.5f) else StudioSurfaceElevated
    val border = if (active) SignalEmerald.copy(alpha = 0.45f) else StudioOutline
    val textCol = if (active) SignalEmerald else TextMuted

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(textCol)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) TextPrimary else TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AutoOpenPcCard(
    autoStreamOnUsb: Boolean,
    protocol: StreamProtocol,
    onToggleAutoStreamOnUsb: (Boolean) -> Unit,
    onSaveScript: () -> Unit,
    onCopyScript: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElectricCyan.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Launch,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Conexión Automática en PC / LucyCast",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Al enchufar el USB y pulsar Transmitir, LucyCast Studio o CamLink-AutoPC.bat se vinculan solos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = StudioSurfaceElevated,
                border = BorderStroke(1.dp, StudioOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-transmitir al conectar USB",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Inicia la cámara apenas detecta el cable conectado al PC.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoStreamOnUsb,
                        onCheckedChange = onToggleAutoStreamOnUsb,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianCanvas,
                            checkedTrackColor = SignalEmerald,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = StudioSurface,
                            uncheckedBorderColor = StudioOutline
                        ),
                        modifier = Modifier.testTag("pc_switch_auto_usb")
                    )
                }
            }

            Text(
                text = "Por seguridad de Windows, el PC necesita ejecutar una vez el auto-lanzador CamLink-AutoPC.bat (sin instalar programas extra si usas MJPEG + Edge, o con ffplay para H.264):",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveScript,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianCanvas
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_autopc_bat_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Guardar .bat",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onCopyScript,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StudioOutline),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("copy_autopc_bat_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Copiar script",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                }
            }

            if (protocol != StreamProtocol.MJPEG_HTTP) {
                Text(
                    text = "Nota: Para que Windows abra la ventana al instante sin instalar FFmpeg, cambia arriba a 'MJPEG sobre HTTP' (se abrirá en Microsoft Edge). Con H.264/HEVC abrirá automáticamente con ffplay o VLC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioAmber
                )
            }
        }
    }
}

@Composable
private fun ProtocolSelectorCard(
    selectedProtocol: StreamProtocol,
    hevcSupported: Boolean,
    onSelect: (StreamProtocol) -> Unit
) {
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
                text = "Protocolo de Transmisión",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                text = "H.264 TCP usa codificación hardware directa sin copias en CPU para latencia mínima.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            StreamProtocol.entries.forEach { protocol ->
                val isSelected = selectedProtocol == protocol
                val enabled = protocol != StreamProtocol.HEVC_TCP || hevcSupported
                Surface(
                    onClick = { if (enabled) onSelect(protocol) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) ElectricCyan.copy(alpha = 0.12f) else StudioSurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) ElectricCyan else StudioOutline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protocol_option_${protocol.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = protocol.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (enabled) TextPrimary else TextMuted
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) ElectricCyan.copy(alpha = 0.2f) else StudioOutline.copy(alpha = 0.45f)
                                ) {
                                    Text(
                                        text = if (!enabled) "NO SOPORTADO" else protocol.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) ElectricCyan else TextSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = protocol.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PcCommandGuideCard(
    settings: StreamSettings,
    usbState: UsbLinkState,
    selectedTool: PcClientTool,
    onSelectTool: (PcClientTool) -> Unit,
    onCopy: (String, String) -> Unit
) {
    val port = settings.port
    val ctrlPort = port + 1
    val adbCommand = "adb forward tcp:$port tcp:$port && adb forward tcp:$ctrlPort tcp:$ctrlPort"
    val targetHost = usbState.usbInterfaceIps.firstOrNull() ?: "127.0.0.1"

    val codecFlag = when (settings.protocol) {
        StreamProtocol.H264_TCP -> "h264"
        StreamProtocol.HEVC_TCP -> "hevc"
        StreamProtocol.MJPEG_HTTP -> "mjpeg"
    }

    val playerCommand = when (selectedTool) {
        PcClientTool.FFPLAY -> {
            if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                "ffplay -fflags nobuffer -flags low_delay -framedrop -f mpjpeg http://$targetHost:$port/video"
            } else {
                "ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -f $codecFlag tcp://$targetHost:$port"
            }
        }
        PcClientTool.OBS_STUDIO -> {
            if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                "http://$targetHost:$port/video"
            } else {
                "tcp://$targetHost:$port"
            }
        }
        PcClientTool.VLC -> {
            if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                "vlc --network-caching=0 http://$targetHost:$port/video"
            } else {
                "vlc --demux=$codecFlag --network-caching=0 tcp://$targetHost:$port"
            }
        }
        PcClientTool.PYTHON_OPENCV -> {
            if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                "cap = cv2.VideoCapture('http://$targetHost:$port/video')"
            } else {
                "cap = cv2.VideoCapture('tcp://$targetHost:$port', cv2.CAP_FFMPEG)"
            }
        }
    }

    val toolHint = when (selectedTool) {
        PcClientTool.FFPLAY -> "Latencia mínima en PC (~15-35 ms). Si usas OBS, puedes usar FFmpeg/VirtualCam o capturar la ventana."
        PcClientTool.OBS_STUDIO -> if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
            "En OBS: Fuentes (+) -> Origen de Navegador o Fuente Multimedia -> pega esta URL."
        } else {
            "En OBS: Fuente Multimedia -> desmarca 'Archivo local' -> Entrada: la URL TCP y en opciones FFmpeg usa: fflags=nobuffer flags=low_delay"
        }
        PcClientTool.VLC -> "Abre desde terminal o Medio -> Abrir ubicación de red con caché en 0 ms."
        PcClientTool.PYTHON_OPENCV -> "Ideal para visión por computadora o transmisión directa con OpenCV + pyvirtualcam."
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioOutline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Text(
                    text = "Guía Rápida en tu PC",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }

            val context = LocalContext.current
            // Step 1: USB Tethering (No ADB needed) or ADB Tunnel
            Text(
                text = "1. Elige cómo enlazar el cable USB:",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SignalEmeraldContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Opción A · Sin instalar ADB (Recomendado): Activa 'Anclaje USB' (Tethering) en Android y usa la IP USB detectada arriba.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    OutlinedButton(
                        onClick = {
                            val tetherIntent = Intent().apply {
                                component = ComponentName(
                                    "com.android.settings",
                                    "com.android.settings.TetherSettings"
                                )
                            }
                            runCatching { context.startActivity(tetherIntent) }
                                .onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                    }.onFailure {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                    }
                                }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("open_tether_settings_button")
                    ) {
                        Text(
                            text = "Abrir Ajustes de Anclaje USB",
                            style = MaterialTheme.typography.labelLarge,
                            color = SignalEmerald
                        )
                    }
                }
            }

            CommandCopyBox(
                title = "Opción B · Túnel ADB USB (PC)",
                command = adbCommand,
                onCopy = { onCopy("Comando ADB", adbCommand) },
                testTag = "copy_adb_button"
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StudioAmberContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, StudioAmber.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = StudioAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "¿Windows dice '\"adb\" no se reconoce'? Instálalo en PowerShell/CMD con:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    CommandCopyBox(
                        title = "Instalar ADB en Windows",
                        command = "winget install Google.PlatformTools",
                        onCopy = { onCopy("Instalar ADB", "winget install Google.PlatformTools") },
                        testTag = "copy_winget_adb_button"
                    )
                }
            }

            // Step 2: PC Client Tool selector
            Text(
                text = "2. Abre el video en tu software favorito:",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PcClientTool.entries.forEach { tool ->
                    FilterChip(
                        selected = selectedTool == tool,
                        onClick = { onSelectTool(tool) },
                        label = {
                            Text(
                                text = tool.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.18f),
                            selectedLabelColor = ElectricCyan,
                            containerColor = StudioSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedTool == tool,
                            borderColor = StudioOutline,
                            selectedBorderColor = ElectricCyan
                        )
                    )
                }
            }

            CommandCopyBox(
                title = selectedTool.label,
                command = playerCommand,
                onCopy = { onCopy(selectedTool.label, playerCommand) },
                testTag = "copy_player_button"
            )

            Text(
                text = toolHint,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun CommandCopyBox(
    title: String,
    command: String,
    onCopy: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianCanvas,
        border = BorderStroke(1.dp, StudioOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Text(
                    text = command,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(testTag)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_command),
                    tint = ElectricCyan,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun SocketTelemetryCard(
    telemetry: StreamTelemetry,
    protocol: StreamProtocol,
    onRequestKeyFrame: () -> Unit
) {
    val context = LocalContext.current
    val isLive = telemetry.pipelineState == StreamPipelineState.STREAMING

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioOutline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cable,
                        contentDescription = null,
                        tint = if (telemetry.isListening) SignalEmerald else TextMuted
                    )
                    Text(
                        text = "Estado del Socket USB",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }

                if (isLive && protocol != StreamProtocol.MJPEG_HTTP) {
                    OutlinedButton(
                        onClick = onRequestKeyFrame,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("force_keyframe_button")
                    ) {
                        Text(
                            text = "Keyframe IDR",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("ESTADO", if (telemetry.isListening) "ESCUCHANDO" else "DETENIDO")
                MetricColumn("CLIENTES PC", "${telemetry.connectedClients}")
                MetricColumn("ENVIADO", Formatter.formatShortFileSize(context, telemetry.totalBytesSent))
                MetricColumn("DESCARTADOS", "${telemetry.droppedFrames}")
            }

            if (telemetry.connectedClientIps.isNotEmpty()) {
                Text(
                    text = "Clientes conectados: ${telemetry.connectedClientIps.joinToString(", ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SignalEmerald
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
