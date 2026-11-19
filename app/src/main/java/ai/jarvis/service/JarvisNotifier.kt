package ai.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import ai.jarvis.MainActivity
import ai.jarvis.R
import ai.jarvis.autoreply.Notifier

class JarvisNotifier(private val context: Context) : Notifier {

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ESCALATION, "Auto-reply escalations", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Messages that need your approval before replying" }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ENGINE, "Auto-reply engine", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the auto-reply engine running" }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REPLY, "Auto-replied messages", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Silent log of automatic replies" }
            )
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun contentIntent(route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra("route", it) }
        }
        return PendingIntent.getActivity(
            context, route?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun notifyEscalation(id: Int, title: String, body: String, category: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ESCALATION)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent("approvals"))
            .addAction(0, "Approve", contentIntent("approvals"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ESCALATION_BASE + id, notification) }
    }

    override fun notifyAutoReply(chat: String, draft: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REPLY)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Auto-replied · $chat")
            .setContentText(draft)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(REPLY_ID, notification) }
    }

    override fun notifyEngineRunning(enabled: Boolean, text: String) {
        if (!canPost() || !enabled) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS auto-reply chal raha hai")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent("autoreply"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ENGINE_ID, notification) }
    }

    companion object {
        const val CHANNEL_ESCALATION = "escalation"
        const val CHANNEL_ENGINE = "engine"
        const val CHANNEL_REPLY = "reply"
        const val ENGINE_ID = 1001
        const val REPLY_ID = 1002
        const val ESCALATION_BASE = 2000
    }
}
