package ai.jarvis.ai

import okhttp3.MediaType.Companion.toMediaType
import ai.jarvis.core.ApiResult
import ai.jarvis.data.LlmConfig
import ai.jarvis.data.api.HttpCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.TimeUnit

/** Honest provider health: a real call, real latency, real error. */
data class ProviderHealth(
    val ok: Boolean,
    val provider: String,
    val latencyMs: Long,
    val message: String,
    val model: String = "",
)

/** The one abstraction every model call goes through. */
interface LlmProvider {
    val id: String
    suspend fun healthCheck(): ProviderHealth
    suspend fun jsonCall(system: String, user: String, maxTokens: Int = 400, temperature: Double = 0.2): ApiResult<String>
    suspend fun textCall(system: String, user: String, maxTokens: Int = 300, temperature: Double = 0.5): ApiResult<String>
}

/** Deterministic offline fallback — not an LLM, and the UI labels it "Rules". */
class RuleBasedProvider : LlmProvider {
    override val id = "rules"
    override suspend fun healthCheck() = ProviderHealth(true, "rules", 0, "Offline rule engine — no model call")
    override suspend fun jsonCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        ApiResult.Failure(ai.jarvis.core.ApiError(0, "Rules provider cannot do free-form JSON; use RuleEngine"))
    override suspend fun textCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        ApiResult.Failure(ai.jarvis.core.ApiError(0, "Rules provider has no generative text"))
}

class NullProvider : LlmProvider {
    override val id = "none"
    override suspend fun healthCheck() = ProviderHealth(false, "none", 0, "No AI provider configured")
    override suspend fun jsonCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        ApiResult.Failure(ai.jarvis.core.ApiError(0, "No AI provider configured"))
    override suspend fun textCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        ApiResult.Failure(ai.jarvis.core.ApiError(0, "No AI provider configured"))
}

/**
 * OpenAI-compatible chat/completions: OpenAI, Groq, OpenRouter, Together, LM Studio, Ollama /v1.
 * Also serves Gemini through its OpenAI-compatible endpoint when the user picks base url ending /openai.
 */
class OpenAiCompatibleProvider(private val config: LlmConfig) : LlmProvider {
    override val id: String = "openai-compat"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun endpoint(): String {
        val base = config.baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    override suspend fun healthCheck(): ProviderHealth = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (config.apiKey.isBlank() && !config.baseUrl.contains("localhost") && !config.baseUrl.contains("10.0.2.2")) {
            return@withContext ProviderHealth(false, id, 0, "API key missing")
        }
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", "ping") }
            }
            put("max_tokens", 8)
        }.toString()
        val req = okhttp3.Request.Builder()
            .url(endpoint())
            .header("Content-Type", "application/json")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .post(body.toRequestBody())
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - started
                val warn = baseUrlWarning()?.let { " · $it" }.orEmpty()
                if (resp.isSuccessful) ProviderHealth(true, id, latency, "OK · ${config.model}$warn", config.model)
                else ProviderHealth(false, id, latency, "HTTP ${resp.code}: ${text.take(160)}$warn", config.model)
            }
        } catch (e: Exception) {
            ProviderHealth(false, id, System.currentTimeMillis() - started, e.message ?: "network error", config.model)
        }
    }

    private suspend fun chat(system: String, user: String, maxTokens: Int, temperature: Double): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", config.model)
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", system) }
                    addJsonObject { put("role", "user"); put("content", user) }
                }
                put("max_tokens", maxTokens)
                put("temperature", temperature)
            }.toString()
            val req = okhttp3.Request.Builder()
                .url(endpoint())
                .header("Content-Type", "application/json")
                .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
                .post(body.toRequestBody())
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext ApiResult.Failure(ai.jarvis.core.ApiError(resp.code, "LLM HTTP ${resp.code}: ${text.take(200)}"))
                    }
                    val root = json.parseToJsonElement(text).jsonObject
                    val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    if (content == null) ApiResult.Failure(ai.jarvis.core.ApiError(resp.code, "LLM response had no content"))
                    else ApiResult.Success(content)
                }
            } catch (e: Exception) {
                ApiResult.Failure(ai.jarvis.core.ApiError(0, "LLM network error: ${e.message}", transport = true))
            }
        }

    override suspend fun jsonCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        chat(system, user, maxTokens, temperature)

    override suspend fun textCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        chat(system, user, maxTokens, temperature)

    private fun String.toRequestBody(): okhttp3.RequestBody =
        "application/json; charset=utf-8".toMediaType().let { mt -> okhttp3.RequestBody.create(mt, this) }
}

/** Google Gemini generateContent with JSON mime type. */
class GeminiProvider(private val config: LlmConfig) : LlmProvider {
    override val id = "gemini"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    /**
     * Gemini endpoints are `{base}/models/{model}:generateContent`.
     *
     * The Base URL field is shared with the OpenAI-compatible provider, so a user who switches
     * the provider chip to "gemini" still has `https://api.openai.com/v1` sitting in the field.
     * Posting a Gemini request there returns `HTTP 403 PERMISSION_DENIED`. We therefore ignore
     * any non-Google base URL and fall back to the public Gemini host instead of failing.
     */
    private fun url(): String {
        val model = config.model.trim().ifBlank { "gemini-1.5-flash" }
        val raw = config.baseUrl.trim().trimEnd('/')
        val base = when {
            raw.contains("generativelanguage.googleapis.com") -> raw
            // OpenAI-shaped or empty base URL under the Gemini provider → use the real host.
            else -> "https://generativelanguage.googleapis.com/v1beta"
        }.let { if (it.endsWith("/v1") || it.endsWith("/v1beta")) it else "$it/v1beta" }
        val safeModel = model.removePrefix("models/")
        return "$base/models/$safeModel:generateContent?key=${config.apiKey.trim()}"
    }

    /** Human-readable hint shown next to the Test LLM result. */
    private fun baseUrlWarning(): String? {
        val raw = config.baseUrl.trim()
        if (raw.isBlank() || raw.contains("generativelanguage.googleapis.com")) return null
        return "Base URL '$raw' Gemini endpoint nahi hai — ${url().substringBefore('?')} use kiya ja raha hai."
    }

    override suspend fun healthCheck(): ProviderHealth = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext ProviderHealth(false, id, 0, "Gemini key missing")
        val started = System.currentTimeMillis()
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject { putJsonArray("parts") { addJsonObject { put("text", "ping") } } }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", 8) }
        }.toString()
        try {
            client.newCall(
                okhttp3.Request.Builder().url(url()).header("Content-Type", "application/json").post(body.toRequestBody()).build()
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - started
                val warn = baseUrlWarning()?.let { " · $it" }.orEmpty()
                if (resp.isSuccessful) ProviderHealth(true, id, latency, "OK · ${config.model}$warn", config.model)
                else ProviderHealth(false, id, latency, "HTTP ${resp.code}: ${text.take(160)}$warn", config.model)
            }
        } catch (e: Exception) {
            ProviderHealth(false, id, System.currentTimeMillis() - started, e.message ?: "network error", config.model)
        }
    }

    private suspend fun generate(system: String, user: String, maxTokens: Int, temperature: Double): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) return@withContext ApiResult.Failure(ai.jarvis.core.ApiError(0, "Gemini key missing"))
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                putJsonArray("contents") {
                    addJsonObject { putJsonArray("parts") { addJsonObject { put("text", user) } } }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", maxTokens)
                    put("temperature", temperature)
                    put("responseMimeType", "application/json")
                }
            }.toString()
            try {
                client.newCall(
                    okhttp3.Request.Builder().url(url()).header("Content-Type", "application/json").post(body.toRequestBody()).build()
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Failure(ai.jarvis.core.ApiError(resp.code, "Gemini HTTP ${resp.code}: ${text.take(200)}"))
                    val root = json.parseToJsonElement(text).jsonObject
                    val content = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content
                    if (content == null) ApiResult.Failure(ai.jarvis.core.ApiError(resp.code, "Gemini returned no content"))
                    else ApiResult.Success(content)
                }
            } catch (e: Exception) {
                ApiResult.Failure(ai.jarvis.core.ApiError(0, "Gemini network error: ${e.message}", transport = true))
            }
        }

    override suspend fun jsonCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        generate(system, user, maxTokens, temperature)
    override suspend fun textCall(system: String, user: String, maxTokens: Int, temperature: Double) =
        generate(system, user, maxTokens, temperature)

    private fun String.toRequestBody(): okhttp3.RequestBody =
        "application/json; charset=utf-8".toMediaType().let { mt -> okhttp3.RequestBody.create(mt, this) }
}

/** Chooses the best available provider and remembers the last health result. */
class AiGateway(
    private val configProvider: () -> LlmConfig,
    @Suppress("unused") private val http: HttpCore,
) {
    @Volatile private var cachedHealth: ProviderHealth? = null

    fun rules(): LlmProvider = RuleBasedProvider()

    fun current(): LlmProvider {
        val cfg = configProvider()
        return when {
            cfg.provider == "gemini" && cfg.apiKey.isNotBlank() -> GeminiProvider(cfg)
            (cfg.provider == "openai-compat" || cfg.provider == "ollama") && cfg.baseUrl.isNotBlank() -> OpenAiCompatibleProvider(cfg)
            else -> NullProvider()
        }
    }

    fun lastHealth(): ProviderHealth? = cachedHealth

    suspend fun verify(): ProviderHealth {
        val provider = current()
        val health = provider.healthCheck()
        cachedHealth = health
        return health
    }

    /** True only when a real model answered. Never claimed without a successful call. */
    suspend fun isLiveModelAvailable(): Boolean {
        val h = verify()
        return h.ok && h.provider != "none" && h.provider != "rules"
    }
}
