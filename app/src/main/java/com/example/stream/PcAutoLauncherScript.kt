package com.example.stream

import android.content.Context
import com.example.R
import com.example.data.StreamProtocol

object PcAutoLauncherScript {

    fun generateWindowsBatchScript(
        context: Context,
        port: Int,
        protocol: StreamProtocol
    ): String {
        val defaultCodec = when (protocol) {
            StreamProtocol.H264_TCP -> "h264"
            StreamProtocol.HEVC_TCP -> "hevc"
            StreamProtocol.MJPEG_HTTP -> "mjpeg"
        }
        val template = context.resources.openRawResource(R.raw.camlink_autopc)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        return template
            .replace("__TCP_PORT__", port.toString())
            .replace("__CTRL_PORT__", (port + 1).toString())
            .replace("__UDP_PORT__", UdpDiscoveryBeacon.DISCOVERY_PORT.toString())
            .replace("__DEFAULT_CODEC__", defaultCodec)
    }
}
