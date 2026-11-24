package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.streamDataStore: DataStore<Preferences> by preferencesDataStore(name = "camlink_settings")

enum class StreamProtocol(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    H264_TCP(
        title = "H.264 Hardware (TCP)",
        subtitle = "Latencia mínima · Zero-Copy Surface",
        badge = "ULTRA BAJA"
    ),
    HEVC_TCP(
        title = "HEVC / H.265 (TCP)",
        subtitle = "Máxima compresión con hardware encoder",
        badge = "ALTA EFICIENCIA"
    ),
    MJPEG_HTTP(
        title = "MJPEG sobre HTTP",
        subtitle = "Compatible directo con OBS, VLC y navegador",
        badge = "PLUG & PLAY"
    )
}

enum class VideoResolution(
    val width: Int,
    val height: Int,
    val label: String,
    val shortLabel: String
) {
    FHD_1080P(1920, 1080, "1920 × 1080 · Full HD", "1080p"),
    HD_720P(1280, 720, "1280 × 720 · HD Fluido", "720p"),
    SD_480P(854, 480, "854 × 480 · Baja Latencia", "480p"),
    LOW_360P(640, 360, "640 × 360 · Instantáneo", "360p")
}

enum class CameraLens(val label: String) {
    BACK("Trasera"),
    FRONT("Frontal")
}

enum class PcClientTool(val label: String) {
    FFPLAY("ffplay"),
    OBS_STUDIO("OBS Studio"),
    VLC("VLC"),
    PYTHON_OPENCV("Python / OpenCV")
}

data class StreamSettings(
    val protocol: StreamProtocol = StreamProtocol.H264_TCP,
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val targetFps: Int = 60,
    val bitrateMbps: Int = 8,
    val jpegQuality: Int = 82,
    val keyframeIntervalSec: Float = 1.0f,
    val port: Int = 7878,
    val lens: CameraLens = CameraLens.BACK,
    val lowLatencyHardware: Boolean = true,
    val backgroundServiceEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val stabilizationEnabled: Boolean = false,
    val mirrorFrontCamera: Boolean = true,
    val showGrid: Boolean = false,
    val autoStreamOnUsb: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROTOCOL = stringPreferencesKey("protocol")
        val RESOLUTION = stringPreferencesKey("resolution")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val KEYFRAME_INTERVAL = floatPreferencesKey("keyframe_interval")
        val PORT = intPreferencesKey("port")
        val LENS = stringPreferencesKey("lens")
        val LOW_LATENCY_HW = booleanPreferencesKey("low_latency_hw")
        val BACKGROUND_SERVICE = booleanPreferencesKey("background_service")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val STABILIZATION = booleanPreferencesKey("stabilization")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val AUTO_STREAM_ON_USB = booleanPreferencesKey("auto_stream_on_usb")
    }

    val settingsFlow: Flow<StreamSettings> = context.streamDataStore.data.map { prefs ->
        StreamSettings(
            protocol = prefs[Keys.PROTOCOL]?.let { runCatching { StreamProtocol.valueOf(it) }.getOrNull() }
                ?: StreamProtocol.H264_TCP,
            resolution = prefs[Keys.RESOLUTION]?.let { runCatching { VideoResolution.valueOf(it) }.getOrNull() }
                ?: VideoResolution.HD_720P,
            targetFps = prefs[Keys.TARGET_FPS] ?: 60,
            bitrateMbps = (prefs[Keys.BITRATE_MBPS] ?: 8).coerceIn(2, 25),
            jpegQuality = (prefs[Keys.JPEG_QUALITY] ?: 82).coerceIn(50, 95),
            keyframeIntervalSec = prefs[Keys.KEYFRAME_INTERVAL] ?: 1.0f,
            port = (prefs[Keys.PORT] ?: 7878).coerceIn(1024, 65535),
            lens = prefs[Keys.LENS]?.let { runCatching { CameraLens.valueOf(it) }.getOrNull() }
                ?: CameraLens.BACK,
            lowLatencyHardware = prefs[Keys.LOW_LATENCY_HW] ?: true,
            backgroundServiceEnabled = prefs[Keys.BACKGROUND_SERVICE] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            stabilizationEnabled = prefs[Keys.STABILIZATION] ?: false,
            mirrorFrontCamera = prefs[Keys.MIRROR_FRONT] ?: true,
            showGrid = prefs[Keys.SHOW_GRID] ?: false,
            autoStreamOnUsb = prefs[Keys.AUTO_STREAM_ON_USB] ?: false
        )
    }

    suspend fun updateProtocol(protocol: StreamProtocol) {
        context.streamDataStore.edit { it[Keys.PROTOCOL] = protocol.name }
    }

    suspend fun updateResolution(resolution: VideoResolution) {
        context.streamDataStore.edit { it[Keys.RESOLUTION] = resolution.name }
    }

    suspend fun updateTargetFps(fps: Int) {
        context.streamDataStore.edit { it[Keys.TARGET_FPS] = fps }
    }

    suspend fun updateBitrateMbps(mbps: Int) {
        context.streamDataStore.edit { it[Keys.BITRATE_MBPS] = mbps.coerceIn(2, 25) }
    }

    suspend fun updateJpegQuality(quality: Int) {
        context.streamDataStore.edit { it[Keys.JPEG_QUALITY] = quality.coerceIn(50, 95) }
    }

    suspend fun updateKeyframeInterval(seconds: Float) {
        context.streamDataStore.edit { it[Keys.KEYFRAME_INTERVAL] = seconds }
    }

    suspend fun updatePort(port: Int) {
        context.streamDataStore.edit { it[Keys.PORT] = port.coerceIn(1024, 65535) }
    }

    suspend fun updateLens(lens: CameraLens) {
        context.streamDataStore.edit { it[Keys.LENS] = lens.name }
    }

    suspend fun updateLowLatencyHardware(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.LOW_LATENCY_HW] = enabled }
    }

    suspend fun updateBackgroundService(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.BACKGROUND_SERVICE] = enabled }
    }

    suspend fun updateKeepScreenOn(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun updateStabilization(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.STABILIZATION] = enabled }
    }

    suspend fun updateMirrorFront(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.MIRROR_FRONT] = enabled }
    }

    suspend fun updateShowGrid(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.SHOW_GRID] = enabled }
    }

    suspend fun updateAutoStreamOnUsb(enabled: Boolean) {
        context.streamDataStore.edit { it[Keys.AUTO_STREAM_ON_USB] = enabled }
    }
}
