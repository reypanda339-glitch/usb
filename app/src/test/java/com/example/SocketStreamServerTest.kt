package com.example

import com.example.data.StreamProtocol
import com.example.stream.SocketStreamServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocketStreamServerTest {

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    @Test
    fun `h264 tcp streams codec config and video packet with zero framing overhead`() {
        val port = findFreePort()
        val syncRequested = CountDownLatch(1)
        val server = SocketStreamServer(
            onClientNeedsSyncFrame = { syncRequested.countDown() }
        )

        server.start(port, StreamProtocol.H264_TCP)
        try {
            // Wait briefly for ServerSocket bind
            Thread.sleep(150)
            val configBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
            val idrBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
            server.publishCodecConfig(configBytes)

            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 2000
                assertTrue(syncRequested.await(2, TimeUnit.SECONDS))

                val input = client.getInputStream()
                val receivedConfig = ByteArray(configBytes.size)
                var read = 0
                while (read < receivedConfig.size) {
                    val n = input.read(receivedConfig, read, receivedConfig.size - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals(configBytes.toList(), receivedConfig.toList())

                server.publishVideoPacket(idrBytes, isKeyFrame = true)
                val receivedIdr = ByteArray(idrBytes.size)
                read = 0
                while (read < receivedIdr.size) {
                    val n = input.read(receivedIdr, read, receivedIdr.size - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals(idrBytes.toList(), receivedIdr.toList())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `h264 tcp skips mid-gop p-frames until first keyframe arrives`() {
        val port = findFreePort()
        val syncRequested = CountDownLatch(1)
        val server = SocketStreamServer(
            onClientNeedsSyncFrame = { syncRequested.countDown() }
        )

        server.start(port, StreamProtocol.H264_TCP)
        try {
            Thread.sleep(150)
            val configBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
            val pFrameBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte())
            val idrBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
            server.publishCodecConfig(configBytes)

            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 2000
                assertTrue(syncRequested.await(2, TimeUnit.SECONDS))

                val input = client.getInputStream()
                val receivedConfig = ByteArray(configBytes.size)
                var read = 0
                while (read < receivedConfig.size) {
                    val n = input.read(receivedConfig, read, receivedConfig.size - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals(configBytes.toList(), receivedConfig.toList())

                // Publish a P-frame before any IDR frame; client must skip it
                server.publishVideoPacket(pFrameBytes, isKeyFrame = false)
                server.publishVideoPacket(idrBytes, isKeyFrame = true)

                val receivedIdr = ByteArray(idrBytes.size)
                read = 0
                while (read < receivedIdr.size) {
                    val n = input.read(receivedIdr, read, receivedIdr.size - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals(idrBytes.toList(), receivedIdr.toList())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `mjpeg http serves multipart headers and jpeg payload`() {
        val port = findFreePort()
        val server = SocketStreamServer(onClientNeedsSyncFrame = {})
        server.start(port, StreamProtocol.MJPEG_HTTP)
        try {
            Thread.sleep(150)
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 2000
                val out = client.getOutputStream()
                out.write("GET /video HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                out.flush()

                val input = client.getInputStream()
                val buf = ByteArray(512)
                val n = input.read(buf)
                val response = String(buf, 0, n, Charsets.US_ASCII)
                assertTrue(response.contains("HTTP/1.1 200 OK"))
                assertTrue(response.contains("multipart/x-mixed-replace; boundary=camlinkframe"))

                val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
                server.publishVideoPacket(fakeJpeg, isKeyFrame = true)

                val frameBuf = ByteArray(256)
                val frameLen = input.read(frameBuf)
                val frameHeader = String(frameBuf, 0, frameLen, Charsets.US_ASCII)
                assertTrue(frameHeader.contains("--camlinkframe"))
                assertTrue(frameHeader.contains("Content-Type: image/jpeg"))
            }
        } finally {
            server.stop()
        }
    }
}
