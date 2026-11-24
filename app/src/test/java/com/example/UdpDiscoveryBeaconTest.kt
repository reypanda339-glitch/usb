package com.example

import com.example.data.CameraLens
import com.example.data.StreamProtocol
import com.example.data.VideoResolution
import com.example.stream.DiscoverySnapshot
import com.example.stream.UdpDiscoveryBeacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class UdpDiscoveryBeaconTest {

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun sendHttpRequest(port: Int, request: String): Pair<String, String> {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 2000
            sock.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            sock.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val headers = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.append(line).append("\n")
            }
            val body = reader.readText()
            return headers.toString() to body
        }
    }

    @Test
    fun `http control server responds 204 to OPTIONS and exposes LucyCast endpoints`() {
        val videoPort = findFreePort()
        val ctrlPort = videoPort + 1
        var switchedProto: StreamProtocol? = null
        var switchedLens: CameraLens? = null
        var switchedRes: VideoResolution? = null
        var switchedFps: Int? = null
        var keyframeCount = 0

        val beacon = UdpDiscoveryBeacon(
            onSwitchProtocolRequested = { switchedProto = it },
            onFlipLensRequested = { req ->
                val target = req ?: CameraLens.FRONT
                switchedLens = target
                target
            },
            onTorchRequested = { state -> state == "on" || state == "toggle" },
            onResolutionRequested = { switchedRes = it },
            onFpsRequested = { switchedFps = it },
            onKeyframeRequested = { keyframeCount++ }
        )

        beacon.updateSnapshot(
            DiscoverySnapshot(
                live = true,
                restarting = false,
                pipelineState = "STREAMING",
                port = videoPort,
                protocol = StreamProtocol.H264_TCP,
                lens = CameraLens.BACK,
                resolution = VideoResolution.HD_720P,
                width = 1280,
                height = 720,
                targetFps = 60,
                sensorOrientation = 90,
                flashSupported = true,
                connectedClients = 1,
                hevcSupported = true,
                usbIps = listOf("192.168.42.129")
            )
        )

        try {
            Thread.sleep(150)

            // 1. OPTIONS CORS preflight
            val (optHeaders, optBody) = sendHttpRequest(
                ctrlPort,
                "OPTIONS /status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )
            assertTrue(optHeaders.contains("204 No Content"))
            assertTrue(optHeaders.contains("Access-Control-Allow-Origin: *"))
            assertTrue(optHeaders.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
            assertEquals("", optBody)

            // 2. GET /status
            val (stHeaders, stBody) = sendHttpRequest(
                ctrlPort,
                "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )
            assertTrue(stHeaders.contains("200 OK"))
            assertTrue(stHeaders.contains("Cache-Control: no-store"))
            assertTrue(stBody.contains(""""live":true"""))
            assertTrue(stBody.contains(""""restarting":false"""))
            assertTrue(stBody.contains(""""sensorOrientation":90"""))
            assertTrue(stBody.contains(""""usbIps":["192.168.42.129"]"""))

            // 3. Remote control endpoints
            val (_, flipBody) = sendHttpRequest(ctrlPort, "GET /flip?lens=FRONT HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(flipBody.contains(""""lens":"FRONT""""))
            assertEquals(CameraLens.FRONT, switchedLens)

            val (_, torchBody) = sendHttpRequest(ctrlPort, "GET /torch?state=on HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(torchBody.contains(""""torch":true"""))

            val (_, resBody) = sendHttpRequest(ctrlPort, "GET /resolution?value=FHD_1080P HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(resBody.contains(""""width":1920"""))
            assertEquals(VideoResolution.FHD_1080P, switchedRes)

            val (_, fpsBody) = sendHttpRequest(ctrlPort, "GET /fps?value=30 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(fpsBody.contains(""""fps":30"""))
            assertEquals(30, switchedFps)

            val (_, protoBody) = sendHttpRequest(ctrlPort, "GET /protocol?value=MJPEG_HTTP HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(protoBody.contains(""""proto":"MJPEG_HTTP""""))
            assertEquals(StreamProtocol.MJPEG_HTTP, switchedProto)

            val (_, kfBody) = sendHttpRequest(ctrlPort, "GET /keyframe HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(kfBody.contains(""""ok":true"""))
            assertEquals(1, keyframeCount)
        } finally {
            beacon.shutdown()
        }
    }
}
