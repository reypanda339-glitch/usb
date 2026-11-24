package com.example.stream

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import com.example.data.CameraLens
import com.example.data.VideoResolution
import kotlin.math.abs

data class CameraDescriptor(
    val cameraId: String,
    val lens: CameraLens,
    val sensorOrientation: Int,
    val activeArraySize: Rect,
    val flashSupported: Boolean,
    val aeRange: Range<Int>,
    val maxZoom: Float,
    val minZoom: Float = 1.0f,
    val supportsAfRegions: Boolean = true,
    val supportsContinuousVideoAf: Boolean = true,
    val availableFpsRanges: List<Range<Int>>,
    val supportedSizes: List<Size>
) {
    val supportedFpsPresets: List<Int>
        get() {
            val presets = mutableListOf<Int>()
            if (availableFpsRanges.any { it.upper >= 60 }) presets.add(60)
            if (availableFpsRanges.any { it.upper >= 30 }) presets.add(30)
            if (availableFpsRanges.any { it.upper >= 24 }) presets.add(24)
            return if (presets.isEmpty()) listOf(30) else presets
        }

    fun pickBestSize(target: VideoResolution): Size {
        if (supportedSizes.isEmpty()) return Size(target.width, target.height)
        val exact = supportedSizes.firstOrNull { it.width == target.width && it.height == target.height }
        if (exact != null) return exact

        val targetArea = target.width * target.height
        val landscapeSizes = supportedSizes.filter { it.width >= it.height && it.width <= 1920 && it.height <= 1080 }
        val pool = landscapeSizes.ifEmpty { supportedSizes }
        return pool.minByOrNull { abs(it.width * it.height - targetArea) }
            ?: Size(target.width, target.height)
    }

    fun pickFallbackSize(): Size {
        val vga = supportedSizes.firstOrNull { it.width == 640 && it.height == 480 }
        if (vga != null) return vga
        return supportedSizes
            .filter { it.width in 320..1280 && it.height in 240..720 }
            .minByOrNull { abs(it.width * it.height - (640 * 480)) }
            ?: supportedSizes.lastOrNull()
            ?: Size(640, 480)
    }

    fun pickBestFpsRange(targetFps: Int): Range<Int>? {
        if (availableFpsRanges.isEmpty()) return null
        val exactFixed = availableFpsRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
        if (exactFixed != null) return exactFixed

        val matchingUpper = availableFpsRanges
            .filter { it.upper == targetFps }
            .maxByOrNull { it.lower }
        if (matchingUpper != null) return matchingUpper

        val closestFixed = availableFpsRanges
            .filter { it.lower == it.upper && it.upper <= targetFps }
            .maxByOrNull { it.upper }
        if (closestFixed != null) return closestFixed

        return availableFpsRanges.minByOrNull { abs(it.upper - targetFps) }
    }
}

object CameraCapabilityInspector {

    fun inspectCamera(context: Context, lens: CameraLens): CameraDescriptor? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (lens == CameraLens.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: manager.cameraIdList.firstOrNull() ?: return null

        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val surfaceSizes = map?.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty()
        val codecSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()

        val commonSizes = surfaceSizes.filter { s ->
            (codecSizes.isEmpty() || codecSizes.any { it.width == s.width && it.height == s.height }) &&
                (yuvSizes.isEmpty() || yuvSizes.any { it.width == s.width && it.height == s.height })
        }.ifEmpty {
            surfaceSizes.filter { s ->
                yuvSizes.isEmpty() || yuvSizes.any { it.width == s.width && it.height == s.height }
            }
        }.ifEmpty {
            surfaceSizes.ifEmpty { yuvSizes }
        }

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val legacyMaxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.coerceAtLeast(1f) ?: 1f

        var minZoom = 1.0f
        var maxZoom = legacyMaxZoom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                minZoom = zoomRange.lower.coerceAtLeast(1.0f)
                maxZoom = zoomRange.upper.coerceAtLeast(minZoom)
            }
        }

        val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        val supportsContinuousVideoAf = afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

        val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 1920, 1080)

        return CameraDescriptor(
            cameraId = cameraId,
            lens = lens,
            sensorOrientation = sensorOrientation,
            activeArraySize = activeArray,
            flashSupported = flash,
            aeRange = aeRange,
            maxZoom = maxZoom,
            minZoom = minZoom,
            supportsAfRegions = maxAfRegions > 0,
            supportsContinuousVideoAf = supportsContinuousVideoAf,
            availableFpsRanges = fpsRanges,
            supportedSizes = commonSizes
        )
    }

    fun isHevcEncoderSupported(): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
