package com.example.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CamLinkApp
import com.example.data.CameraLens
import com.example.data.StreamProtocol
import com.example.data.StreamSettings
import com.example.data.VideoResolution
import com.example.service.CameraStreamService
import com.example.stream.StreamPipelineState
import com.example.stream.StreamTelemetry
import com.example.stream.UsbLinkState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CamLinkApp
    private val settingsRepo = app.settingsRepository
    private val streamEngine = app.streamEngine
    private val usbMonitor = app.usbDiagnosticsMonitor

    val settings: StateFlow<StreamSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamSettings())

    val telemetry: StateFlow<StreamTelemetry> = streamEngine.telemetry
    val usbState: StateFlow<UsbLinkState> = usbMonitor.state

    private var wasUsbTransportReady = false

    init {
        viewModelScope.launch {
            settings.collect { current ->
                streamEngine.updateSettings(current)
            }
        }
        viewModelScope.launch {
            usbState.collect { link ->
                val transportReady = link.isUsbConnected || link.isAdbActive || link.isRndisActive
                val justReady = !wasUsbTransportReady && transportReady
                wasUsbTransportReady = transportReady
                if (justReady && settings.value.autoStreamOnUsb) {
                    tryAutoStartStream()
                }
            }
        }
    }

    private fun tryAutoStartStream() {
        val hasCam = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val isIdle = telemetry.value.pipelineState == StreamPipelineState.IDLE ||
            telemetry.value.pipelineState == StreamPipelineState.PREVIEW_ONLY
        if (hasCam && isIdle) {
            startStreamingNow()
        }
    }

    fun attachPreviewSurface(holder: SurfaceHolder, width: Int, height: Int) {
        streamEngine.attachPreviewSurface(holder, width, height)
    }

    fun detachPreviewSurface() {
        streamEngine.detachPreviewSurface()
    }

    fun onCameraPermissionGranted() {
        streamEngine.ensurePreviewStarted()
    }

    fun updateDisplayRotation(rotationDegrees: Int) {
        streamEngine.updateDisplayRotation(rotationDegrees)
    }

    private var lastStreamToggleMs = 0L

    fun toggleStream() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStreamToggleMs < 350L) return
        lastStreamToggleMs = now

        val currentState = telemetry.value.pipelineState
        val isRunning = currentState == StreamPipelineState.STREAMING ||
            currentState == StreamPipelineState.STARTING
        val currentSettings = settings.value

        if (isRunning) {
            if (currentSettings.backgroundServiceEnabled) {
                CameraStreamService.stop(app)
            }
            streamEngine.stopStreaming()
        } else {
            startStreamingNow()
        }
    }

    private fun startStreamingNow() {
        val currentSettings = settings.value
        val startedService = if (currentSettings.backgroundServiceEnabled) {
            CameraStreamService.start(app)
        } else {
            false
        }
        if (!startedService) {
            streamEngine.startStreaming(currentSettings)
        }
    }

    private var lastFlipCameraMs = 0L

    fun flipCamera() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFlipCameraMs < 400L) return
        lastFlipCameraMs = now
        val next = if (settings.value.lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        viewModelScope.launch { settingsRepo.updateLens(next) }
    }

    fun toggleTorch() = streamEngine.toggleTorch()

    fun toggleFocusLock() = streamEngine.toggleFocusLock()

    fun setExposureEv(ev: Int) = streamEngine.setExposureEv(ev)

    fun setZoomRatio(ratio: Float) = streamEngine.setZoomRatio(ratio)

    fun triggerTapToFocus(nx: Float, ny: Float) = streamEngine.triggerTapToFocus(nx, ny)

    fun requestKeyFrame() = streamEngine.requestKeyFrame()

    fun refreshUsbDiagnostics() = usbMonitor.refresh()

    fun toggleGrid() {
        viewModelScope.launch { settingsRepo.updateShowGrid(!settings.value.showGrid) }
    }

    fun updateProtocol(protocol: StreamProtocol) {
        viewModelScope.launch { settingsRepo.updateProtocol(protocol) }
    }

    fun updateResolution(resolution: VideoResolution) {
        viewModelScope.launch { settingsRepo.updateResolution(resolution) }
    }

    fun updateTargetFps(fps: Int) {
        viewModelScope.launch { settingsRepo.updateTargetFps(fps) }
    }

    fun updateBitrateMbps(mbps: Int) {
        viewModelScope.launch { settingsRepo.updateBitrateMbps(mbps) }
    }

    fun updateJpegQuality(quality: Int) {
        viewModelScope.launch { settingsRepo.updateJpegQuality(quality) }
    }

    fun updateKeyframeInterval(seconds: Float) {
        viewModelScope.launch { settingsRepo.updateKeyframeInterval(seconds) }
    }

    fun updatePort(port: Int) {
        viewModelScope.launch { settingsRepo.updatePort(port) }
    }

    fun updateLowLatencyHardware(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateLowLatencyHardware(enabled) }
    }

    fun updateBackgroundService(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateBackgroundService(enabled) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateKeepScreenOn(enabled) }
    }

    fun updateStabilization(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateStabilization(enabled) }
    }

    fun updateMirrorFront(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateMirrorFront(enabled) }
    }

    fun updateAutoStreamOnUsb(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateAutoStreamOnUsb(enabled) }
    }
}
