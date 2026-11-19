package ai.jarvis.data.api

import ai.jarvis.core.ApiError
import ai.jarvis.core.ApiResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Raw HTTP outcome, before DTO decoding. Keeps the app able to call ANY OpenWA route. */
@Serializable
data class ApiOutcome(
    val httpStatus: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = httpStatus in 200..299
    val isNoContent: Boolean get() = httpStatus == 204 || body.isBlank()
}

/**
 * Thin OkHttp layer shared by the typed @OpenWaApi surface and the generic "call any path"
 * Dev-Tools console. One place for: auth header, idempotency keys, timeouts, redaction,
 * and the string-or-array `message` error unwrapping that OpenWA 0.30.3 returns.
 */
class HttpCore(
    baseUrlProvider: () -> String,
    apiKeyProvider: () -> String,
) {
    private val baseUrlProvider = baseUrlProvider
    private val apiKeyProvider = apiKeyProvider

    private fun baseUrl(): String = baseUrlProvider()
    private fun apiKey(): String = apiKeyProvider()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // session start can take 10-60 s
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun buildUrl(path: String, query: Map<String, String?> = emptyMap()): String {
        val base = baseUrl().trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        val qs = query.entries
            .mapNotNull { e -> e.value?.takeIf { it.isNotBlank() }?.let { v -> "${enc(e.key)}=${enc(v)}" } }
            .joinToString("&")
        return if (qs.isEmpty()) base + p else base + p + "?" + qs
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun newRequest(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        needsAuth: Boolean = true,
    ): Request {
        val builder = Request.Builder()
            .url(buildUrl(path, query))
            .header("Accept", "application/json")
        if (needsAuth) {
            val key = apiKey().trim()
            if (key.isNotEmpty()) builder.header("X-API-Key", key)
        }
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val requestBody: RequestBody? = when {
            body != null -> body.toRequestBody(jsonMedia)
            method in setOf("POST", "PUT", "PATCH", "DELETE") -> "".toRequestBody(jsonMedia)
            else -> null
        }
        builder.method(method.uppercase(), requestBody)
        return builder.build()
    }

    suspend fun executeRaw(request: Request): ApiResult<ApiOutcome> {
        val call = client.newCall(request)
        return try {
            val response = call.await()
            val text = response.body?.string().orEmpty()
            val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
            val outcome = ApiOutcome(response.code, text, headers)
            if (outcome.isSuccessful) ApiResult.Success(outcome)
            else ApiResult.Failure(parseError(response.code, text, headers))
        } catch (io: IOException) {
            ApiResult.Failure(
                ApiError(
                    status = 0,
                    message = "Network error: ${io.message ?: io::class.java.simpleName}",
                    transport = true,
                )
            )
        }
    }

    /**
     * Call `method path` and decode the JSON body into T with kotlinx.serialization.
     * `204`/empty bodies yield `null` as T? — callers use `ApiResult<Unit>` semantics via [executeUnit].
     */
    suspend inline fun <reified T> call(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResult<T> = decode(callRaw(method, path, body, query, idempotencyKey, extraHeaders))

    @PublishedApi
    internal suspend fun callRaw(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResult<ApiOutcome> = executeRaw(newRequest(method, path, body, query, idempotencyKey, extraHeaders))

    @PublishedApi
    internal inline fun <reified T> decode(result: ApiResult<ApiOutcome>): ApiResult<T> = when (result) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> {
            val outcome = result.data
            try {
                val decoded: T = if (outcome.body.isBlank() && T::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    Unit as T
                } else {
                    json.decodeFromString<T>(outcome.body)
                }
                ApiResult.Success(decoded, outcome.httpStatus)
            } catch (e: Exception) {
                ApiResult.Failure(
                    ApiError(
                        status = outcome.httpStatus,
                        message = "Unexpected response shape: ${e.message?.take(200)}",
                        rawBody = outcome.body.take(2000),
                    )
                )
            }
        }
    }

    /** For 204-style routes: success carries the raw outcome. */
    suspend fun execute(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResult<ApiOutcome> = executeRaw(newRequest(method, path, body, query, idempotencyKey, extraHeaders))

    /**
     * OpenWA error bodies look like:
     *   {"statusCode":400,"message":"...","error":"Bad Request"}
     *   {"statusCode":400,"message":["property foo should not exist"],"error":"Bad Request"}
     * `message` may be a string OR an array — decode with JsonElement and flatten.
     */
    fun parseError(status: Int, body: String, headers: Map<String, String> = emptyMap()): ApiError {
        val retryAfter = headers["Retry-After"]?.toIntOrNull()
        if (body.isBlank()) {
            return ApiError(status, defaultMessage(status), retryAfterSeconds = retryAfter, rawBody = "")
        }
        return try {
            val el = json.parseToJsonElement(body)
            val obj = el as? kotlinx.serialization.json.JsonObject
            val messageEl = obj?.get("message")
            val text = when (messageEl) {
                is kotlinx.serialization.json.JsonPrimitive -> messageEl.content
                is kotlinx.serialization.json.JsonArray -> messageEl.joinToString("; ") {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
                }
                else -> null
            }
            val code = (obj?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val retry = (obj?.get("retryAfterSeconds") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ApiError(
                status = status,
                message = text?.takeIf { it.isNotBlank() } ?: defaultMessage(status),
                code = code,
                retryAfterSeconds = retry ?: retryAfter,
                rawBody = body.take(4000),
            )
        } catch (_: Exception) {
            ApiError(status, body.take(300), retryAfterSeconds = retryAfter, rawBody = body.take(4000))
        }
    }

    private fun defaultMessage(status: Int) = when (status) {
        400 -> "Bad request"
        401 -> "API key missing, invalid or revoked"
        403 -> "Key role/scope does not allow this action"
        404 -> "Not found"
        409 -> "Session is not connected. The WhatsApp client is not ready."
        413 -> "Media too large (body limit 25 MiB)"
        422 -> "Idempotency key reused"
        429 -> "Rate limited"
        500 -> "Engine page error"
        501 -> "This route is not supported by the active engine"
        503 -> "Service unavailable"
        504 -> "Upstream timeout"
        else -> "HTTP $status"
    }

    internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
