package com.example.stream

import com.example.data.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class EncodedFrame(
    val data: ByteArray,
    val isCodecConfig: Boolean = false,
    val isKeyFrame: Boolean = false
)

data class SocketServerStats(
    val isListening: Boolean = false,
    val port: Int = 7878,
    val connectedClients: Int = 0,
    val clientAddresses: List<String> = emptyList(),
    val totalBytesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val instantBitrateMbps: Float = 0f,
    val lastError: String? = null
)

class SocketStreamServer(
    private val onClientNeedsSyncFrame: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeProtocol: StreamProtocol = StreamProtocol.H264_TCP

    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    private val clients = CopyOnWriteArrayList<ClientSession>()
    private val totalBytesSent = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)

    private var windowBytes = 0L
    private var windowStartNs = System.nanoTime()
    private var lastBitrateMbps = 0f

    private val _stats = MutableStateFlow(SocketServerStats())
    val stats: StateFlow<SocketServerStats> = _stats.asStateFlow()

    private class ClientSession(
        val id: String,
        val socket: Socket,
        val channel: Channel<EncodedFrame>,
        val job: Job
    )

    fun start(port: Int, protocol: StreamProtocol) {
        stop()
        activeProtocol = protocol
        cachedCodecConfig = null
        totalBytesSent.set(0L)
        droppedFrames.set(0L)
        windowBytes = 0L
        windowStartNs = System.nanoTime()
        lastBitrateMbps = 0f

        serverJob = scope.launch {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                serverSocket = ss
                updateStats(isListening = true, port = port, error = null)

                while (isActive && !ss.isClosed) {
                    val socket = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    configureLowLatencySocket(socket)
                    launchClientSession(socket, port)
                }
            } catch (e: Exception) {
                updateStats(
                    isListening = false,
                    port = port,
                    error = e.localizedMessage ?: "No se pudo abrir el puerto $port"
                )
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { session ->
            session.job.cancel()
            session.channel.close()
            runCatching { session.socket.close() }
        }
        clients.clear()
        cachedCodecConfig = null
        updateStats(isListening = false, port = _stats.value.port, error = null)
    }

    fun publishCodecConfig(configBytes: ByteArray) {
        cachedCodecConfig = configBytes
        broadcast(EncodedFrame(data = configBytes, isCodecConfig = true, isKeyFrame = false))
    }

    fun publishVideoPacket(bytes: ByteArray, isKeyFrame: Boolean) {
        broadcast(EncodedFrame(data = bytes, isCodecConfig = false, isKeyFrame = isKeyFrame))
    }

    private fun broadcast(frame: EncodedFrame) {
        if (clients.isEmpty()) return

        for (client in clients) {
            val result = client.channel.trySend(frame)
            if (result.isFailure && !frame.isCodecConfig) {
                droppedFrames.incrementAndGet()
            }
        }
    }

    private fun configureLowLatencySocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 64 * 1024
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
            socket.soTimeout = 0
        }
    }

    private fun launchClientSession(socket: Socket, port: Int) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
        val clientLabel = "$remoteIp:${socket.port}"
        val channel = Channel<EncodedFrame>(
            capacity = if (activeProtocol == StreamProtocol.MJPEG_HTTP) 1 else 3,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        lateinit var session: ClientSession
        val job = scope.launch {
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
                var awaitingKeyFrame = activeProtocol != StreamProtocol.MJPEG_HTTP
                if (activeProtocol == StreamProtocol.MJPEG_HTTP) {
                    val servedHtml = handleHttpHandshake(socket, out)
                    if (servedHtml) {
                        return@launch
                    }
                } else {
                    cachedCodecConfig?.let { config ->
                        out.write(config)
                        out.flush()
                        recordBytes(config.size)
                    }
                    onClientNeedsSyncFrame()
                }

                for (frame in channel) {
                    if (!isActive || socket.isClosed) break
                    if (activeProtocol == StreamProtocol.MJPEG_HTTP) {
                        val header = (
                            "--$MJPEG_BOUNDARY\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.data.size}\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        out.write(header)
                        out.write(frame.data)
                        out.write(CRLF_BYTES)
                        out.flush()
                        recordBytes(header.size + frame.data.size + CRLF_BYTES.size)
                    } else {
                        if (awaitingKeyFrame) {
                            if (frame.isCodecConfig) {
                                out.write(frame.data)
                                out.flush()
                                recordBytes(frame.data.size)
                                continue
                            }
                            if (!frame.isKeyFrame) {
                                continue
                            }
                            awaitingKeyFrame = false
                        }
                        out.write(frame.data)
                        out.flush()
                        recordBytes(frame.data.size)
                    }
                }
            } catch (_: Exception) {
                // Client disconnected
            } finally {
                clients.remove(session)
                channel.close()
                runCatching { socket.close() }
                updateStats(isListening = serverSocket?.isClosed == false, port = port, error = null)
            }
        }

        session = ClientSession(
            id = clientLabel,
            socket = socket,
            channel = channel,
            job = job
        )
        clients.add(session)
        updateStats(isListening = true, port = port, error = null)
    }

    private fun handleHttpHandshake(socket: Socket, out: BufferedOutputStream): Boolean {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLines = StringBuilder()
        val buf = ByteArray(2048)
        socket.soTimeout = 1500
        var readTotal = 0
        while (readTotal < buf.size) {
            val b = input.read()
            if (b == -1) break
            requestLines.append(b.toChar())
            readTotal++
            if (requestLines.endsWith("\r\n\r\n")) break
        }
        socket.soTimeout = 0

        val firstLine = requestLines.toString().lineSequence().firstOrNull().orEmpty()
        val reqParts = firstLine.split(" ")
        val method = reqParts.getOrNull(0)?.uppercase() ?: "GET"
        val path = reqParts.getOrNull(1) ?: "/"

        if (method == "OPTIONS") {
            val optResp = (
                "HTTP/1.1 204 No Content\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "Access-Control-Allow-Private-Network: true\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            out.write(optResp)
            out.flush()
            return true
        }

        if (path.startsWith("/monitor")) {
            val html = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>CamLink USB Live</title>
                <style>html,body{margin:0;height:100%;background:#0B0F14;overflow:hidden;display:flex;align-items:center;justify-content:center}
                img{width:100vw;height:100vh;object-fit:contain}</style></head>
                <body><img id="cam" src="/video" alt="CamLink USB Live"/>
                <script>const i=document.getElementById('cam');i.onerror=()=>setTimeout(()=>{i.src='/video?t='+Date.now()},600);</script>
                </body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val resp = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Private-Network: true\r\n" +
                    "Content-Length: ${html.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            out.write(resp)
            out.write(html)
            out.flush()
            return true
        }

        val mjpegHeader = (
            "HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: -1\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        out.write(mjpegHeader)
        out.flush()
        return false
    }

    private fun recordBytes(bytes: Int) {
        val total = totalBytesSent.addAndGet(bytes.toLong())
        val now = System.nanoTime()
        windowBytes += bytes
        val elapsedNs = now - windowStartNs
        if (elapsedNs >= 500_000_000L) {
            val bitsPerSec = (windowBytes * 8.0) / (elapsedNs / 1_000_000_000.0)
            lastBitrateMbps = (bitsPerSec / 1_000_000.0).toFloat()
            windowBytes = 0L
            windowStartNs = now
            updateStats(
                isListening = serverSocket?.isClosed == false,
                port = _stats.value.port,
                error = _stats.value.lastError,
                total = total
            )
        }
    }

    private fun updateStats(
        isListening: Boolean,
        port: Int,
        error: String?,
        total: Long = totalBytesSent.get()
    ) {
        _stats.value = SocketServerStats(
            isListening = isListening,
            port = port,
            connectedClients = clients.size,
            clientAddresses = clients.map { it.id },
            totalBytesSent = total,
            droppedFrames = droppedFrames.get(),
            instantBitrateMbps = lastBitrateMbps,
            lastError = error
        )
    }

    companion object {
        private const val MJPEG_BOUNDARY = "camlinkframe"
        private val CRLF_BYTES = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
