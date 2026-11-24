package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.StreamProtocol
import com.example.stream.PcAutoLauncherScript
import com.example.stream.UdpDiscoveryBeacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("CamLink USB", appName)
    }

    @Test
    fun `pc auto launcher script substitutes port and protocol cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val script = PcAutoLauncherScript.generateWindowsBatchScript(
            context = context,
            port = 8123,
            protocol = StreamProtocol.MJPEG_HTTP
        )
        assertTrue(script.contains("\$tcpPort = 8123"))
        assertTrue(script.contains("\$ctrlPort = 8124"))
        assertTrue(script.contains("\$udpPort = ${UdpDiscoveryBeacon.DISCOVERY_PORT}"))
        assertTrue(script.contains("\$defaultCodec = \"mjpeg\""))
        assertFalse(script.contains("__TCP_PORT__"))
        assertFalse(script.contains("__CTRL_PORT__"))
        assertFalse(script.contains("__DEFAULT_CODEC__"))
    }
}
