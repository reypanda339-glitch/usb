package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.CameraLens
import com.example.data.SettingsRepository
import com.example.data.StreamProtocol
import com.example.data.VideoResolution
import com.example.service.CameraStreamService
import com.example.stream.StreamEngine
import com.example.stream.StreamPipelineState
import com.example.stream.UsbDiagnosticsMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CamLinkApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var streamEngine: StreamEngine
        private set

    lateinit var usbDiagnosticsMonitor: UsbDiagnosticsMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsRepository = SettingsRepository(this)
        streamEngine = StreamEngine(
            context = this,
            onRequestProtocolSwitch = { proto ->
                appScope.launch {
                    settingsRepository.updateProtocol(proto)
                }
            },
            onRemoteStartRequested = {
                appScope.launch {
                    val current = settingsRepository.settingsFlow.first()
                    val state = streamEngine.telemetry.value.pipelineState
                    if (state != StreamPipelineState.STREAMING && state != StreamPipelineState.STARTING) {
                        val startedService = if (current.backgroundServiceEnabled) {
                            CameraStreamService.start(this@CamLinkApp)
                        } else {
                            false
                        }
                        if (!startedService) {
                            streamEngine.startStreaming(current)
                        }
                    }
                }
            },
            onRemoteStopRequested = {
                appScope.launch {
                    val current = settingsRepository.settingsFlow.first()
                    if (current.backgroundServiceEnabled) {
                        CameraStreamService.stop(this@CamLinkApp)
                    }
                    streamEngine.stopStreaming()
                }
            },
            onRemoteLensSwitch = { lens: CameraLens ->
                appScope.launch {
                    settingsRepository.updateLens(lens)
                }
            },
            onRemoteResolutionSwitch = { resolution: VideoResolution ->
                appScope.launch {
                    settingsRepository.updateResolution(resolution)
                }
            },
            onRemoteFpsSwitch = { fps: Int ->
                appScope.launch {
                    settingsRepository.updateTargetFps(fps)
                }
            }
        )
        usbDiagnosticsMonitor = UsbDiagnosticsMonitor(this)
        usbDiagnosticsMonitor.start()
        appScope.launch {
            usbDiagnosticsMonitor.state.collect { usb ->
                streamEngine.updateUsbIps(usb.usbInterfaceIps)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STREAM_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val STREAM_NOTIFICATION_CHANNEL_ID = "camlink_usb_stream_channel"
    }
}
