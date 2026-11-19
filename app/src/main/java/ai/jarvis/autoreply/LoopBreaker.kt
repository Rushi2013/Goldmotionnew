package ai.jarvis.autoreply

import java.util.concurrent.ConcurrentHashMap

/**
 * Loop / abuse protection (spec §7.10). Pure in-memory logic so it is unit-testable
 * and survives screen changes but resets on app restart (documented behaviour).
 */
class LoopBreaker(
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private data class ChatState(
        val autoReplyTimestamps: ArrayDeque<Long> = ArrayDeque(),
        var blockedUntil: Long = 0,
        var lastInboundText: String = "",
        var identicalInboundStreak: Int = 0,
    )

    private val states = ConcurrentHashMap<String, ChatState>()

    data class Verdict(val allowed: Boolean, val reason: String = "")

    fun check(chatId: String, inboundText: String, messageId: String? = null): Verdict {
        val now = nowProvider()
        val st = states.getOrPut(chatId) { ChatState() }
        synchronized(st) {
            if (now < st.blockedUntil) {
                val mins = ((st.blockedUntil - now) / 60_000) + 1
                return Verdict(false, "Loop breaker active is chat par (~$mins min)")
            }
            // identical repeated text (>3 times) looks like another bot
            val normalized = inboundText.trim().lowercase()
            if (normalized.isNotEmpty() && normalized == st.lastInboundText) {
                st.identicalInboundStreak++
            } else {
                st.lastInboundText = normalized
                st.identicalInboundStreak = 1
            }
            if (st.identicalInboundStreak >= 4) {
                st.blockedUntil = now + 6 * 3600_000L
                return Verdict(false, "Bot jaisa repeated text — 6 ghante ke liye band")
            }
            // ≥3 auto-replies in 90 s to the same chat
            while (st.autoReplyTimestamps.isNotEmpty() && now - st.autoReplyTimestamps.first() > 90_000) {
                st.autoReplyTimestamps.removeFirst()
            }
            if (st.autoReplyTimestamps.size >= 3) {
                st.blockedUntil = now + 6 * 3600_000L
                return Verdict(false, "90s me 3 auto-reply ho chuke — 6 ghante ke liye band")
            }
            return Verdict(true)
        }
    }

    fun recordAutoReply(chatId: String) {
        val st = states.getOrPut(chatId) { ChatState() }
        synchronized(st) { st.autoReplyTimestamps.addLast(nowProvider()) }
    }

    fun reset(chatId: String? = null) {
        if (chatId == null) states.clear() else states.remove(chatId)
    }

    fun isBlocked(chatId: String): Boolean {
        val st = states[chatId] ?: return false
        return nowProvider() < st.blockedUntil
    }
}

/** Per-chat cooldown + daily/hourly caps (spec §7.10, items 3). */
class ReplyRateLimiter(
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val lastReplyAt = ConcurrentHashMap<String, Long>()

    data class Verdict(val allowed: Boolean, val reason: String = "")

    fun check(
        chatId: String,
        autoSentToday: Int,
        autoSentThisHour: Int,
        perChatPerDay: Int,
        perHourTotal: Int,
        cooldownSeconds: Int = 60,
    ): Verdict {
        val now = nowProvider()
        val last = lastReplyAt[chatId] ?: 0L
        if (now - last < cooldownSeconds * 1000L) {
            val wait = ((cooldownSeconds * 1000L - (now - last)) / 1000) + 1
            return Verdict(false, "Cooldown: ${wait}s baaki")
        }
        if (autoSentToday >= perChatPerDay) return Verdict(false, "Per-chat daily cap ($perChatPerDay) ho gaya")
        if (autoSentThisHour >= perHourTotal) return Verdict(false, "Hourly cap ($perHourTotal) ho gaya")
        return Verdict(true)
    }

    fun record(chatId: String) {
        lastReplyAt[chatId] = nowProvider()
    }

    fun reset() = lastReplyAt.clear()
}

/** Quiet hours: reply is withheld (queued as "after hours") rather than sent at 3 am. */
object QuietHours {
    fun isQuiet(nowHour: Int, startHour: Int, endHour: Int): Boolean {
        if (startHour == endHour) return false
        return if (startHour < endHour) nowHour in startHour until endHour
        else nowHour >= startHour || nowHour < endHour
    }
}

/** Dedupe by sessionId+messageId and content hash; backed by Room's unique index. */
object DedupeKey {
    fun of(sessionId: String, messageId: String, text: String): String =
        "$sessionId|$messageId|${text.hashCode()}"
}
