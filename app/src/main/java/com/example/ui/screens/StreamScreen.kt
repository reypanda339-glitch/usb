package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.StreamProtocol
import com.example.data.StreamSettings
import com.example.stream.StreamPipelineState
import com.example.stream.StreamTelemetry
import com.example.stream.UsbLinkState
import com.example.ui.theme.BroadcastCoral
import com.example.ui.theme.BroadcastCoralContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun StreamScreen(
    settings: StreamSettings,
    telemetry: StreamTelemetry,
    usbState: UsbLinkState,
    onAttachSurface: (SurfaceHolder, Int, Int) -> Unit,
    onDetachSurface: () -> Unit,
    onPermissionGranted: () -> Unit,
    onToggleStream: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onExposureChange: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onTapToFocus: (Float, Float) -> Unit,
    onToggleGrid: () -> Unit,
    onOpenPcSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            onPermissionGranted()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onToggleStream()
    }

    var hudHidden by remember { mutableStateOf(false) }
    var showExposureSlider by remember { mutableStateOf(false) }
    var focusReticleOffset by remember { mutableStateOf<Offset?>(null) }
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(focusReticleOffset) {
        if (focusReticleOffset != null) {
            delay(1200)
            focusReticleOffset = null
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionGate(
            onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                runCatching { context.startActivity(intent) }
            },
            modifier = modifier
        )
        return
    }

    val isStreaming = telemetry.pipelineState == StreamPipelineState.STREAMING ||
        telemetry.pipelineState == StreamPipelineState.STARTING

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        // Aspect-ratio-correct hardware SurfaceView viewfinder centered on obsidian canvas
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val safeAspect = telemetry.previewAspectRatio.coerceIn(0.45f, 2.2f)
            val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 0.56f
            val previewModifier = if (containerRatio > safeAspect) {
                Modifier.fillMaxSize().aspectRatio(safeAspect, matchHeightConstraintsFirst = true)
            } else {
                Modifier.fillMaxSize().aspectRatio(safeAspect, matchHeightConstraintsFirst = false)
            }

            val latestZoom by androidx.compose.runtime.rememberUpdatedState(telemetry.zoomRatio)
            val latestMaxZoom by androidx.compose.runtime.rememberUpdatedState(telemetry.maxZoom)

            Box(
                modifier = previewModifier
                    .clip(RoundedCornerShape(4.dp))
                    .onSizeChanged { viewfinderSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                val nextZoom = (latestZoom * zoom).coerceIn(1f, latestMaxZoom.coerceAtLeast(1f))
                                onZoomChange(nextZoom)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                focusReticleOffset = offset
                                val nx = offset.x / viewfinderSize.width.toFloat()
                                val ny = offset.y / viewfinderSize.height.toFloat()
                                onTapToFocus(nx, ny)
                            }
                        }
                    }
            ) {
                CameraSurfaceView(
                    previewWidth = telemetry.previewWidth,
                    previewHeight = telemetry.previewHeight,
                    onAttach = onAttachSurface,
                    onDetach = onDetachSurface,
                    modifier = Modifier.fillMaxSize()
                )

                if (settings.showGrid) {
                    RuleOfThirdsGrid(modifier = Modifier.fillMaxSize())
                }

                // Tap-to-focus reticle
                focusReticleOffset?.let { pos ->
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - 32.dp.toPx()).roundToInt(),
                                    (pos.y - 32.dp.toPx()).roundToInt()
                                )
                            }
                            .size(64.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                color = ElectricCyan,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            val len = 8.dp.toPx()
                            drawLine(
                                ElectricCyan,
                                Offset(center.x - len, center.y),
                                Offset(center.x + len, center.y),
                                strokeWidth = 1.5.dp.toPx()
                            )
                            drawLine(
                                ElectricCyan,
                                Offset(center.x, center.y - len),
                                Offset(center.x, center.y + len),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                }
            }
        }

        // Top & Bottom subtle studio vignettes for crisp HUD readability
        if (!hudHidden) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(ObsidianCanvas.copy(alpha = 0.78f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, ObsidianCanvas.copy(alpha = 0.88f))
                        )
                    )
            )
        }

        // Top Telemetry HUD
        AnimatedVisibility(
            visible = !hudHidden,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveStatusPill(
                        pipelineState = telemetry.pipelineState,
                        connectedClients = telemetry.connectedClients
                    )

                    UsbStatusChip(
                        usbState = usbState,
                        onClick = onOpenPcSetup
                    )
                }

                // Live Telemetry Strip
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioSurface.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, StudioOutline.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TelemetryStat(
                            label = "RES",
                            value = telemetry.activeResolutionLabel
                        )
                        TelemetryStat(
                            label = "FPS",
                            value = if (isStreaming) "${telemetry.actualFps}/${settings.targetFps}" else "${settings.targetFps}"
                        )
                        TelemetryStat(
                            label = "LATENCIA",
                            value = if (isStreaming && telemetry.encodeLatencyMs > 0f) {
                                "${telemetry.encodeLatencyMs} ms"
                            } else {
                                "—"
                            },
                            highlight = isStreaming && telemetry.encodeLatencyMs in 0.1f..35f
                        )
                        TelemetryStat(
                            label = if (settings.protocol == StreamProtocol.MJPEG_HTTP) "HTTP" else "TASA",
                            value = if (isStreaming && telemetry.bitrateMbps > 0f) {
                                String.format("%.1f Mb/s", telemetry.bitrateMbps)
                            } else if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
                                "Q${settings.jpegQuality}%"
                            } else {
                                "${settings.bitrateMbps} Mb/s"
                            }
                        )
                    }
                }

                telemetry.errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BroadcastCoralContainer.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, BroadcastCoral.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Minimal Restore HUD button when HUD is hidden
        if (hudHidden) {
            Surface(
                onClick = { hudHidden = false },
                shape = CircleShape,
                color = StudioSurface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, StudioOutline),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .minimumInteractiveComponentSize()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.cd_hide_hud),
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "HUD",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
            }
        }

        // Bottom Studio Control Dock
        AnimatedVisibility(
            visible = !hudHidden,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Expandable Exposure Compensation Bar
                AnimatedVisibility(visible = showExposureSlider) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = StudioSurface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, StudioOutline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "EV ${if (telemetry.exposureEv > 0) "+" else ""}${telemetry.exposureEv}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan,
                                modifier = Modifier.width(48.dp)
                            )
                            val minEv = telemetry.exposureMinEv.toFloat()
                            val maxEv = telemetry.exposureMaxEv.toFloat().coerceAtLeast(minEv + 1f)
                            Slider(
                                value = telemetry.exposureEv.toFloat().coerceIn(minEv, maxEv),
                                onValueChange = { onExposureChange(it.roundToInt()) },
                                valueRange = minEv..maxEv,
                                colors = SliderDefaults.colors(
                                    thumbColor = ElectricCyan,
                                    activeTrackColor = ElectricCyan,
                                    inactiveTrackColor = StudioOutline
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Quick Camera Tools Row
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = StudioSurface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, StudioOutline.copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioToolIcon(
                            icon = Icons.Filled.Cameraswitch,
                            contentDescription = stringResource(R.string.cd_flip_camera),
                            active = false,
                            onClick = onFlipCamera,
                            testTag = "flip_camera_button"
                        )
                        StudioToolIcon(
                            icon = if (telemetry.torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = stringResource(R.string.cd_toggle_torch),
                            active = telemetry.torchEnabled,
                            enabled = telemetry.flashSupported,
                            onClick = onToggleTorch,
                            testTag = "torch_button"
                        )
                        StudioToolIcon(
                            icon = if (telemetry.focusLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = stringResource(R.string.cd_focus_lock),
                            active = telemetry.focusLocked,
                            onClick = onToggleFocusLock,
                            testTag = "focus_lock_button"
                        )
                        StudioToolIcon(
                            icon = Icons.Filled.Exposure,
                            contentDescription = stringResource(R.string.cd_exposure),
                            active = showExposureSlider || telemetry.exposureEv != 0,
                            onClick = { showExposureSlider = !showExposureSlider },
                            testTag = "exposure_button"
                        )
                        StudioToolIcon(
                            icon = if (settings.showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                            contentDescription = stringResource(R.string.cd_toggle_grid),
                            active = settings.showGrid,
                            onClick = onToggleGrid,
                            testTag = "grid_button"
                        )
                        StudioToolIcon(
                            icon = Icons.Filled.VisibilityOff,
                            contentDescription = stringResource(R.string.cd_hide_hud),
                            active = false,
                            onClick = { hudHidden = true },
                            testTag = "hide_hud_button"
                        )
                    }
                }

                // Primary Broadcast Bar: Start/Stop + Endpoint Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onOpenPcSetup,
                        shape = RoundedCornerShape(16.dp),
                        color = StudioSurfaceElevated.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, StudioOutline),
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("endpoint_summary_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = when (settings.protocol) {
                                    StreamProtocol.MJPEG_HTTP -> "http://127.0.0.1:${settings.port}/video"
                                    else -> "tcp://127.0.0.1:${settings.port}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isStreaming) {
                                    if (telemetry.connectedClients > 0) {
                                        "${telemetry.connectedClients} cliente PC activo · ${settings.protocol.title}"
                                    } else {
                                        "Puerto abierto · Esperando PC"
                                    }
                                } else {
                                    "${settings.protocol.title} · Toca para guía PC"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!isStreaming &&
                                settings.backgroundServiceEnabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onToggleStream()
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isStreaming) BroadcastCoral else ElectricCyan,
                            contentColor = if (isStreaming) TextPrimary else ObsidianCanvas
                        ),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("start_stop_stream_button")
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isStreaming) "Detener" else "Transmitir",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSurfaceView(
    previewWidth: Int,
    previewHeight: Int,
    onAttach: (SurfaceHolder, Int, Int) -> Unit,
    onDetach: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentWidth by androidx.compose.runtime.rememberUpdatedState(previewWidth)
    val currentHeight by androidx.compose.runtime.rememberUpdatedState(previewHeight)

    val callback = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.setFixedSize(currentWidth, currentHeight)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onAttach(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onDetach()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onDetach()
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                val sizeTag = "$currentWidth:$currentHeight"
                tag = sizeTag
                holder.setFixedSize(currentWidth, currentHeight)
                holder.addCallback(callback)
            }
        },
        update = { view ->
            val sizeTag = "$previewWidth:$previewHeight"
            if (view.tag != sizeTag) {
                view.tag = sizeTag
                view.holder.setFixedSize(previewWidth, previewHeight)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val lineColor = Color.White.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), stroke)
        drawLine(lineColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), stroke)
        drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), stroke)
        drawLine(lineColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), stroke)
    }
}

@Composable
private fun LiveStatusPill(
    pipelineState: StreamPipelineState,
    connectedClients: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val isLive = pipelineState == StreamPipelineState.STREAMING
    val isStarting = pipelineState == StreamPipelineState.STARTING
    val dotColor = when {
        isLive && connectedClients > 0 -> BroadcastCoral
        isLive -> StudioAmber
        isStarting -> ElectricCyan
        else -> SignalEmerald
    }
    val label = when {
        isLive && connectedClients > 0 -> stringResource(R.string.status_live)
        isLive -> "ESPERANDO PC"
        isStarting -> stringResource(R.string.status_connecting)
        else -> stringResource(R.string.status_ready)
    }

    Surface(
        shape = CircleShape,
        color = StudioSurface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isLive) dotColor.copy(alpha = pulseAlpha) else dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UsbStatusChip(
    usbState: UsbLinkState,
    onClick: () -> Unit
) {
    val accent = when {
        usbState.isRndisActive && usbState.usbInterfaceIps.isNotEmpty() -> SignalEmerald
        usbState.isUsbConnected && usbState.isAdbActive -> ElectricCyan
        usbState.isUsbConnected -> StudioAmber
        else -> TextMuted
    }
    val text = when {
        usbState.isRndisActive && usbState.usbInterfaceIps.isNotEmpty() -> "USB RNDIS"
        usbState.isUsbConnected && usbState.isAdbActive -> "USB · ADB"
        usbState.isUsbConnected -> "USB"
        else -> "SIN USB"
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = StudioSurface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.testTag("usb_status_chip")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Usb,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
    }
}

@Composable
private fun TelemetryStat(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) SignalEmerald else TextPrimary
        )
    }
}

@Composable
private fun StudioToolIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    testTag: String
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> TextMuted.copy(alpha = 0.4f)
                active -> ElectricCyan
                else -> TextPrimary
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun CameraPermissionGate(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioOutline),
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(ElectricCyan.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.permission_camera_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.permission_camera_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianCanvas
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("grant_camera_permission_button")
                ) {
                    Text(
                        text = stringResource(R.string.permission_camera_grant),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StudioOutline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.permission_camera_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
