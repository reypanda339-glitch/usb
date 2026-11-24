package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.CamLinkApp
import com.example.MainActivity
import com.example.R
import com.example.stream.StreamPipelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CameraStreamService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        runCatching { promoteToForeground("Preparando transmisión USB...") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { promoteToForeground("Iniciando transmisión USB...") }

        if (intent?.action == ACTION_STOP_STREAM) {
            stopStreamAndSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val app = application as CamLinkApp
        serviceScope.launch {
            val settings = app.settingsRepository.settingsFlow.first()
            val state = app.streamEngine.telemetry.value.pipelineState
            if (state != StreamPipelineState.STREAMING && state != StreamPipelineState.STARTING) {
                app.streamEngine.startStreaming(settings)
            }
        }

        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            app.streamEngine.telemetry.collect { telemetry ->
                val detail = if (telemetry.connectedClients > 0) {
                    "${telemetry.activeResolutionLabel} · ${telemetry.actualFps} FPS · ${telemetry.connectedClients} PC conectado"
                } else {
                    "${telemetry.activeResolutionLabel} · Puerto ${telemetry.activePort} · Esperando PC"
                }
                val manager = getSystemService(NotificationManager::class.java)
                runCatching { manager?.notify(NOTIFICATION_ID, buildNotification(detail)) }
            }
        }

        return START_STICKY
    }

    private fun promoteToForeground(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP_STREAM
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CamLinkApp.STREAM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_live))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamLinkUSB::StreamWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopStreamAndSelf() {
        val app = application as CamLinkApp
        app.streamEngine.stopStreaming()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        isServiceRunning = false
        telemetryJob?.cancel()
        serviceScope.cancel()
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_STREAM = "com.example.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.example.action.STOP_STREAM"
        private const val NOTIFICATION_ID = 4102

        @Volatile
        private var isServiceRunning = false

        fun start(context: Context): Boolean {
            return runCatching {
                val intent = Intent(context, CameraStreamService::class.java).apply {
                    action = ACTION_START_STREAM
                }
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, CameraStreamService::class.java).apply {
                    action = ACTION_STOP_STREAM
                }
                context.startService(intent)
            }.onFailure {
                runCatching {
                    context.stopService(Intent(context, CameraStreamService::class.java))
                }
            }
        }
    }
}
