package com.example.indicvoicebridge.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.indicvoicebridge.model.DiscoveredPeer
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class PeerDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "PeerDiscovery"
        private const val SERVICE_TYPE = "_indicvoicebridge._tcp."
        private const val SERVICE_NAME = "IndicVoiceBridge"
        private const val UDP_PORT = 5051
        private const val BEACON_INTERVAL_MS = 3000L
    }

    private val peers = mutableMapOf<String, DiscoveredPeer>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nsdManager: NsdManager? = null
    private var udpSocket: DatagramSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    var onPeersChanged: ((List<DiscoveredPeer>) -> Unit)? = null

    private val deviceName: String get() {
        return android.os.Build.MODEL.replace("[^a-zA-Z0-9]".toRegex(), "-")
    }

    fun start(localPort: Int = 5050) {
        startNsd(localPort)
        startUdpBeaconing()
        cleanupStaleLoop()
    }

    private fun startNsd(localPort: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "${deviceName}-${(1000..9999).random()}"
                serviceType = SERVICE_TYPE
                port = localPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType == SERVICE_TYPE) {
                        nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, code: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val ip = resolved.host?.hostAddress ?: return
                                val peer = DiscoveredPeer(
                                    deviceName = resolved.serviceName,
                                    ip = ip,
                                    port = resolved.port
                                )
                                peers[ip] = peer
                                notifyChanged()
                            }
                        })
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    peers.remove(info.host?.hostAddress)
                    notifyChanged()
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD error: ${e.message}")
        }
    }

    private fun startUdpBeaconing() {
        scope.launch {
            // Listener
            launch {
                try {
                    val socket = DatagramSocket(UDP_PORT)
                    udpSocket = socket
                    val buf = ByteArray(256)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg.startsWith("IVBPEER:")) {
                            val name = msg.removePrefix("IVBPEER:")
                            val ip = packet.address.hostAddress ?: continue
                            if (ip != getLocalIp()) {
                                peers[ip] = DiscoveredPeer(deviceName = name, ip = ip)
                                notifyChanged()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UDP listener: ${e.message}")
                }
            }

            // Sender beacon
            launch {
                delay(500)
                while (isActive) {
                    try {
                        val socket = DatagramSocket()
                        socket.broadcast = true
                        val msg = "IVBPEER:$deviceName".toByteArray()
                        val packet = DatagramPacket(
                            msg, msg.size,
                            InetAddress.getByName("255.255.255.255"), UDP_PORT
                        )
                        socket.send(packet)
                        socket.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Beacon send: ${e.message}")
                    }
                    delay(BEACON_INTERVAL_MS)
                }
            }
        }
    }

    private fun cleanupStaleLoop() {
        scope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                val stale = peers.entries.filter { now - it.value.lastSeen > 15_000 }
                stale.forEach { peers.remove(it.key) }
                if (stale.isNotEmpty()) notifyChanged()
            }
        }
    }

    private fun notifyChanged() {
        val list = peers.values.toList()
        scope.launch(Dispatchers.Main) { onPeersChanged?.invoke(list) }
    }

    fun getLocalIp(): String {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .first { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                    .hostAddress ?: "0.0.0.0"
            } catch (e2: Exception) { "0.0.0.0" }
        }
    }

    fun getPeers(): List<DiscoveredPeer> = peers.values.toList()

    fun stop() {
        scope.cancel()
        udpSocket?.close()
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) { /* ignore */ }
    }
}

