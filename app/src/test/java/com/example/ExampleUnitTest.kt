package com.example

import android.graphics.Rect
import android.util.Range
import android.util.Size
import com.example.data.CameraLens
import com.example.data.VideoResolution
import com.example.stream.CameraDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun `camera descriptor picks exact or fallback supported size`() {
        val descriptor = CameraDescriptor(
            cameraId = "0",
            lens = CameraLens.BACK,
            sensorOrientation = 90,
            activeArraySize = Rect(0, 0, 1920, 1080),
            flashSupported = false,
            aeRange = Range(-6, 6),
            maxZoom = 4f,
            availableFpsRanges = listOf(Range(30, 30), Range(15, 30)),
            supportedSizes = listOf(Size(1280, 720), Size(640, 480))
        )

        assertEquals(Size(1280, 720), descriptor.pickBestSize(VideoResolution.HD_720P))
        assertEquals(Size(640, 480), descriptor.pickFallbackSize())
        assertEquals(Range(30, 30), descriptor.pickBestFpsRange(60))
    }
}
