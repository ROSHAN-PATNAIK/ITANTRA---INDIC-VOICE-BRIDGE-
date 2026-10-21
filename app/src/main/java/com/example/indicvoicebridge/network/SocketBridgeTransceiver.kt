package com.example.indicvoicebridge.network

import android.util.Log
import com.example.indicvoicebridge.model.BridgeMessage
import com.example.indicvoicebridge.model.DiscoveredPeer
import com.example.indicvoicebridge.model.MessageType
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class SocketBridgeTransceiver {

    companion object {
        private const val TAG = "Transceiver"
        const val PORT = 5050
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    var onMessageReceived: ((BridgeMessage) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    fun startReceiving() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                onStatusUpdate?.invoke("Listening on port $PORT…")
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val raw = reader.readLine() ?: return
            val parts = raw.split("|")
            val senderIp = socket.inetAddress.hostAddress ?: "unknown"
            when (parts[0]) {
                "ALERT" -> {
                    val alertText = parts.getOrElse(2) { parts.getOrElse(1) { "ALERT" } }
                    val msg = BridgeMessage(
                        senderIp = senderIp,
                        originalText = alertText,
                        translatedText = alertText,
                        sourceLanguage = "en",
                        targetLanguage = "en",
                        type = MessageType.ALERT,
                        isOutgoing = false
                    )
                    onMessageReceived?.invoke(msg)
                }
                "MESSAGE" -> {
                    val targetLang = parts.getOrElse(1) { "en" }
                    val original = parts.getOrElse(2) { "" }
                    val translated = parts.getOrElse(3) { original }
                    val msg = BridgeMessage(
                        senderIp = senderIp,
                        originalText = original,
                        translatedText = translated,
                        sourceLanguage = "en",
                        targetLanguage = targetLang,
                        type = MessageType.MESSAGE,
                        isOutgoing = false
                    )
                    onMessageReceived?.invoke(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handling: ${e.message}")
        } finally {
            socket.close()
        }
    }

    fun sendToOne(peer: DiscoveredPeer, payload: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val socket = Socket(peer.ip, peer.port)
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(payload)
                socket.close()
                withContext(Dispatchers.Main) { onResult(true, "Sent to ${peer.deviceName}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "Error") }
            }
        }
    }

    fun sendToAll(peers: List<DiscoveredPeer>, payload: String, onResult: (Int, Int) -> Unit) {
        scope.launch {
            var success = 0; var fail = 0
            peers.map { peer ->
                async {
                    try {
                        val socket = Socket(peer.ip, peer.port)
                        PrintWriter(socket.getOutputStream(), true).println(payload)
                        socket.close()
                        true
                    } catch (e: Exception) { false }
                }
            }.forEach { if (it.await()) success++ else fail++ }
            withContext(Dispatchers.Main) { onResult(success, fail) }
        }
    }

    fun buildMessagePayload(targetLang: String, original: String, translated: String): String =
        "MESSAGE|$targetLang|$original|$translated"

    fun buildAlertPayload(deviceName: String, text: String): String =
        "ALERT|$deviceName|$text"

    fun stop() {
        scope.cancel()
        serverSocket?.close()
    }
}

