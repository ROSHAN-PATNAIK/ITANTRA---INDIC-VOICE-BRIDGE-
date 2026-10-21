package com.example.indicvoicebridge.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.indicvoicebridge.MainActivity
import com.example.indicvoicebridge.R
import com.example.indicvoicebridge.model.BridgeMessage
import com.example.indicvoicebridge.model.MessageType
import com.example.indicvoicebridge.network.SocketBridgeTransceiver
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class BridgeForegroundService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "bridge_service"
        private const val CHANNEL_ALERT_ID = "bridge_alert"
        private const val NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 1002
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_ALERT_RECEIVED = "com.example.indicvoicebridge.ALERT_RECEIVED"
        const val EXTRA_ALERT_TEXT = "alert_text"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    var onMessageReceived: ((BridgeMessage) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildPersistentNotification())
        startSocketServer()
        return START_STICKY
    }

    private fun startSocketServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(SocketBridgeTransceiver.PORT)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server: ${e.message}")
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
                    val alertText = parts.getOrElse(2) { parts.getOrElse(1) { "EMERGENCY ALERT" } }
                    triggerAlertWakeLock()
                    fireAlertNotification(senderIp, alertText)
                    val msg = BridgeMessage(
                        senderIp = senderIp, originalText = alertText, translatedText = alertText,
                        sourceLanguage = "en", targetLanguage = "en",
                        type = MessageType.ALERT, isOutgoing = false
                    )
                    onMessageReceived?.invoke(msg)
                    // Broadcast to activity if running
                    val broadcast = Intent(ACTION_ALERT_RECEIVED).apply {
                        putExtra(EXTRA_ALERT_TEXT, alertText)
                        `package` = packageName
                    }
                    sendBroadcast(broadcast)
                }
                "MESSAGE" -> {
                    val targetLang = parts.getOrElse(1) { "en" }
                    val original = parts.getOrElse(2) { "" }
                    val translated = parts.getOrElse(3) { original }
                    val msg = BridgeMessage(
                        senderIp = senderIp, originalText = original, translatedText = translated,
                        sourceLanguage = "en", targetLanguage = targetLang,
                        type = MessageType.MESSAGE, isOutgoing = false
                    )
                    onMessageReceived?.invoke(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun triggerAlertWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "IndicVoiceBridge::AlertWakeLock"
        )
        wakeLock?.acquire(10_000L)
        // Vibrate SOS pattern
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 300, 100, 300, 100, 600, 200, 300, 100, 300)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        // Play alert tone
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
                .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000)
        } catch (e: Exception) { Log.e(TAG, "Tone: ${e.message}") }
    }

    private fun fireAlertNotification(senderIp: String, text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 EMERGENCY ALERT from $senderIp")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ALERT_NOTIF_ID, notif)
    }

    private fun buildPersistentNotification(): Notification {
        val stopIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Indic Voice Bridge")
            .setContentText("Active on port ${SocketBridgeTransceiver.PORT} — tap to open")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bridge Service", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High-priority emergency SOS alerts"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        serverSocket?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

