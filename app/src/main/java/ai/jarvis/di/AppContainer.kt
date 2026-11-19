package ai.jarvis.di

import kotlinx.coroutines.launch

import android.content.Context
import ai.jarvis.ai.AiGateway
import ai.jarvis.ai.MessageClassifier
import ai.jarvis.autoreply.AutoReplyEngine
import ai.jarvis.autoreply.Notifier
import ai.jarvis.data.SettingsStore
import ai.jarvis.data.api.HttpCore
import ai.jarvis.data.api.OpenWaApi
import ai.jarvis.data.api.SessionManager
import ai.jarvis.data.api.SocketIoManager
import ai.jarvis.data.db.JarvisDatabase
import ai.jarvis.domain.intents.IntentParser
import ai.jarvis.whatsapp.bulk.BulkExecutor
import ai.jarvis.whatsapp.DemoGateway
import ai.jarvis.service.JarvisNotifier

/**
 * Hand-rolled dependency container (no annotation processors for DI).
 *
 * Everything is created lazily and recreated when Settings change, because the base URL and the
 * API key live inside the same HttpCore that every repository uses. `refresh()` is called by the
 * Settings screen after a save so the next request uses the new values.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val settings = SettingsStore(appContext)
    val database = JarvisDatabase.get(appContext)

    /** Providers read Settings on every call, so a settings change is picked up immediately. */
    val http = HttpCore(
        baseUrlProvider = { settings.settings.value.baseUrl },
        apiKeyProvider = { settings.settings.value.apiKey },
    )

    val api = OpenWaApi(http)

    val socket = SocketIoManager(
        baseUrlProvider = { settings.settings.value.baseUrl },
        apiKeyProvider = { settings.settings.value.apiKey },
    )

    val sessionManager = SessionManager(api, settings)
    val aiGateway = AiGateway(configProvider = { settings.settings.value.llm }, http = http)
    val classifier = MessageClassifier(aiGateway)
    val intentParser = IntentParser(aiGateway)
    val notifier: Notifier = JarvisNotifier(appContext)
    val demo = DemoGateway()

    val autoReplyEngine = AutoReplyEngine(database, api, classifier, settings, notifier)
    val bulkExecutor = BulkExecutor(api, database, settings)

    /** Voice/Home → Bulk composer hand-off (kept off the nav route so no URL-encoding edge cases). */
    val bulkPrefill = kotlinx.coroutines.flow.MutableStateFlow<BulkPrefill?>(null)

    private val logScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** Logs every API call (redacted) for Dev Tools. */
    fun logCall(method: String, path: String, status: Int, latencyMs: Long, errorCode: String?, isDemo: Boolean) {
        logScope.launch {
            database.logDao().insert(
                ai.jarvis.data.db.ApiCallLogEntity(
                    method = method,
                    path = path,
                    statusCode = status,
                    latencyMs = latencyMs,
                    errorCode = errorCode,
                    isDemo = isDemo,
                    host = runCatching { java.net.URI(settings.settings.value.baseUrl).host ?: "" }.getOrDefault(""),
                )
            )
        }
    }

    data class BulkPrefill(val type: String, val text: String)

    fun shutdown() {
        socket.disconnect()
    }
}
