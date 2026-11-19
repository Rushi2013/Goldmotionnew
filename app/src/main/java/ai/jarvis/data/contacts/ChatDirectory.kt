package ai.jarvis.data.contacts

import ai.jarvis.core.ApiResult
import ai.jarvis.data.api.ChatSummaryDto
import ai.jarvis.data.api.ContactDto
import ai.jarvis.data.api.OpenWaApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory directory that answers "what is this chat called?".
 *
 * The thread screen used to print `chatId.substringBefore('@')`, which is how a saved contact
 * ended up shown as the bare number `773538363963836`. WhatsApp's own directory has three
 * different labels for the same JID (saved name, push name, formatted number) and the gateway
 * exposes them in *two* different collections (`/chats` and `/contacts`). This class merges both
 * and resolves, in order:
 *
 *   1. saved contact name        (Contacts → the name the user gave the number)
 *   2. chat display name         (Chats → `name`, group subjects live here)
 *   3. push name                 (what the person set for themselves)
 *   4. formatted number          (`+91 98765 43210` instead of a 13-digit blob)
 *
 * Everything is held in RAM only — this is deliberate. The user explicitly asked that WhatsApp
 * data must not be stored on the device, so nothing here is written to disk or to Room.
 */
class ChatDirectory(
    private val api: OpenWaApi,
    private val sessionIdProvider: () -> String,
    private val isLiveProvider: () -> Boolean,
) {
    private val mutex = Mutex()
    private var chats: List<ChatSummaryDto> = emptyList()
    private var contacts: List<ContactDto> = emptyList()
    private var loadedAtMs: Long = 0

    /** Merged view used by the Chats screen: every chat, but with a better name when we have one. */
    fun enrichedChats(): List<ChatSummaryDto> = chats.map { chat ->
        val better = nameFor(chat.id)
        if (better.isNullOrBlank() || better == displayFallback(chat.id)) chat
        else chat.copy(name = better)
    }

    fun rawChats(): List<ChatSummaryDto> = chats

    fun chat(chatId: String): ChatSummaryDto? = chats.firstOrNull { it.id == chatId }

    /** Saved name → push name → formatted number. Never returns the raw JID digits when avoidable. */
    fun nameFor(chatId: String): String? {
        chats.firstOrNull { it.id == chatId }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val contact = contactFor(chatId)
        contact?.name?.takeIf { it.isNotBlank() }?.let { return it }
        contact?.pushName?.takeIf { it.isNotBlank() }?.let { return it }
        chat(chatId)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return prettyNumber(chatId)
    }

    fun contactFor(chatId: String): ContactDto? {
        val digits = digitsOf(chatId)
        if (digits.isBlank()) return null
        // Contact ids are "<number>@c.us" — tolerate a lid/group JID by comparing digits only.
        return contacts.firstOrNull { digitsOf(it.id) == digits }
            ?: contacts.firstOrNull { digitsOf(it.number) == digits }
    }

    private val picCache = mutableMapOf<String, String?>()

    fun profilePicFor(chatId: String): String? = picCache[chatId]

    /**
     * Ask the gateway for a profile picture when the contact list did not carry one.
     * Only the resulting URL is cached (in RAM); the image bytes are Coil's business and are
     * never persisted by this app.
     */
    suspend fun fetchProfilePic(chatId: String): String? {
        picCache[chatId]?.let { return it }
        contactFor(chatId)?.profilePicUrl?.takeIf { it.isNotBlank() }?.let {
            picCache[chatId] = it
            return it
        }
        if (!isLiveProvider()) return null
        val sid = sessionIdProvider().takeIf { it.isNotBlank() } ?: return null
        return when (val res = api.contactProfilePicture(sid, chatId)) {
            is ApiResult.Success -> {
                val el = res.data
                val url = when (el) {
                    is kotlinx.serialization.json.JsonObject -> {
                        val obj = el["data"] as? kotlinx.serialization.json.JsonObject ?: el
                        (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: (obj["profilePicUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: (obj["picture"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    is kotlinx.serialization.json.JsonPrimitive -> el.content
                    else -> null
                }
                picCache[chatId] = url
                url
            }
            is ApiResult.Failure -> {
                picCache[chatId] = null
                null
            }
        }
    }

    fun isGroup(chatId: String): Boolean =
        chatId.endsWith("@g.us", true) || chat(chatId)?.isGroup == true

    /** Refresh from the gateway. Cached for [TTL_MS] unless [force]. */
    suspend fun refresh(force: Boolean = false): ApiResult<Unit> {
        if (!isLiveProvider()) return ApiResult.Success(Unit)
        val sid = sessionIdProvider().takeIf { it.isNotBlank() }
            ?: return ApiResult.Failure(ai.jarvis.core.ApiError(0, "Session id set nahi hai"))
        if (!force && chats.isNotEmpty() && System.currentTimeMillis() - loadedAtMs < TTL_MS) {
            return ApiResult.Success(Unit)
        }
        return mutex.withLock {
            if (!force && chats.isNotEmpty() && System.currentTimeMillis() - loadedAtMs < TTL_MS) {
                return@withLock ApiResult.Success(Unit)
            }
            val chatsRes = api.sessionChats(sid, limit = 500)
            if (chatsRes is ApiResult.Failure) return@withLock chatsRes
            chats = (chatsRes as ApiResult.Success).data
            // Contacts are optional: a gateway without the route must not break the chats list.
            when (val res = api.listContacts(sid, limit = 2000)) {
                is ApiResult.Success -> contacts = res.data
                is ApiResult.Failure -> Unit
            }
            loadedAtMs = System.currentTimeMillis()
            ApiResult.Success(Unit)
        }
    }

    fun invalidate() {
        loadedAtMs = 0
    }

    companion object {
        private const val TTL_MS = 60_000L

        fun digitsOf(raw: String): String = raw.substringBefore('@').filter { it.isDigit() }

        /** `773538363963836@c.us` → `+91 77353 83639` (or the best grouping we can infer). */
        fun prettyNumber(chatId: String): String? {
            val digits = digitsOf(chatId)
            if (digits.length < 8) return null
            val withCc = if (digits.length == 10) "91$digits" else digits
            if (withCc.length < 11) return "+$withCc"
            val cc = withCc.dropLast(10)
            val rest = withCc.takeLast(10)
            val grouped = rest.chunked(5).joinToString(" ")
            return "+$cc $grouped"
        }

        fun displayFallback(chatId: String): String =
            prettyNumber(chatId) ?: chatId.substringBefore('@')
    }
}
