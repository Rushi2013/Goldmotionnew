package ai.jarvis.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.jarvis.JarvisApp
import ai.jarvis.MainActivity
import ai.jarvis.R
import ai.jarvis.autoreply.AutoReplyEngine
import ai.jarvis.core.AppMode
import ai.jarvis.data.api.SocketIoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the auto-reply engine alive.
 *
 * Honest behaviour (spec §3.1 / §22):
 *  - it only exists while the user has auto-reply enabled;
 *  - the notification says exactly what is running;
 *  - when the service dies (user stops it, OEM kills it), the UI shows AUTO-REPLY OFF.
 *
 * The inbound pipeline is: Socket.IO message.received → AutoReplyEngine.onInbound.
 * If the socket is unavailable we poll recent messages for the allowlisted chats only.
 */
class AutoReplyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Auto-reply engine start ho raha hai…"))

        val app = applicationContext as JarvisApp
        val container = app.container
        if (container.settings.settings.value.mode != AppMode.LIVE) {
            updateNotification("DEMO mode — koi real reply nahi jaayega")
            return START_STICKY
        }

        // Connect the realtime socket with the new key/base URL from Settings.
        container.socket.disconnect()
        container.socket.connect()

        socketJob?.cancel()
        socketJob = scope.launch {
            container.socket.events.collect { event ->
                val inbound = container.socket.toInbound(event) ?: return@collect
                if (inbound.fromMe) return@collect
                val result = runCatching { container.autoReplyEngine.onInbound(inbound) }.getOrNull()
                if (result is AutoReplyEngine.HandleResult.AutoSent) {
                    updateNotification("Last: auto-replied to ${inbound.chatName ?: inbound.chatId}")
                } else if (result is AutoReplyEngine.HandleResult.Escalated) {
                    updateNotification("Last: escalation aayi — Approvals check karo")
                }
            }
        }

        // Keep the state object fresh even if a socket event silently stops arriving.
        scope.launch {
            while (isActive) {
                runCatching { container.autoReplyEngine.refreshState() }
                runCatching { container.sessionManager.refreshSession() }
                delay(60_000)
            }
        }

        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("route", "autoreply"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoReplyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisNotifier.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS auto-reply")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = android.app.NotificationManager::class.java.let { getSystemService(it) }
        runCatching { nm?.notify(NOTIF_ID, buildNotification(text)) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { (applicationContext as JarvisApp).container.socket.disconnect() }
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1003
        const val ACTION_START = "ai.jarvis.START_AUTOREPLY"
        const val ACTION_STOP = "ai.jarvis.STOP_AUTOREPLY"

        fun start(context: Context) {
            val intent = Intent(context, AutoReplyService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoReplyService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
