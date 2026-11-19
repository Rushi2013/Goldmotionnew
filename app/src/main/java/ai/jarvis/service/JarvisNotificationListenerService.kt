package ai.jarvis.service

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Optional: surfaces WhatsApp notifications for the "inbound while app closed" hint on the Home
 * screen. Never used to read or reply — the app's own Socket.IO subscription is the real source.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!pkg.contains("whatsapp")) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        lastSeen.tryEmit(title to text)
    }

    companion object {
        val lastSeen = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    }
}
