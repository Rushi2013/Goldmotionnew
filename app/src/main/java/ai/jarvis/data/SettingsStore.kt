package ai.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.jarvis.core.AppMode
import ai.jarvis.core.AutoReplyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LlmConfig(
    val provider: String = "none", // none | openai-compat | gemini | ollama
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val temperature: Double = 0.3,
)

@Serializable
data class AppSettings(
    val baseUrl: String = "http://10.0.2.2:2785/api",
    val apiKey: String = "",
    val sessionId: String = "",
    val sessionName: String = "",
    val mode: AppMode = AppMode.DEMO,
    val defaultCountryCode: String = "91",
    val llm: LlmConfig = LlmConfig(),
    val onboardingDone: Boolean = false,
    val autoReplyMode: AutoReplyMode = AutoReplyMode.OFF,
    val autoReplyTone: String = "hinglish", // formal | casual | hinglish
    val autoReplyLanguage: String = "auto",   // auto | hi | en
    val greetingTemplate: String = "Namaste! Main JARVIS hoon. Aapka message mil gaya hai — main aapko jaldi reply karunga.",
    val quietHoursEnabled: Boolean = false,
    val quietStart: Int = 22,
    val quietEnd: Int = 7,
    val maxPerChatPerDay: Int = 25,
    val maxPerHourTotal: Int = 60,
    val notifyEveryReply: Boolean = false,
    val ttsEnabled: Boolean = true,
    val ttsLanguage: String = "hi-IN",
    val ttsRate: Float = 1.0f,
    val serverRulesWarningAcknowledged: Boolean = false,
    val themeAmoled: Boolean = true,
    val lastBulkTemplate: String = "",
    val realtimeEnabled: Boolean = true,
)

/**
 * Keys are stored in EncryptedSharedPreferences (Android Keystore backed).
 * If the keystore is unavailable (rare, e.g. a broken OEM image) we degrade to a plain
 * preference file *and say so in Settings* — we never silently pretend storage is encrypted.
 */
class SettingsStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val app = context.applicationContext

    private val prefs: SharedPreferences
    private val encrypted: Boolean

    init {
        var p: SharedPreferences? = null
        var enc = false
        try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            p = EncryptedSharedPreferences.create(
                app, "jarvis_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            enc = true
            p.edit().putString("__selftest", "ok").apply()
            if (p.getString("__selftest", null) != "ok") enc = false
        } catch (_: Throwable) {
            p = null
        }
        if (p == null || !enc) {
            p = app.getSharedPreferences("jarvis_plain", Context.MODE_PRIVATE)
            enc = false
        }
        prefs = p
        encrypted = enc
    }

    val isEncrypted: Boolean get() = encrypted

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(raw)
        } catch (_: Exception) {
            AppSettings()
        }
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
        return next
    }

    fun replace(next: AppSettings) {
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
    }

    fun setRemote(baseUrl: String, apiKey: String, sessionId: String, sessionName: String, mode: AppMode) {
        update { it.copy(baseUrl = baseUrl.trimEnd('/'), apiKey = apiKey.trim(), sessionId = sessionId.trim(), sessionName = sessionName, mode = mode) }
    }

    /** For the "clear all data" action in Settings. */
    fun wipe() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private companion object {
        const val KEY_SETTINGS = "settings_v1"
    }
}
