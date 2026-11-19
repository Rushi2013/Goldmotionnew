package ai.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.jarvis.JarvisApp
import ai.jarvis.core.AppMode
import ai.jarvis.core.AutoReplyMode

/** Restores the auto-reply engine after a reboot, only if the user had explicitly enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? JarvisApp ?: return
        val s = app.container.settings.settings.value
        if (s.mode == AppMode.LIVE && s.autoReplyMode != AutoReplyMode.OFF) {
            runCatching { AutoReplyService.start(context) }
        }
    }
}
