package com.example.stream

import android.os.Build
import com.example.BuildConfig
import com.example.data.CameraLens
import com.example.data.StreamProtocol
import com.example.data.VideoResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

data class DiscoverySnapshot(
    val live: Boolean = false,
    val restarting: Boolean = false,
    val pipelineState: String = "IDLE",
    val port: Int = 7878,
    val protocol: StreamProtocol = StreamProtocol.H264_TCP,
    val lens: CameraLens = CameraLens.BACK,
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val width: Int = 1280,
    val height: Int = 720,
    val targetFps: Int = 60,
    val actualFps: Int = 0,
    val bitrateMbps: Int = 8,
    val sensorOrientation: Int = 90,
    val torchEnabled: Boolean = false,
    val flashSupported: Boolean = false,
    val connectedClients: Int = 0,
    val hevcSupported: Boolean = false,
    val usbIps: List<String> = emptyList()
)

class UdpDiscoveryBeacon(
    private val onSwitchProtocolRequested: (StreamProtocol) -> Unit = {},
    private val onStartStreamRequested: () -> Unit = {},
    private val onStopStreamRequested: () -> Unit = {},
    private val onKeyframeRequested: () -> Unit = {},
    private val onFlipLensRequested: (CameraLens?) -> CameraLens = { it ?: CameraLens.BACK },
    private val onTorchRequested: (String?) -> Boolean = { false },
    private val onResolutionRequested: (VideoResolution) -> Unit = {},
    private val onFpsRequested: (Int) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var beaconJob: Job? = null
    private var udpResponderJob: Job? = null
    private var httpControlJob: Job? = null

    private var udpListenSocket: DatagramSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var boundCtrlPort: Int = -1

    @Volatile
    private var snapshot: DiscoverySnapshot = DiscoverySnapshot()

    fun updateSnapshot(newSnapshot: DiscoverySnapshot) {
        val previous = snapshot
        snapshot = newSnapshot
        val targetCtrlPort = newSnapshot.port + 1

        if (httpServerSocket == null || httpServerSocket?.isClosed == true || boundCtrlPort != targetCtrlPort) {
            startControlAndResponder(targetCtrlPort)
        }

        if (newSnapshot.live && !previous.live) {
            startLiveBeaconLoop()
        } else if (!newSnapshot.live && previous.live && !newSnapshot.restarting) {
            stopLiveBeaconLoop()
            emitStopBurst(previous.port, previous.protocol)
        }
    }

    fun start(port: Int, protocol: StreamProtocol) {
        updateSnapshot(
            snapshot.copy(
                live = true,
                restarting = false,
                pipelineState = "STREAMING",
                port = port,
                protocol = protocol
            )
        )
    }

    fun stop() {
        val prev = snapshot
        snapshot = prev.copy(
            live = false,
            restarting = false,
            pipelineState = "PREVIEW_ONLY",
            actualFps = 0,
            connectedClients = 0
        )
        stopLiveBeaconLoop()
        if (prev.live) {
            emitStopBurst(prev.port, prev.protocol)
        }
    }

    fun shutdown() {
        snapshot = snapshot.copy(live = false, restarting = false, pipelineState = "IDLE")
        stopLiveBeaconLoop()
        udpResponderJob?.cancel()
        udpResponderJob = null
        httpControlJob?.cancel()
        httpControlJob = null
        runCatching { udpListenSocket?.close() }
        udpListenSocket = null
        runCatching { httpServerSocket?.close() }
        httpServerSocket = null
        boundCtrlPort = -1
    }

    private fun startControlAndResponder(ctrlPort: Int) {
        udpResponderJob?.cancel()
        httpControlJob?.cancel()
        runCatching { udpListenSocket?.close() }
        runCatching { httpServerSocket?.close() }
        udpListenSocket = null
        httpServerSocket = null
        boundCtrlPort = ctrlPort

        // 1. UDP listener for PC-initiated CAMLINK_WHO (works in BOTH standby & live modes)
        udpResponderJob = scope.launch {
            runCatching {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                    broadcast = true
                }
                udpListenSocket = sock
                val buf = ByteArray(512)
                while (isActive && !sock.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("CAMLINK_WHO")) {
                        val snap = snapshot
                        val tag = if (snap.live) "CAMLINK_LIVE" else "CAMLINK_READY"
                        val ip = pickBestLocalIp()
                        val reply = buildRichPayload(tag, snap, ip)
                        val replyBytes = reply.toByteArray(Charsets.UTF_8)
                        val replyPacket = DatagramPacket(
                            replyBytes,
                            replyBytes.size,
                            packet.address,
                            packet.port
                        )
                        sock.send(replyPacket)
                    }
                }
            }
        }

        // 2. Persistent HTTP Control/Status server on TCP (port + 1)
        httpControlJob = scope.launch {
            runCatching {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", ctrlPort))
                }
                httpServerSocket = ss
                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleControlRequest(client) }
                }
            }
        }
    }

    private fun startLiveBeaconLoop() {
        beaconJob?.cancel()
        beaconJob = scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    while (isActive && (snapshot.live || snapshot.restarting)) {
                        val snap = snapshot
                        if (snap.live) {
                            sendBroadcastBurst(socket, "CAMLINK_LIVE", snap)
                        }
                        delay(BEACON_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun stopLiveBeaconLoop() {
        beaconJob?.cancel()
        beaconJob = null
    }

    private fun emitStopBurst(port: Int, protocol: StreamProtocol) {
        scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val snap = snapshot.copy(port = port, protocol = protocol)
                    sendBroadcastBurst(socket, "CAMLINK_STOP", snap)
                }
            }
        }
    }

    private fun handleControlRequest(client: Socket) {
        try {
            client.soTimeout = 1500
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine().orEmpty()
            // Consume remaining HTTP headers cleanly
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/status"
            val path = rawPath.substringBefore("?")
            val query = parseQueryParams(rawPath.substringAfter("?", ""))

            if (method == "OPTIONS") {
                val optionsResp = (
                    "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Access-Control-Allow-Private-Network: true\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                client.getOutputStream().use { out ->
                    out.write(optionsResp)
                    out.flush()
                }
                return
            }

            val body = when {
                path == "/use-mjpeg" -> {
                    if (snapshot.protocol != StreamProtocol.MJPEG_HTTP) {
                        snapshot = snapshot.copy(protocol = StreamProtocol.MJPEG_HTTP, restarting = snapshot.live)
                        onSwitchProtocolRequested(StreamProtocol.MJPEG_HTTP)
                    }
                    """{"ok":true,"live":${snapshot.live},"proto":"${StreamProtocol.MJPEG_HTTP.name}","port":${snapshot.port}}"""
                }
                path == "/use-h264" -> {
                    if (snapshot.protocol != StreamProtocol.H264_TCP) {
                        snapshot = snapshot.copy(protocol = StreamProtocol.H264_TCP, restarting = snapshot.live)
                        onSwitchProtocolRequested(StreamProtocol.H264_TCP)
                    }
                    """{"ok":true,"live":${snapshot.live},"proto":"${StreamProtocol.H264_TCP.name}","port":${snapshot.port}}"""
                }
                path == "/use-hevc" -> {
                    val target = if (snapshot.hevcSupported) StreamProtocol.HEVC_TCP else StreamProtocol.H264_TCP
                    if (snapshot.protocol != target) {
                        snapshot = snapshot.copy(protocol = target, restarting = snapshot.live)
                        onSwitchProtocolRequested(target)
                    }
                    """{"ok":${snapshot.hevcSupported},"proto":"${target.name}","port":${snapshot.port}}"""
                }
                path == "/protocol" -> {
                    val requested = query["value"]?.uppercase()?.let { v ->
                        runCatching { StreamProtocol.valueOf(v) }.getOrNull()
                    }
                    if (requested == null) {
                        """{"ok":false,"error":"invalid_protocol","proto":"${snapshot.protocol.name}"}"""
                    } else if (requested == StreamProtocol.HEVC_TCP && !snapshot.hevcSupported) {
                        """{"ok":false,"error":"hevc_not_supported","proto":"${snapshot.protocol.name}"}"""
                    } else {
                        if (snapshot.protocol != requested) {
                            snapshot = snapshot.copy(protocol = requested, restarting = snapshot.live)
                            onSwitchProtocolRequested(requested)
                        }
                        """{"ok":true,"proto":"${requested.name}","port":${snapshot.port}}"""
                    }
                }
                path == "/flip" -> {
                    val requestedLens = query["lens"]?.uppercase()?.let { v ->
                        runCatching { CameraLens.valueOf(v) }.getOrNull()
                    }
                    val newLens = onFlipLensRequested(requestedLens)
                    snapshot = snapshot.copy(lens = newLens, torchEnabled = false, restarting = snapshot.live)
                    """{"ok":true,"lens":"${newLens.name}"}"""
                }
                path == "/torch" -> {
                    val newTorch = onTorchRequested(query["state"])
                    snapshot = snapshot.copy(torchEnabled = newTorch)
                    """{"ok":true,"torch":$newTorch,"flashSupported":${snapshot.flashSupported}}"""
                }
                path == "/resolution" -> {
                    val requestedRes = query["value"]?.uppercase()?.let { v ->
                        runCatching { VideoResolution.valueOf(v) }.getOrNull()
                    }
                    if (requestedRes == null) {
                        """{"ok":false,"error":"invalid_resolution","resolution":"${snapshot.resolution.name}","width":${snapshot.width},"height":${snapshot.height}}"""
                    } else {
                        snapshot = snapshot.copy(
                            resolution = requestedRes,
                            width = requestedRes.width,
                            height = requestedRes.height,
                            restarting = snapshot.live
                        )
                        onResolutionRequested(requestedRes)
                        """{"ok":true,"resolution":"${requestedRes.name}","width":${requestedRes.width},"height":${requestedRes.height}}"""
                    }
                }
                path == "/fps" -> {
                    val requestedFps = query["value"]?.toIntOrNull()?.takeIf { it in listOf(24, 30, 60) }
                    if (requestedFps == null) {
                        """{"ok":false,"error":"invalid_fps","fps":${snapshot.targetFps}}"""
                    } else {
                        snapshot = snapshot.copy(targetFps = requestedFps, restarting = snapshot.live)
                        onFpsRequested(requestedFps)
                        """{"ok":true,"fps":$requestedFps}"""
                    }
                }
                path == "/keyframe" -> {
                    onKeyframeRequested()
                    """{"ok":true}"""
                }
                path == "/start" -> {
                    if (!snapshot.live) {
                        onStartStreamRequested()
                    }
                    buildStatusJson(snapshot, ok = true)
                }
                path == "/stop" -> {
                    if (snapshot.live) {
                        onStopStreamRequested()
                    }
                    buildStatusJson(snapshot, ok = true)
                }
                else -> buildStatusJson(snapshot)
            }

            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val response = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "Access-Control-Allow-Private-Network: true\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)

            client.getOutputStream().use { out ->
                out.write(response)
                out.write(bodyBytes)
                out.flush()
            }
        } catch (_: Exception) {
            // Ignore transient probes
        } finally {
            runCatching { client.close() }
        }
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        if (queryString.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        queryString.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                map[key] = value
            }
        }
        return map
    }

    private fun buildStatusJson(snap: DiscoverySnapshot, ok: Boolean = true): String {
        val model = escapeJson("${Build.MANUFACTURER} ${Build.MODEL}".trim())
        val streamPath = if (snap.protocol == StreamProtocol.MJPEG_HTTP) "/video" else ""
        val usbIpsJson = snap.usbIps.joinToString(prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }
        return """{""" +
            """"ok":$ok,""" +
            """"live":${snap.live},""" +
            """"restarting":${snap.restarting},""" +
            """"state":"${snap.pipelineState}",""" +
            """"port":${snap.port},""" +
            """"ctrlPort":${snap.port + 1},""" +
            """"udpPort":$DISCOVERY_PORT,""" +
            """"proto":"${snap.protocol.name}",""" +
            """"lens":"${snap.lens.name}",""" +
            """"resolution":"${snap.resolution.name}",""" +
            """"width":${snap.width},""" +
            """"height":${snap.height},""" +
            """"fps":${snap.targetFps},""" +
            """"actualFps":${snap.actualFps},""" +
            """"bitrateMbps":${snap.bitrateMbps},""" +
            """"sensorOrientation":${snap.sensorOrientation},""" +
            """"torch":${snap.torchEnabled},""" +
            """"flashSupported":${snap.flashSupported},""" +
            """"connectedClients":${snap.connectedClients},""" +
            """"clients":${snap.connectedClients},""" +
            """"hevcSupported":${snap.hevcSupported},""" +
            """"usbIps":$usbIpsJson,""" +
            """"streamPath":"$streamPath",""" +
            """"device":"$model",""" +
            """"appId":"${BuildConfig.APPLICATION_ID}"""" +
            """}"""
    }

    private fun sendBroadcastBurst(socket: DatagramSocket, tag: String, snap: DiscoverySnapshot) {
        val targets = collectBroadcastTargets()
        targets.forEach { (localIp, broadcastAddr) ->
            runCatching {
                val message = buildRichPayload(tag, snap, localIp)
                val bytes = message.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, DISCOVERY_PORT)
                socket.send(packet)
            }
        }
    }

    companion object {
        const val DISCOVERY_PORT = 7879
        private const val BEACON_INTERVAL_MS = 1000L

        fun isUsbInterfaceName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("rndis") ||
                lower.contains("usb") ||
                lower.contains("ncm") ||
                lower.contains("tether") ||
                lower.startsWith("eth")
        }

        fun buildPayload(tag: String, port: Int, protocol: StreamProtocol): String {
            return "$tag|port=$port|proto=${protocol.name}"
        }

        fun buildRichPayload(tag: String, snap: DiscoverySnapshot, localIp: String): String {
            val safeModel = Build.MODEL.replace("|", " ").replace("=", " ").trim()
            return "$tag|ip=$localIp|port=${snap.port}|ctrlPort=${snap.port + 1}|proto=${snap.protocol.name}" +
                "|lens=${snap.lens.name}|w=${snap.width}|h=${snap.height}|fps=${snap.targetFps}" +
                "|sensorOrientation=${snap.sensorOrientation}|state=${snap.pipelineState}|device=$safeModel"
        }

        private fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
        }

        fun pickBestLocalIp(): String {
            var fallback = "127.0.0.1"
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (isUsbInterfaceName(nif.name)) {
                                return ip
                            }
                            if (fallback == "127.0.0.1") fallback = ip
                        }
                    }
                }
            }
            return fallback
        }

        fun collectBroadcastTargets(): List<Pair<String, InetAddress>> {
            val targets = mutableListOf<Pair<String, InetAddress>>()
            var primaryIp = "127.0.0.1"

            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ifAddr in nif.interfaceAddresses) {
                        val addr = ifAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val hostIp = addr.hostAddress ?: continue
                            if (isUsbInterfaceName(nif.name)) {
                                primaryIp = hostIp
                            } else if (primaryIp == "127.0.0.1") {
                                primaryIp = hostIp
                            }
                            ifAddr.broadcast?.let { bcast ->
                                targets.add(hostIp to bcast)
                            }
                        }
                    }
                }
            }

            runCatching {
                targets.add(primaryIp to InetAddress.getByName("255.255.255.255"))
            }
            return targets.distinctBy { "${it.first}:${it.second.hostAddress}" }
        }
    }
}
