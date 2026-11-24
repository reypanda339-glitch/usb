package com.example.stream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class UsbLinkState(
    val isUsbConnected: Boolean = false,
    val isUsbConfigured: Boolean = false,
    val isAdbActive: Boolean = false,
    val isRndisActive: Boolean = false,
    val usbInterfaceIps: List<String> = emptyList(),
    val wifiIps: List<String> = emptyList()
) {
    val primaryDirectIp: String?
        get() = usbInterfaceIps.firstOrNull() ?: wifiIps.firstOrNull()

    val statusHeadline: String
        get() = when {
            isRndisActive && usbInterfaceIps.isNotEmpty() -> "Listo para LucyCast · USB Tethering"
            isUsbConnected && isAdbActive -> "Listo para LucyCast · ADB Listo"
            isUsbConnected -> "Cable USB Conectado"
            else -> "Cable USB Desconectado"
        }
}

class UsbDiagnosticsMonitor(private val context: Context) {

    private val _state = MutableStateFlow(UsbLinkState())
    val state: StateFlow<UsbLinkState> = _state.asStateFlow()

    private var registered = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val delayedRefreshRunnable = Runnable { refresh() }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_STATE) {
                parseIntentAndInterfaces(intent)
                val connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false)
                handler.removeCallbacks(delayedRefreshRunnable)
                if (connected) {
                    handler.postDelayed(delayedRefreshRunnable, 500L)
                    handler.postDelayed(delayedRefreshRunnable, 1500L)
                }
            }
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter(ACTION_USB_STATE)
            val sticky = ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
            parseIntentAndInterfaces(sticky)
        } else {
            refresh()
        }
    }

    fun stop() {
        handler.removeCallbacks(delayedRefreshRunnable)
        if (registered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            registered = false
        }
    }

    fun refresh() {
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(ACTION_USB_STATE),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.getOrNull()
        parseIntentAndInterfaces(sticky)
    }

    private fun parseIntentAndInterfaces(intent: Intent?) {
        val connected = intent?.getBooleanExtra(EXTRA_USB_CONNECTED, false) ?: false
        val configured = intent?.getBooleanExtra(EXTRA_USB_CONFIGURED, false) ?: false
        val adb = intent?.getBooleanExtra(EXTRA_USB_FUNCTION_ADB, false) ?: false
        val rndis = intent?.getBooleanExtra(EXTRA_USB_FUNCTION_RNDIS, false) ?: false

        val usbIps = mutableListOf<String>()
        val wifiIps = mutableListOf<String>()

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        when {
                            UdpDiscoveryBeacon.isUsbInterfaceName(name) -> usbIps.add(hostAddr)
                            name.startsWith("wlan") -> wifiIps.add(hostAddr)
                        }
                    }
                }
            }
        }

        _state.value = UsbLinkState(
            isUsbConnected = connected,
            isUsbConfigured = configured,
            isAdbActive = adb,
            isRndisActive = rndis || usbIps.isNotEmpty(),
            usbInterfaceIps = usbIps.distinct(),
            wifiIps = wifiIps.distinct()
        )
    }

    companion object {
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val EXTRA_USB_CONFIGURED = "configured"
        private const val EXTRA_USB_FUNCTION_ADB = "adb"
        private const val EXTRA_USB_FUNCTION_RNDIS = "rndis"
    }
}
