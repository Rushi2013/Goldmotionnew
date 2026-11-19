package ai.jarvis.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Truthful delivery states. Never claim DELIVERED without an ack. */
enum class DeliveryState {
    DEMO_SIMULATED, QUEUED, ACCEPTED, DELIVERED, READ, FAILED, OUTCOME_UNKNOWN;

    val label: String
        get() = when (this) {
            DEMO_SIMULATED -> "DEMO"
            QUEUED -> "QUEUED"
            ACCEPTED -> "ACCEPTED"
            DELIVERED -> "DELIVERED"
            READ -> "READ"
            FAILED -> "FAILED"
            OUTCOME_UNKNOWN -> "OUTCOME UNKNOWN"
        }
}

enum class AppMode { DEMO, LIVE }

enum class AutoReplyMode { OFF, ALL, ALLOWLIST }

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** A single HTTP outcome from OpenWA, or a transport failure. */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T, val status: Int = 200) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): ApiResult<T> {
        if (this is Success) block(data)
        return this
    }

    fun valueOrNull(): T? = (this as? Success)?.data

    companion object {
        fun <T> ok(value: T, status: Int = 200): ApiResult<T> = Success(value, status)
    }
}

/** Normalised OpenWA error. `message` may be a JSON string OR array of strings – always flattened. */
data class ApiError(
    val status: Int,
    val message: String,
    val code: String? = null,
    val retryAfterSeconds: Int? = null,
    val rawBody: String = "",
    val transport: Boolean = false,
) {
    /** The one-line message the UI shows. */
    val oneLine: String get() = message.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "Unknown error ($status)" }

    val needsSessionStart: Boolean
        get() = status == 409 || message.contains("not active", true) || message.contains("not connected", true)

    val isUnsupported: Boolean
        get() = status == 501 || message.contains("not supported", true) || message.contains("unsupported", true)

    val isScopeProblem: Boolean get() = status == 403

    val isAuthProblem: Boolean get() = status == 401

    val headline: String
        get() = when {
            transport -> "Connection failed"
            status == 401 -> "API key rejected (401)"
            status == 403 -> "Scope denied (403)"
            status == 404 -> "Not found (404)"
            status == 409 -> "Engine not ready (409)"
            status == 413 -> "Payload too large (413)"
            status == 422 -> "Idempotency problem (422)"
            status == 429 -> "Rate limited (429)"
            status == 501 -> "Not supported on this engine (501)"
            status == 503 -> "Service unavailable (503)"
            status == 504 -> "Upstream timeout (504)"
            else -> "Error $status"
        }
}

object TimeFmt {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    fun time(epochMs: Long): String = timeFmt.format(Date(epochMs))
    fun dateTime(epochMs: Long): String = dateTimeFmt.format(Date(epochMs))
    fun iso(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))

    fun parseIsoOrEpoch(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { n -> return if (n < 10_000_000_000L) n * 1000 else n }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss",
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val d = f.parse(value) ?: continue
                return d.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /** OpenWA messages carry epoch SECONDS; entities carry ISO strings. */
    fun epochSecondsToMs(seconds: Long?): Long? = seconds?.takeIf { it > 0 }?.let {
        if (it < 10_000_000_000L) it * 1000 else it
    }
}

object Numbers {
    /** digits only, no plus/spaces/dashes/brackets. */
    fun digits(raw: String): String = raw.filter { it.isDigit() }

    fun mask(number: String): String {
        val d = digits(number)
        return if (d.length <= 4) "••••" else d.take(2) + "•".repeat((d.length - 5).coerceAtLeast(1)) + d.takeLast(3)
    }

    fun isGroupChatId(chatId: String): Boolean = chatId.endsWith("@g.us")

    fun chatIdToNumber(chatId: String): String =
        chatId.substringBefore('@').substringBefore('_')

    /**
     * Normalise a human-entered number into a WhatsApp E.164-style chat id.
     * Returns null when the number cannot be trusted (>15 digits, too short, ...).
     */
    fun toChatId(raw: String, defaultCountryCode: String = "91"): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains('@')) return s // already a chat id
        s = s.removePrefix("whatsapp:").removePrefix("tel:")
        var d = digits(s)
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.substring(2)
        if (s.trim().startsWith("+")) {
            // explicit international
        } else if (d.length == 10) {
            d = defaultCountryCode + d
        } else if (d.length == 11 && d.startsWith("0")) {
            d = defaultCountryCode + d.substring(1)
        }
        if (d.length !in 8..15) return null
        return "$d@c.us"
    }

    /** Loose validity used before calling /contacts/check. */
    fun looksPlausible(raw: String, defaultCountryCode: String = "91"): Boolean =
        toChatId(raw, defaultCountryCode) != null
}

object Ids {
    fun uuid(): String = java.util.UUID.randomUUID().toString()
    fun shortId(prefix: String = "local"): String = prefix + "_" + uuid().take(8)
    fun idemKey(vararg parts: String): String =
        sha256(parts.joinToString("|"))
    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
