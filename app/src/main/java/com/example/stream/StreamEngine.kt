package com.example.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.example.data.CameraLens
import com.example.data.StreamProtocol
import com.example.data.StreamSettings
import com.example.data.VideoResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StreamPipelineState {
    IDLE,
    PREVIEW_ONLY,
    STARTING,
    STREAMING,
    ERROR
}

data class StreamTelemetry(
    val pipelineState: StreamPipelineState = StreamPipelineState.IDLE,
    val errorMessage: String? = null,
    val actualFps: Int = 0,
    val encodeLatencyMs: Float = 0f,
    val bitrateMbps: Float = 0f,
    val totalBytesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val connectedClients: Int = 0,
    val connectedClientIps: List<String> = emptyList(),
    val activeResolutionLabel: String = "1280×720",
    val previewWidth: Int = 1280,
    val previewHeight: Int = 720,
    val activePort: Int = 7878,
    val isListening: Boolean = false,
    val torchEnabled: Boolean = false,
    val focusLocked: Boolean = false,
    val exposureEv: Int = 0,
    val exposureMinEv: Int = -6,
    val exposureMaxEv: Int = 6,
    val zoomRatio: Float = 1.0f,
    val maxZoom: Float = 4.0f,
    val flashSupported: Boolean = false,
    val hevcSupported: Boolean = false,
    val supportedFpsPresets: List<Int> = listOf(60, 30, 24),
    val previewAspectRatio: Float = 16f / 9f
)

class StreamEngine(
    private val context: Context,
    private val onRequestProtocolSwitch: (StreamProtocol) -> Unit = {},
    private val onRemoteStartRequested: () -> Unit = {},
    private val onRemoteStopRequested: () -> Unit = {},
    private val onRemoteLensSwitch: (CameraLens) -> Unit = {},
    private val onRemoteResolutionSwitch: (VideoResolution) -> Unit = {},
    private val onRemoteFpsSwitch: (Int) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("CamLinkCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val encoderThread = HandlerThread("CamLinkEncoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    private val encoderLock = Any()

    @Volatile
    private var isEncoderActive = false

    @Volatile
    private var isRestartingPipeline = false

    @Volatile
    private var currentUsbIps: List<String> = emptyList()

    private val socketServer = SocketStreamServer(
        onClientNeedsSyncFrame = { requestKeyFrame() }
    )
    private val udpBeacon = UdpDiscoveryBeacon(
        onSwitchProtocolRequested = { proto ->
            scope.launch {
                onRequestProtocolSwitch(proto)
            }
        },
        onStartStreamRequested = {
            scope.launch {
                if (hasCameraPermission()) {
                    onRemoteStartRequested()
                }
            }
        },
        onStopStreamRequested = {
            scope.launch {
                onRemoteStopRequested()
            }
        },
        onKeyframeRequested = {
            requestKeyFrame()
        },
        onFlipLensRequested = { requestedLens ->
            val target = requestedLens ?: if (currentSettings.lens == CameraLens.BACK) {
                CameraLens.FRONT
            } else {
                CameraLens.BACK
            }
            scope.launch {
                onRemoteLensSwitch(target)
            }
            target
        },
        onTorchRequested = { state ->
            setTorchMode(state)
        },
        onResolutionRequested = { resolution ->
            scope.launch {
                onRemoteResolutionSwitch(resolution)
            }
        },
        onFpsRequested = { fps ->
            scope.launch {
                onRemoteFpsSwitch(fps)
            }
        }
    )

    private val _telemetry = MutableStateFlow(
        StreamTelemetry(hevcSupported = CameraCapabilityInspector.isHevcEncoderSupported())
    )
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private var currentSettings = StreamSettings()
    private var currentDescriptor: CameraDescriptor? = null
    private var resolvedStreamSize: Size = Size(1280, 720)
    private var displayRotationDegrees: Int = 0

    private var cameraDevice: CameraDevice? = null
    private var isOpeningCamera = false
    private var isClosingCamera = false
    private var pendingReopenOnClose = false
    private var hasAttemptedFallbackSize = false
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewWidthAttached: Int = 0
    private var previewHeightAttached: Int = 0

    private var mediaCodec: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var mjpegImageReader: ImageReader? = null

    @Volatile
    private var torchEnabled = false
    private var focusLocked = false
    private var exposureEv = 0
    private var zoomRatio = 1.0f

    private var fpsFrameCounter = 0
    private var fpsWindowStartNs = System.nanoTime()
    private var smoothedLatencyMs = 0f

    private var nv21Scratch: ByteArray? = null
    private val jpegScratchOut = ByteArrayOutputStream(128 * 1024)

    init {
        refreshDescriptorMetadata(currentSettings)
        syncDiscoverySnapshot()
        scope.launch {
            socketServer.stats.collect { stats ->
                _telemetry.value = _telemetry.value.copy(
                    bitrateMbps = stats.instantBitrateMbps,
                    totalBytesSent = stats.totalBytesSent,
                    droppedFrames = stats.droppedFrames,
                    connectedClients = stats.connectedClients,
                    connectedClientIps = stats.clientAddresses,
                    activePort = stats.port,
                    isListening = stats.isListening,
                    errorMessage = stats.lastError ?: _telemetry.value.errorMessage
                )
                syncDiscoverySnapshot()
            }
        }
    }

    fun updateSettings(settings: StreamSettings) {
        val old = currentSettings
        currentSettings = settings

        val lensChanged = old.lens != settings.lens
        val resolutionChanged = old.resolution != settings.resolution
        val protocolChanged = old.protocol != settings.protocol
        val portChanged = old.port != settings.port
        val encoderConfigChanged = old.bitrateMbps != settings.bitrateMbps ||
            old.targetFps != settings.targetFps ||
            old.keyframeIntervalSec != settings.keyframeIntervalSec ||
            old.lowLatencyHardware != settings.lowLatencyHardware ||
            old.jpegQuality != settings.jpegQuality

        val isStreaming = _telemetry.value.pipelineState == StreamPipelineState.STREAMING

        if (lensChanged) {
            torchEnabled = false
            focusLocked = false
            exposureEv = 0
            zoomRatio = 1.0f
            hasAttemptedFallbackSize = false
            _telemetry.value = _telemetry.value.copy(
                torchEnabled = false,
                focusLocked = false,
                exposureEv = 0,
                zoomRatio = 1.0f
            )
        }
        if (resolutionChanged) {
            hasAttemptedFallbackSize = false
        }

        if (isStreaming && (lensChanged || resolutionChanged || protocolChanged || portChanged || encoderConfigChanged)) {
            restartPipeline(settings, keepStreaming = true, reopenCamera = lensChanged)
        } else if (lensChanged || resolutionChanged) {
            if (cameraDevice != null) {
                restartPipeline(settings, keepStreaming = false, reopenCamera = lensChanged)
            } else {
                refreshDescriptorMetadata(settings)
            }
        } else if (old.targetFps != settings.targetFps || old.stabilizationEnabled != settings.stabilizationEnabled) {
            cameraHandler.post { applyRepeatingRequest() }
        }
        syncDiscoverySnapshot()
    }

    fun updateUsbIps(ips: List<String>) {
        if (currentUsbIps != ips) {
            currentUsbIps = ips
            syncDiscoverySnapshot()
        }
    }

    fun updateDisplayRotation(rotationDegrees: Int) {
        if (displayRotationDegrees != rotationDegrees) {
            displayRotationDegrees = rotationDegrees
            updatePreviewAspectRatio()
        }
    }

    fun attachPreviewSurface(holder: SurfaceHolder, width: Int, height: Int) {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        cameraHandler.post {
            if (previewSurface === surface &&
                previewWidthAttached == width &&
                previewHeightAttached == height &&
                (captureSession != null || isOpeningCamera)
            ) {
                return@post
            }
            previewSurface = surface
            previewWidthAttached = width
            previewHeightAttached = height
            if (cameraDevice == null) {
                openCameraAndConfigure()
            } else {
                recreateCaptureSession()
            }
        }
    }

    fun detachPreviewSurface() {
        if (Looper.myLooper() == cameraThread.looper) {
            detachPreviewSurfaceInternal()
            return
        }
        val latch = CountDownLatch(1)
        val posted = cameraHandler.post {
            try {
                detachPreviewSurfaceInternal()
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            runCatching { latch.await(300L, TimeUnit.MILLISECONDS) }
        }
    }

    private fun detachPreviewSurfaceInternal() {
        if (previewSurface == null) return
        previewSurface = null
        previewWidthAttached = 0
        previewHeightAttached = 0
        stopAndCloseCaptureSession()
        val isStreaming = _telemetry.value.pipelineState == StreamPipelineState.STREAMING ||
            _telemetry.value.pipelineState == StreamPipelineState.STARTING
        if (isStreaming && cameraDevice != null) {
            recreateCaptureSession()
        } else {
            closeCameraOnly()
        }
    }

    fun ensurePreviewStarted() {
        cameraHandler.post {
            if (cameraDevice == null && previewSurface != null) {
                openCameraAndConfigure()
            }
        }
    }

    fun startStreaming(settings: StreamSettings) {
        currentSettings = settings
        _telemetry.value = _telemetry.value.copy(
            pipelineState = StreamPipelineState.STARTING,
            errorMessage = null
        )
        cameraHandler.post {
            if (!hasCameraPermission()) {
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.ERROR,
                    errorMessage = "Permiso de cámara no concedido."
                )
                return@post
            }
            restartPipelineInternal(settings, startStream = true, reopenCamera = cameraDevice == null)
        }
    }

    fun stopStreaming() {
        cameraHandler.post {
            isRestartingPipeline = false
            udpBeacon.stop()
            socketServer.stop()
            stopAndCloseCaptureSession()
            releaseEncoders()
            if (previewSurface != null && cameraDevice != null) {
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.PREVIEW_ONLY,
                    actualFps = 0,
                    encodeLatencyMs = 0f,
                    errorMessage = null
                )
                recreateCaptureSession()
            } else {
                closeCameraOnly()
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.IDLE,
                    actualFps = 0,
                    encodeLatencyMs = 0f,
                    errorMessage = null
                )
            }
            syncDiscoverySnapshot()
        }
    }

    fun toggleTorch() {
        setTorchMode("toggle")
    }

    fun setTorchMode(state: String?): Boolean {
        val supported = currentDescriptor?.flashSupported == true
        if (!supported) {
            torchEnabled = false
            return false
        }
        val next = when (state?.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> !torchEnabled
        }
        torchEnabled = next
        _telemetry.value = _telemetry.value.copy(torchEnabled = next)
        syncDiscoverySnapshot()
        cameraHandler.post {
            applyRepeatingRequest()
        }
        return next
    }

    fun toggleFocusLock() {
        cameraHandler.post {
            focusLocked = !focusLocked
            _telemetry.value = _telemetry.value.copy(focusLocked = focusLocked)
            applyRepeatingRequest()
        }
    }

    fun setExposureEv(ev: Int) {
        cameraHandler.post {
            val range = currentDescriptor?.aeRange ?: Range(0, 0)
            val clamped = ev.coerceIn(range.lower, range.upper)
            if (clamped == exposureEv) return@post
            exposureEv = clamped
            _telemetry.value = _telemetry.value.copy(exposureEv = exposureEv)
            applyRepeatingRequest()
        }
    }

    fun setZoomRatio(ratio: Float) {
        cameraHandler.post {
            val minZ = currentDescriptor?.minZoom ?: 1f
            val maxZ = (currentDescriptor?.maxZoom ?: 4f).coerceAtLeast(minZ).coerceAtMost(8f)
            val clamped = ratio.coerceIn(minZ, maxZ)
            if (abs(clamped - zoomRatio) < 0.01f) return@post
            zoomRatio = clamped
            _telemetry.value = _telemetry.value.copy(zoomRatio = zoomRatio)
            applyRepeatingRequest()
        }
    }

    fun triggerTapToFocus(normalizedX: Float, normalizedY: Float) {
        cameraHandler.post {
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val descriptor = currentDescriptor ?: return@post
            if (!descriptor.supportsAfRegions) return@post

            val activeRect = descriptor.activeArraySize
            if (activeRect.width() <= 4 || activeRect.height() <= 4) return@post

            val nx = normalizedX.coerceIn(0f, 1f)
            val ny = normalizedY.coerceIn(0f, 1f)

            val sensorX: Int
            val sensorY: Int
            when (descriptor.sensorOrientation) {
                90 -> {
                    sensorX = (ny * activeRect.width()).roundToInt()
                    sensorY = ((1f - nx) * activeRect.height()).roundToInt()
                }
                270 -> {
                    sensorX = ((1f - ny) * activeRect.width()).roundToInt()
                    sensorY = (nx * activeRect.height()).roundToInt()
                }
                180 -> {
                    sensorX = ((1f - nx) * activeRect.width()).roundToInt()
                    sensorY = ((1f - ny) * activeRect.height()).roundToInt()
                }
                else -> {
                    sensorX = (nx * activeRect.width()).roundToInt()
                    sensorY = (ny * activeRect.height()).roundToInt()
                }
            }

            val halfBox = (minOf(activeRect.width(), activeRect.height()) * 0.08f).roundToInt().coerceAtLeast(1)
            val maxRight = (activeRect.right - 2).coerceAtLeast(activeRect.left)
            val maxBottom = (activeRect.bottom - 2).coerceAtLeast(activeRect.top)
            val left = (sensorX - halfBox).coerceIn(activeRect.left, maxRight)
            val top = (sensorY - halfBox).coerceIn(activeRect.top, maxBottom)
            val right = (sensorX + halfBox).coerceIn(left + 1, activeRect.right)
            val bottom = (sensorY + halfBox).coerceIn(top + 1, activeRect.bottom)
            val meteringRect = MeteringRectangle(
                Rect(left, top, right, bottom),
                MeteringRectangle.METERING_WEIGHT_MAX - 1
            )

            focusLocked = false
            _telemetry.value = _telemetry.value.copy(focusLocked = false)

            runCatching {
                val triggerBuilder = buildCaptureRequest(device) ?: return@runCatching
                triggerBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                triggerBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                triggerBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                triggerBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(triggerBuilder.build(), null, cameraHandler)

                val repeatBuilder = buildCaptureRequest(device) ?: return@runCatching
                repeatBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                repeatBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                session.setRepeatingRequest(repeatBuilder.build(), null, cameraHandler)
            }
        }
    }

    fun requestKeyFrame() {
        encoderHandler.post {
            synchronized(encoderLock) {
                if (!isEncoderActive) return@post
                runCatching {
                    val bundle = Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    }
                    mediaCodec?.setParameters(bundle)
                }
            }
        }
    }

    fun shutdown() {
        cameraHandler.post {
            udpBeacon.shutdown()
            socketServer.stop()
            stopAndCloseCaptureSession()
            releaseEncoders()
            closeCameraOnly()
            cameraThread.quitSafely()
            encoderThread.quitSafely()
        }
    }

    private fun syncDiscoverySnapshot() {
        val tel = _telemetry.value
        val settings = currentSettings
        val live = tel.pipelineState == StreamPipelineState.STREAMING && !isRestartingPipeline
        udpBeacon.updateSnapshot(
            DiscoverySnapshot(
                live = live,
                restarting = isRestartingPipeline,
                pipelineState = tel.pipelineState.name,
                port = settings.port,
                protocol = settings.protocol,
                lens = settings.lens,
                resolution = settings.resolution,
                width = resolvedStreamSize.width,
                height = resolvedStreamSize.height,
                targetFps = settings.targetFps,
                actualFps = tel.actualFps,
                bitrateMbps = settings.bitrateMbps,
                sensorOrientation = currentDescriptor?.sensorOrientation ?: 90,
                torchEnabled = torchEnabled,
                flashSupported = tel.flashSupported,
                connectedClients = tel.connectedClients,
                hevcSupported = tel.hevcSupported,
                usbIps = currentUsbIps
            )
        )
    }

    private fun restartPipeline(settings: StreamSettings, keepStreaming: Boolean, reopenCamera: Boolean) {
        cameraHandler.post {
            restartPipelineInternal(settings, startStream = keepStreaming, reopenCamera = reopenCamera)
        }
    }

    private fun restartPipelineInternal(settings: StreamSettings, startStream: Boolean, reopenCamera: Boolean) {
        // Keep HTTP control server on port+1 alive during restart; only mark restarting=true
        if (startStream) {
            isRestartingPipeline = true
            syncDiscoverySnapshot()
        } else {
            isRestartingPipeline = false
            udpBeacon.stop()
        }
        socketServer.stop()
        stopAndCloseCaptureSession()
        releaseEncoders()

        if (reopenCamera) {
            closeCameraOnly()
        }

        refreshDescriptorMetadata(settings)
        if (startStream) {
            val encoderReady = prepareEncoder(settings)
            if (!encoderReady) {
                isRestartingPipeline = false
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.ERROR,
                    errorMessage = "No se pudo inicializar el codificador de video hardware."
                )
                syncDiscoverySnapshot()
                return
            }
            socketServer.start(settings.port, settings.protocol)
            syncDiscoverySnapshot()
        }

        if (cameraDevice != null && !reopenCamera && !isClosingCamera) {
            recreateCaptureSession()
        } else {
            openCameraAndConfigure()
        }
    }

    private fun refreshDescriptorMetadata(settings: StreamSettings) {
        val descriptor = runCatching {
            CameraCapabilityInspector.inspectCamera(context, settings.lens)
        }.getOrNull()
        currentDescriptor = descriptor
        if (descriptor != null) {
            if (!hasAttemptedFallbackSize) {
                resolvedStreamSize = descriptor.pickBestSize(settings.resolution)
            }
            _telemetry.value = _telemetry.value.copy(
                activeResolutionLabel = "${resolvedStreamSize.width}×${resolvedStreamSize.height}",
                previewWidth = resolvedStreamSize.width,
                previewHeight = resolvedStreamSize.height,
                flashSupported = descriptor.flashSupported,
                exposureMinEv = descriptor.aeRange.lower,
                exposureMaxEv = descriptor.aeRange.upper,
                exposureEv = exposureEv.coerceIn(descriptor.aeRange.lower, descriptor.aeRange.upper),
                maxZoom = descriptor.maxZoom.coerceAtLeast(1f).coerceAtMost(8f),
                supportedFpsPresets = descriptor.supportedFpsPresets
            )
            updatePreviewAspectRatio()
        }
    }

    private fun updatePreviewAspectRatio() {
        val descriptor = currentDescriptor ?: return
        val size = resolvedStreamSize
        val sensorPortrait = descriptor.sensorOrientation == 90 || descriptor.sensorOrientation == 270
        val displayLandscape = displayRotationDegrees == 90 || displayRotationDegrees == 270
        val isPreviewPortrait = if (displayLandscape) !sensorPortrait else sensorPortrait
        val ratio = if (isPreviewPortrait) {
            size.height.toFloat() / size.width.toFloat()
        } else {
            size.width.toFloat() / size.height.toFloat()
        }
        _telemetry.value = _telemetry.value.copy(previewAspectRatio = ratio)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openCameraAndConfigure() {
        if (!hasCameraPermission() || isOpeningCamera) return
        if (isClosingCamera) {
            pendingReopenOnClose = true
            return
        }
        if (currentDescriptor == null) {
            refreshDescriptorMetadata(currentSettings)
        }
        val descriptor = currentDescriptor ?: run {
            _telemetry.value = _telemetry.value.copy(
                pipelineState = StreamPipelineState.ERROR,
                errorMessage = "No se encontró ninguna cámara disponible en el dispositivo."
            )
            return
        }

        isOpeningCamera = true
        pendingReopenOnClose = false
        try {
            cameraManager.openCamera(
                descriptor.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        isOpeningCamera = false
                        if (camera.id != currentDescriptor?.cameraId || isClosingCamera) {
                            runCatching { camera.close() }
                            return
                        }
                        cameraDevice = camera
                        recreateCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        isOpeningCamera = false
                        runCatching { camera.close() }
                        if (cameraDevice == camera) {
                            cameraDevice = null
                            captureSession = null
                        }
                    }

                    override fun onClosed(camera: CameraDevice) {
                        isClosingCamera = false
                        if (cameraDevice == camera) {
                            cameraDevice = null
                            captureSession = null
                        }
                        if (pendingReopenOnClose) {
                            pendingReopenOnClose = false
                            openCameraAndConfigure()
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        isOpeningCamera = false
                        isClosingCamera = false
                        runCatching { camera.close() }
                        if (cameraDevice == camera) {
                            cameraDevice = null
                            captureSession = null
                        }
                        _telemetry.value = _telemetry.value.copy(
                            pipelineState = StreamPipelineState.ERROR,
                            errorMessage = "Error de cámara (código $error)."
                        )
                    }
                },
                cameraHandler
            )
        } catch (se: SecurityException) {
            isOpeningCamera = false
            _telemetry.value = _telemetry.value.copy(
                pipelineState = StreamPipelineState.ERROR,
                errorMessage = "Permiso de cámara denegado."
            )
        } catch (e: Exception) {
            isOpeningCamera = false
            _telemetry.value = _telemetry.value.copy(
                pipelineState = StreamPipelineState.ERROR,
                errorMessage = e.localizedMessage ?: "No se pudo abrir la cámara."
            )
        }
    }

    private fun recreateCaptureSession() {
        val device = cameraDevice ?: return
        stopAndCloseCaptureSession()

        val outputs = mutableListOf<Surface>()
        previewSurface?.takeIf { it.isValid }?.let { outputs.add(it) }
        encoderInputSurface?.takeIf { it.isValid }?.let { outputs.add(it) }
        mjpegImageReader?.surface?.takeIf { it.isValid }?.let { outputs.add(it) }

        if (outputs.isEmpty()) return

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null || isClosingCamera) {
                    runCatching { session.close() }
                    return
                }
                captureSession = session
                val hasEncoder = encoderInputSurface != null || mjpegImageReader != null
                isRestartingPipeline = false
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = if (hasEncoder) StreamPipelineState.STREAMING else StreamPipelineState.PREVIEW_ONLY,
                    errorMessage = null
                )
                syncDiscoverySnapshot()
                applyRepeatingRequest()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                runCatching { session.close() }
                val descriptor = currentDescriptor
                if (!hasAttemptedFallbackSize && descriptor != null) {
                    val fallback = descriptor.pickFallbackSize()
                    if (fallback != resolvedStreamSize) {
                        hasAttemptedFallbackSize = true
                        resolvedStreamSize = fallback
                        _telemetry.value = _telemetry.value.copy(
                            activeResolutionLabel = "${fallback.width}×${fallback.height}",
                            previewWidth = fallback.width,
                            previewHeight = fallback.height
                        )
                        updatePreviewAspectRatio()
                        val wasStreaming = encoderInputSurface != null || mjpegImageReader != null
                        if (wasStreaming) {
                            releaseEncoders()
                            prepareEncoder(currentSettings)
                        }
                        if (previewSurface == null) {
                            recreateCaptureSession()
                        }
                        return
                    }
                }
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.ERROR,
                    errorMessage = "No se pudo configurar la sesión de captura de cámara."
                )
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val configs = outputs.map { OutputConfiguration(it) }
                val executor = Executor { command -> cameraHandler.post(command) }
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        configs,
                        executor,
                        callback
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(outputs, callback, cameraHandler)
            }
        } catch (e: Exception) {
            _telemetry.value = _telemetry.value.copy(
                pipelineState = StreamPipelineState.ERROR,
                errorMessage = e.localizedMessage ?: "Fallo al crear sesión de cámara."
            )
        }
    }

    private fun buildCaptureRequest(device: CameraDevice): CaptureRequest.Builder? {
        val isStreaming = encoderInputSurface != null || mjpegImageReader != null
        val template = if (isStreaming) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)

        var targetCount = 0
        previewSurface?.takeIf { it.isValid }?.let {
            builder.addTarget(it)
            targetCount++
        }
        encoderInputSurface?.takeIf { it.isValid }?.let {
            builder.addTarget(it)
            targetCount++
        }
        mjpegImageReader?.surface?.takeIf { it.isValid }?.let {
            builder.addTarget(it)
            targetCount++
        }
        if (targetCount == 0) return null

        val descriptor = currentDescriptor
        if (descriptor != null) {
            descriptor.pickBestFpsRange(currentSettings.targetFps)?.let { fpsRange ->
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            if (descriptor.aeRange.lower < descriptor.aeRange.upper) {
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    exposureEv.coerceIn(descriptor.aeRange.lower, descriptor.aeRange.upper)
                )
            }

            if (descriptor.maxZoom > 1.0f) {
                val safeZoom = zoomRatio.coerceIn(descriptor.minZoom, descriptor.maxZoom)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeZoom)
                } else if (safeZoom > 1.0f) {
                    val active = descriptor.activeArraySize
                    if (active.width() > 4 && active.height() > 4) {
                        val cropW = (active.width() / safeZoom).roundToInt().coerceAtLeast(2)
                        val cropH = (active.height() / safeZoom).roundToInt().coerceAtLeast(2)
                        val left = ((active.width() - cropW) / 2).coerceAtLeast(0)
                        val top = ((active.height() - cropH) / 2).coerceAtLeast(0)
                        builder.set(
                            CaptureRequest.SCALER_CROP_REGION,
                            Rect(left, top, left + cropW, top + cropH)
                        )
                    }
                }
            }
        }

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val afMode = when {
            focusLocked -> CaptureRequest.CONTROL_AF_MODE_OFF
            descriptor?.supportsContinuousVideoAf == true -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> CaptureRequest.CONTROL_AF_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchEnabled && currentDescriptor?.flashSupported == true) {
                CaptureRequest.FLASH_MODE_TORCH
            } else {
                CaptureRequest.FLASH_MODE_OFF
            }
        )
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (currentSettings.stabilizationEnabled) {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
        )
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        return builder
    }

    private fun applyRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        runCatching {
            val builder = buildCaptureRequest(device) ?: return
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        }
    }

    private fun prepareEncoder(settings: StreamSettings): Boolean {
        fpsFrameCounter = 0
        fpsWindowStartNs = System.nanoTime()
        smoothedLatencyMs = 0f

        return if (settings.protocol == StreamProtocol.MJPEG_HTTP) {
            prepareMjpegReader(settings)
        } else {
            prepareHardwareVideoCodec(settings)
        }
    }

    private fun buildVideoFormat(
        mime: String,
        settings: StreamSettings,
        includeVendorLowLatency: Boolean
    ): MediaFormat {
        return MediaFormat.createVideoFormat(
            mime,
            resolvedStreamSize.width,
            resolvedStreamSize.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.targetFps)
            setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                settings.keyframeIntervalSec.roundToInt().coerceAtLeast(1)
            )
            if (includeVendorLowLatency) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (settings.lowLatencyHardware) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setInteger(MediaFormat.KEY_LATENCY, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
            }
        }
    }

    private fun registerCodecCallback(codec: MediaCodec) {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                synchronized(encoderLock) {
                    if (!isEncoderActive || mediaCodec !== mc) return
                    try {
                        val buffer = mc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)

                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isConfig) {
                                socketServer.publishCodecConfig(bytes)
                            } else {
                                socketServer.publishVideoPacket(bytes, isKeyFrame)
                                recordFrameMetrics(info.presentationTimeUs)
                            }
                        }
                    } catch (_: Throwable) {
                        // Ignore transient codec teardown states safely
                    } finally {
                        runCatching { mc.releaseOutputBuffer(index, false) }
                    }
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                synchronized(encoderLock) {
                    if (!isEncoderActive || mediaCodec !== mc) return
                }
                _telemetry.value = _telemetry.value.copy(
                    pipelineState = StreamPipelineState.ERROR,
                    errorMessage = "Error del encoder: ${e.localizedMessage ?: e.errorCode}"
                )
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) = Unit
        }, encoderHandler)
    }

    private fun prepareHardwareVideoCodec(settings: StreamSettings): Boolean {
        val useHevc = settings.protocol == StreamProtocol.HEVC_TCP &&
            CameraCapabilityInspector.isHevcEncoderSupported()
        val mime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        return try {
            val codec = MediaCodec.createEncoderByType(mime)
            registerCodecCallback(codec)

            val configured = runCatching {
                codec.configure(
                    buildVideoFormat(mime, settings, includeVendorLowLatency = true),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            }.isSuccess || runCatching {
                codec.reset()
                registerCodecCallback(codec)
                codec.configure(
                    buildVideoFormat(mime, settings, includeVendorLowLatency = false),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            }.isSuccess

            if (!configured) {
                runCatching { codec.release() }
                return false
            }

            val surface = codec.createInputSurface()
            synchronized(encoderLock) {
                mediaCodec = codec
                encoderInputSurface = surface
                isEncoderActive = true
            }
            codec.start()
            true
        } catch (_: Throwable) {
            releaseEncoders()
            false
        }
    }

    private fun prepareMjpegReader(settings: StreamSettings): Boolean {
        return try {
            val reader = ImageReader.newInstance(
                resolvedStreamSize.width,
                resolvedStreamSize.height,
                ImageFormat.YUV_420_888,
                2
            )
            reader.setOnImageAvailableListener({ r ->
                synchronized(encoderLock) {
                    if (!isEncoderActive || mjpegImageReader !== r) {
                        runCatching { r.acquireLatestImage()?.close() }
                        return@setOnImageAvailableListener
                    }
                    var image: Image? = null
                    try {
                        image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val nv21 = yuv420ToNv21(image)
                        jpegScratchOut.reset()
                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                        yuvImage.compressToJpeg(
                            Rect(0, 0, image.width, image.height),
                            settings.jpegQuality,
                            jpegScratchOut
                        )
                        val jpegBytes = jpegScratchOut.toByteArray()
                        socketServer.publishVideoPacket(jpegBytes, isKeyFrame = true)
                        recordFrameMetrics(image.timestamp / 1000L)
                    } catch (_: Throwable) {
                        // Ignore transient teardown / closed-image races
                    } finally {
                        runCatching { image?.close() }
                    }
                }
            }, encoderHandler)
            synchronized(encoderLock) {
                mjpegImageReader = reader
                isEncoderActive = true
            }
            true
        } catch (_: Throwable) {
            releaseEncoders()
            false
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2
        val needed = ySize + uvSize
        val out = nv21Scratch?.takeIf { it.size == needed } ?: ByteArray(needed).also {
            nv21Scratch = it
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0
        if (yRowStride == width && yPixelStride == 1) {
            yBuf.get(out, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                val rowOffset = row * yRowStride
                for (col in 0 until width) {
                    out[pos++] = yBuf.get(rowOffset + col * yPixelStride)
                }
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            val rowOffset = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val index = rowOffset + col * uvPixelStride
                out[pos++] = vBuf.get(index)
                out[pos++] = uBuf.get(index)
            }
        }
        return out
    }

    private fun recordFrameMetrics(presentationTimeUs: Long) {
        fpsFrameCounter++
        val nowNs = System.nanoTime()

        val nowMonoUs = nowNs / 1000L
        val nowBootUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val deltaMonoMs = (nowMonoUs - presentationTimeUs) / 1000f
        val deltaBootMs = (nowBootUs - presentationTimeUs) / 1000f
        val sampleLatency = listOf(deltaMonoMs, deltaBootMs)
            .filter { it in 1f..500f }
            .minOrNull()
        if (sampleLatency != null) {
            smoothedLatencyMs = if (smoothedLatencyMs == 0f) {
                sampleLatency
            } else {
                smoothedLatencyMs * 0.8f + sampleLatency * 0.2f
            }
        }

        val elapsedNs = nowNs - fpsWindowStartNs
        if (elapsedNs >= 500_000_000L) {
            val fps = (fpsFrameCounter * 1_000_000_000.0 / elapsedNs).roundToInt()
            fpsFrameCounter = 0
            fpsWindowStartNs = nowNs
            _telemetry.value = _telemetry.value.copy(
                actualFps = fps,
                encodeLatencyMs = (smoothedLatencyMs * 10f).roundToInt() / 10f
            )
            syncDiscoverySnapshot()
        }
    }

    private fun releaseEncoders() {
        synchronized(encoderLock) {
            isEncoderActive = false
            runCatching { mediaCodec?.stop() }
            runCatching { mediaCodec?.release() }
            mediaCodec = null
            runCatching { encoderInputSurface?.release() }
            encoderInputSurface = null
            runCatching { mjpegImageReader?.close() }
            mjpegImageReader = null
        }
    }

    private fun stopAndCloseCaptureSession() {
        val session = captureSession ?: return
        captureSession = null
        runCatching { session.stopRepeating() }
        runCatching { session.abortCaptures() }
        runCatching { session.close() }
    }

    private fun closeCameraOnly() {
        isOpeningCamera = false
        stopAndCloseCaptureSession()
        val dev = cameraDevice
        if (dev != null) {
            isClosingCamera = true
            runCatching { dev.close() }
            cameraDevice = null
        } else {
            isClosingCamera = false
        }
    }
}
