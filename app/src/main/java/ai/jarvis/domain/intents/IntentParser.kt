package ai.jarvis.domain.intents

import ai.jarvis.core.ApiResult
import ai.jarvis.ai.AiGateway
import ai.jarvis.ai.Prompts
import ai.jarvis.domain.policy.RuleClassifier
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Date

enum class IntentName {
    SEND_WHATSAPP_MESSAGE, WHATSAPP_SESSION_STATUS, GENERAL_QUESTION, CALCULATE,
    GET_TIME, GET_DATE, OPEN_APP, CREATE_REMINDER, LIST_REMINDERS, CANCEL_REMINDER,
    READ_INFO, CONFIRM_YES, CONFIRM_NO, EDIT_FIELD, CANCEL, SMALL_TALK, UNKNOWN,
    AUTOREPLY_ON, AUTOREPLY_OFF, AUTOREPLY_FOR_CONTACT_ON, AUTOREPLY_FOR_CONTACT_OFF,
    AUTOREPLY_STATUS, SEND_BULK, SEND_STATUS_UPDATE, OPEN_CHAT, READ_UNREAD_MESSAGES,
    REPLY_TO_LAST, APPROVE_ESCALATION, LIST_ESCALATIONS, SEND_MEDIA_TO_CHAT,
    CREATE_POLL, SHARE_LOCATION, MUTE_CHAT, BLOCK_CONTACT, LINK_SESSION,
}

enum class AutoReplyScope { GLOBAL, CONTACT, OFF, STATUS }

data class Slots(
    val recipientName: String? = null,
    val recipientPhoneHint: String? = null,
    val message: String? = null,
    val autoReplyScope: AutoReplyScope? = null,
    val messageType: String? = null,
    val recipientSet: String? = null,
    val pollOptions: List<String> = emptyList(),
    val chatName: String? = null,
    val reminderText: String? = null,
    val reminderAtEpochMs: Long? = null,
    val expression: String? = null,
    val query: String? = null,
)

data class IntentResult(
    val intent: IntentName,
    val slots: Slots = Slots(),
    val confidence: Double = 0.0,
    val requiresConfirmation: Boolean = true,
    val risk: ai.jarvis.core.RiskLevel = ai.jarvis.core.RiskLevel.MEDIUM,
    val missingSlots: List<String> = emptyList(),
    val ambiguity: String? = null,
    val language: String = "unknown",
    val explanation: String = "",
    val source: String = "rules",
    val clarifiedQuestion: String? = null,
)

/**
 * Deterministic parser for Hinglish/English/Devanagari commands.
 *
 * Ordering (important): auto-reply commands and bulk commands are matched BEFORE the generic
 * "send X to Y" pattern, otherwise "sabko good morning bhej do" would look like a single send.
 */
class IntentParser(private val gateway: AiGateway? = null) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val honorifics = listOf("ji", "mr", "mrs", "ms", "shri", "sri", "bhai", "didi", "sir", "madam", "ko", "ke", "ka", "ki")

    /** Devanagari command words → Roman so one set of rules serves all three languages. */
    private fun normalizeCommands(input: String): String {
        if (input.none { it.code in 0x0900..0x097F }) return input
        var s = input
        val map = listOf(
            "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp",
            "भेजो" to "bhej", "भेज" to "bhej", "को" to "ko", "के लिए" to "ke liye",
            "मैसेज" to "message", "संदेश" to "message", "लिख" to "likh", "करो" to "kar do",
            "चालू" to "chalu", "बंद" to "band", "ऑटो" to "auto", "रिप्लाई" to "reply",
            "पर" to "par", "से" to "se", "और" to "aur", "सबको" to "sabko", "मुझे" to "mujhe",
            "कितने" to "kitne", "बजे" to "baje", "कल" to "kal", "आज" to "aaj",
        )
        map.forEach { (k, v) -> s = s.replace(k, v) }
        return s
    }

    fun parse(raw: String, pending: PendingAction? = null): IntentResult {
        val text = normalizeCommands(raw.trim())
        if (text.isEmpty()) return unknown("Empty input")
        val lower = text.lowercase()
        val lang = RuleClassifier.languageOf(text)

        // --- confirmations against a pending action
        if (pending != null) {
            if (matchesAny(lower, listOf("haan", "han", "yes", "yep", "ok", "okay", "theek hai", "bhej do", "send it", "confirm", "kar do", "जी हाँ", "hanji"))) {
                return IntentResult(IntentName.CONFIRM_YES, confidence = 0.95, requiresConfirmation = false, language = lang, source = "rules", explanation = "Pending action confirm")
            }
            if (matchesAny(lower, listOf("nahi", "nahin", "no", "cancel", "mat bhejo", "dont", "don't", "नहीं", "रहने दो", "cancel karo"))) {
                return IntentResult(IntentName.CONFIRM_NO, confidence = 0.95, requiresConfirmation = false, language = lang, source = "rules")
            }
        }

        // --- 1. Auto-reply controls
        val autoReply = parseAutoReply(lower, text, lang)
        if (autoReply != null) return autoReply

        // --- 2. Bulk
        val bulk = parseBulk(lower, text, lang)
        if (bulk != null) return bulk

        // --- 3. Escalations
        if (matchesAny(lower, listOf("escalation", "approval", "kya approve", "pending reply", "approve karna"))) {
            return IntentResult(IntentName.LIST_ESCALATIONS, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }
        if (matchesAny(lower, listOf("approve kar", "approve first", "haan approve", "bhej do usko")) && lower.contains("escalation")) {
            return IntentResult(IntentName.APPROVE_ESCALATION, confidence = 0.85, language = lang, source = "rules")
        }

        // --- 4. Session status / link
        if (matchesAny(lower, listOf("session status", "whatsapp status", "connected hai", "qr", "link session", "session link", "whatsapp connect"))) {
            return if (matchesAny(lower, listOf("link", "connect", "qr", "jodo"))) {
                IntentResult(IntentName.LINK_SESSION, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
            } else {
                IntentResult(IntentName.WHATSAPP_SESSION_STATUS, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
            }
        }

        // --- 5. Time / date
        if (Regex("""(?i)\b(kitne baje|kya time|time kya|samay|what time|current time)\b""").containsMatchIn(text)) {
            return IntentResult(IntentName.GET_TIME, confidence = 0.95, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }
        if (Regex("""(?i)\b(aaj kaunsi tarikh|aaj ki taarikh|date kya|what date|aaj ka din|kya din hai)\b""").containsMatchIn(text)) {
            return IntentResult(IntentName.GET_DATE, confidence = 0.95, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }

        // --- 6. Calculator (only pure arithmetic; never eval)
        val calc = Calculator.extractExpression(text)
        if (calc != null) {
            return IntentResult(
                IntentName.CALCULATE, slots = Slots(expression = calc), confidence = 0.95,
                requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules",
            )
        }

        // --- 7. Reminders
        val reminder = parseReminder(lower, text, lang)
        if (reminder != null) return reminder
        if (matchesAny(lower, listOf("reminder list", "mere reminders", "list reminders", "reminders dikhao"))) {
            return IntentResult(IntentName.LIST_REMINDERS, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }

        // --- 8. Chats / unread / open
        if (matchesAny(lower, listOf("unread", "unread messages", "naye message", "padho messages"))) {
            return IntentResult(IntentName.READ_UNREAD_MESSAGES, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }
        val openChat = Regex("""(?i)\b([\p{L}\s]{2,30})\s+(ka|ki)\s+chat\s+(kholo|open|dikhao)\b""").find(text)
        if (openChat != null) {
            return IntentResult(
                IntentName.OPEN_CHAT,
                slots = Slots(chatName = openChat.groupValues[1].trim()),
                confidence = 0.88, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules",
            )
        }

        // --- 9. Poll
        val poll = Regex("""(?i)poll\s*(banao|create|bana)?\s*[:\-]?\s*(.+)""").find(text)
        if (poll != null) {
            val rest = poll.groupValues[2]
            val options = rest.split(Regex("""\s+(?:ya|or|,|/)\s+""")).map { it.trim() }.filter { it.isNotBlank() }
            return IntentResult(
                IntentName.CREATE_POLL,
                slots = Slots(message = rest.trim(), pollOptions = options),
                confidence = if (options.size >= 2) 0.85 else 0.6,
                risk = ai.jarvis.core.RiskLevel.HIGH, language = lang, source = "rules",
                clarifiedQuestion = if (options.size < 2) "Poll ke 2 options batao" else null,
            )
        }

        // --- 10. Location
        val loc = Regex("""(?i)([\p{L}\s]{2,30})\s+(?:ko|ke liye)\s+location\s+(?:bhej|share|send)""").find(text)
        if (loc != null) {
            return IntentResult(
                IntentName.SHARE_LOCATION, slots = Slots(recipientName = loc.groupValues[1].trim()),
                confidence = 0.85, risk = ai.jarvis.core.RiskLevel.HIGH, language = lang, source = "rules",
            )
        }

        // --- 11. Mute
        val mute = Regex("""(?i)([\p{L}\s]{2,30})\s+(?:ka|ki|ko)\s+(?:chat\s+)?mute""").find(text)
        if (mute != null) {
            return IntentResult(IntentName.MUTE_CHAT, slots = Slots(chatName = mute.groupValues[1].trim()), confidence = 0.85, risk = ai.jarvis.core.RiskLevel.MEDIUM, language = lang, source = "rules")
        }

        // --- 12. Send message (the classic path)
        val send = parseSend(lower, text, lang)
        if (send != null) return send

        // --- 13. Small talk / greetings to the assistant
        if (matchesAny(lower, listOf("hello jarvis", "hi jarvis", "hey jarvis", "jarvis suno", "kaise ho jarvis"))) {
            return IntentResult(IntentName.SMALL_TALK, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }

        // --- 14. Fallback
        return IntentResult(
            intent = IntentName.UNKNOWN, confidence = 0.3, requiresConfirmation = false,
            risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules",
            clarifiedQuestion = "Samajh nahi aaya. Jaise: \"Pramila ko hi bhej\" ya \"auto reply on\".",
        )
    }

    private fun parseAutoReply(lower: String, original: String, lang: String): IntentResult? {
        val hasAuto = lower.contains("auto reply") || lower.contains("autoreply") || lower.contains("auto-reply") ||
            lower.contains("autopilot")
        val hasOn = matchesAny(lower, listOf("on", "chalu", "start", "shuru", "enable", "kar do", "karo", "do", "सक्रिय"))
        val hasOff = matchesAny(lower, listOf("off", "band", "stop", "disable", "rok", "बंद"))
        val hasStatus = matchesAny(lower, listOf("status", "kaun", "kitne", "list", "dikhao", "batao"))

        if (hasAuto && hasStatus) {
            return IntentResult(IntentName.AUTOREPLY_STATUS, confidence = 0.9, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules")
        }
        if (!hasAuto && !(lower.contains("sabko") && (hasOn || hasOff))) {
            // "sabko auto reply do" is covered above; only auto-reply phrases reach here
            if (!lower.contains("sab band")) return null
        }

        val target = contactTarget(original)
        return when {
            hasOff && target != null -> IntentResult(
                IntentName.AUTOREPLY_FOR_CONTACT_OFF,
                slots = Slots(recipientName = target, autoReplyScope = AutoReplyScope.CONTACT),
                confidence = 0.88, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.LOW, language = lang, source = "rules",
            )
            hasOff -> IntentResult(
                IntentName.AUTOREPLY_OFF, slots = Slots(autoReplyScope = AutoReplyScope.OFF),
                confidence = 0.92, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.MEDIUM, language = lang, source = "rules",
            )
            target != null && hasOn -> IntentResult(
                IntentName.AUTOREPLY_FOR_CONTACT_ON,
                slots = Slots(recipientName = target, autoReplyScope = AutoReplyScope.CONTACT),
                confidence = 0.88, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.MEDIUM, language = lang, source = "rules",
            )
            hasOn -> IntentResult(
                IntentName.AUTOREPLY_ON, slots = Slots(autoReplyScope = AutoReplyScope.GLOBAL),
                confidence = 0.92, requiresConfirmation = false, risk = ai.jarvis.core.RiskLevel.MEDIUM, language = lang, source = "rules",
            )
            else -> null
        }
    }

    private fun parseBulk(lower: String, original: String, lang: String): IntentResult? {
        val hasBulkVerb = matchesAny(lower, listOf("sabko", "sab log", "everyone", "all contacts", "sabhi ko", "sab ke", "broadcast"))
        val hasSend = matchesAny(lower, listOf("bhej", "send", "broadcast", "blast"))
        if (!hasBulkVerb || !hasSend) return null

        // Extract the text after the recipient-set word
        val textMatch = Regex("""(?i)(?:sabko|sab log(?:o|on)? ko|sabhi ko|everyone|all contacts)[\s,]*(.*?)(?:bhej do|bhejdo|bhejo|send kar do|send karo|send|broadcast karo|broadcast)?$""").find(original)
        val message = textMatch?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'', ',', '.')?.takeIf { it.isNotBlank() }
        val messageType = when {
            lower.contains("image") || lower.contains("photo") || lower.contains("tasveer") -> "IMAGE"
            lower.contains("video") -> "VIDEO"
            lower.contains("audio") || lower.contains("voice") -> "AUDIO"
            lower.contains("document") || lower.contains("pdf") -> "DOCUMENT"
            else -> "TEXT"
        }
        return IntentResult(
            IntentName.SEND_BULK,
            slots = Slots(message = message, messageType = messageType, recipientSet = "ALL_CONTACTS"),
            confidence = if (message != null) 0.85 else 0.6,
            requiresConfirmation = true,
            risk = ai.jarvis.core.RiskLevel.HIGH,
            missingSlots = if (message == null) listOf("message") else emptyList(),
            language = lang, source = "rules",
            clarifiedQuestion = if (message == null) "Sabko kya bhejna hai?" else null,
        )
    }

    private fun parseReminder(lower: String, original: String, lang: String): IntentResult? {
        if (!matchesAny(lower, listOf("reminder", "yaad dila", "remind me", "yaad dilana"))) return null
        val timeRegexes = listOf(
            Regex("""(?i)(?:kal|tomorrow)\s*(?:subah|morning)?\s*(\d{1,2})(?::(\d{2}))?\s*(?:baje|am|pm)?"""),
            Regex("""(?i)(?:aaj|today)\s*(?:subah|morning|shaam|evening|raat)?\s*(\d{1,2})(?::(\d{2}))?\s*(?:baje|am|pm)?"""),
            Regex("""(?i)(\d{1,2})(?::(\d{2}))?\s*(?:baje|am|pm)"""),
        )
        var atMs: Long? = null
        for (r in timeRegexes) {
            val m = r.find(original) ?: continue
            val hour = m.groupValues[1].toIntOrNull() ?: continue
            val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val cal = java.util.Calendar.getInstance()
            var h = hour
            if (lower.contains("pm") && h < 12) h += 12
            if (lower.contains("shaam") && h < 12) h += 12
            if (lower.contains("raat") && h < 12) h += 12
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            if (lower.contains("kal") || lower.contains("tomorrow")) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            if (cal.timeInMillis < System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            atMs = cal.timeInMillis
            break
        }
        val what = original
            .replace(Regex("""(?i)\b(reminder|yaad dila(?:na|o)?|remind me|kal|tomorrow|aaj|today|subah|morning|shaam|evening|raat|\d{1,2}(?::\d{2})?\s*(?:baje|am|pm)?)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(',', '.', '-', '–')
        return IntentResult(
            IntentName.CREATE_REMINDER,
            slots = Slots(reminderText = what.ifBlank { "Reminder" }, reminderAtEpochMs = atMs),
            confidence = if (atMs != null) 0.85 else 0.6,
            risk = ai.jarvis.core.RiskLevel.LOW,
            missingSlots = if (atMs == null) listOf("reminderAt") else emptyList(),
            language = lang, source = "rules",
            clarifiedQuestion = if (atMs == null) "Kab yaad dilau? (jaise: kal 6 baje)" else null,
        )
    }

    private fun parseSend(lower: String, original: String, lang: String): IntentResult? {
        val hasSendVerb = matchesAny(lower, listOf(
            "bhej", "send", "message kar", "msg kar", "likh ke bhej", "whatsapp par", "message bhejo", "text kar",
            "भेज", "likh",
        ))
        if (!hasSendVerb) return null

        // "Pramila ko hi bhej" | "send hi to Pramila" | "Pramila ko WhatsApp par hello bhej"
        val patterns = listOf(
            Regex("""(?i)(?:send|text|message)\s+["']?(.+?)["']?\s+to\s+([\p{L}\s]{2,30})"""),
            Regex("""(?i)([\p{L}\s]{2,30}?)\s*(?:ko|ke liye)\s*(?:whatsapp\s*(?:par|pe|se)?)?\s*["']?(.+?)["']?\s*(?:bhej|send|likh|bhej\s*do)"""),
            Regex("""(?i)["']?(.+?)["']?\s+likh\s*(?:ke|kar)?\s*([\p{L}\s]{2,30}?)\s*ko\s*bhej"""),
        )
        for (p in patterns) {
            val m = p.find(original) ?: continue
            var (msg, name) = when {
                p.pattern.startsWith("(?i)(?:send") -> m.groupValues[1].trim() to m.groupValues[2].trim()
                p.pattern.contains("likh") -> m.groupValues[1].trim() to m.groupValues[2].trim()
                else -> m.groupValues[2].trim() to m.groupValues[1].trim()
            }
            msg = msg.trim().trim('"', '\'', ',', '.')
            name = cleanName(name)
            if (name.isBlank()) continue
            // never treat filler words as the recipient
            if (name.lowercase() in setOf("whatsapp", "message", "msg", "hi", "hello", "ye", "wo")) continue
            val looksLikeMessage = msg.isBlank() || msg.lowercase() in setOf("message", "msg", "text")
            return IntentResult(
                IntentName.SEND_WHATSAPP_MESSAGE,
                slots = Slots(
                    recipientName = name,
                    message = if (looksLikeMessage) null else msg,
                ),
                confidence = if (!looksLikeMessage) 0.85 else 0.6,
                requiresConfirmation = true,
                risk = ai.jarvis.core.RiskLevel.HIGH,
                missingSlots = if (looksLikeMessage) listOf("message") else emptyList(),
                language = lang, source = "rules",
                clarifiedQuestion = if (looksLikeMessage) "Kya message bhejna hai $name ko?" else null,
            )
        }

        // "Pramila ko hi bhej" (name first, message = short token before)
        val short = Regex("""(?i)^([\p{L}\s]{2,30}?)\s*(?:ko|ke liye)\s*["']?([\p{L}\p{N}\s!?.,]{0,60}?)["']?\s*(?:bhej\s*do|bhej|send)\s*$""").find(original)
        if (short != null) {
            val name = cleanName(short.groupValues[1])
            val msg = short.groupValues[2].trim().trim('"', '\'')
            return IntentResult(
                IntentName.SEND_WHATSAPP_MESSAGE,
                slots = Slots(recipientName = name, message = msg.ifBlank { null }),
                confidence = if (msg.isBlank()) 0.6 else 0.82,
                requiresConfirmation = true,
                risk = ai.jarvis.core.RiskLevel.HIGH,
                missingSlots = if (msg.isBlank()) listOf("message") else emptyList(),
                language = lang, source = "rules",
                clarifiedQuestion = if (msg.isBlank()) "Kya message bhejna hai $name ko?" else null,
            )
        }
        return null
    }

    private fun cleanName(raw: String): String {
        var name = raw.trim()
            .replace(Regex("""(?i)\b(whatsapp|par|pe|se|ko|ke liye|message|msg|send|bhej)\b"""), " ")
            .replace(Regex("""(व्हाट्सएप|व्हाट्सऐप|भेजो|भेज|को|के लिए|पर|से|मैसेज|संदेश)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(',', '.', '!', '?')
        // strip trailing honorifics
        val parts = name.split(' ').filter { it.isNotBlank() }
        while (parts.isNotEmpty() && parts.last().lowercase() in honorifics) {
            name = parts.dropLast(1).joinToString(" ")
            break
        }
        return name.trim()
    }

    private fun contactTarget(original: String): String? {
        // "Pramila ke liye auto reply on" / "sirf Pramila ko auto reply"
        val patterns = listOf(
            Regex("""(?i)([\p{L}\s]{2,30}?)\s*(?:ke liye|ka|ki|ko)\s*(?:auto\s*-?\s*reply|autopilot)"""),
        )
        for (p in patterns) {
            val m = p.find(original) ?: continue
            val name = cleanName(m.groupValues[1])
            if (name.isBlank()) continue
            if (name.lowercase() in setOf("sab", "sabko", "all", "everyone", "mere", "sirf")) continue
            return name
        }
        return null
    }

    private fun matchesAny(lower: String, needles: List<String>): Boolean =
        needles.any { n ->
            if (n.length <= 3) {
                Regex("""(^|[\s,.;:!?])${Regex.escape(n)}([\s,.;:!?]|$)""").containsMatchIn(lower)
            } else lower.contains(n)
        }

    private fun unknown(reason: String) = IntentResult(
        IntentName.UNKNOWN, confidence = 0.0, requiresConfirmation = false,
        risk = ai.jarvis.core.RiskLevel.LOW, explanation = reason, clarifiedQuestion = "Kuch samajh nahi aaya.",
    )

    /** Optional LLM refinement — only used when the rules gave a low-confidence UNKNOWN. */
    suspend fun parseWithLlm(raw: String): IntentResult {
        val rules = parse(raw)
        if (rules.intent != IntentName.UNKNOWN || rules.confidence >= 0.55) return rules
        val gw = gateway ?: return rules
        val provider = gw.current()
        if (provider.id == "none") return rules
        val res = withTimeoutOrNull(12_000) {
            provider.jsonCall(Prompts.intentSystem, "Utterance: $raw", maxTokens = 300, temperature = 0.2)
        } ?: return rules
        if (res !is ApiResult.Success) return rules
        return try {
            val cleaned = res.data.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = json.parseToJsonElement(cleaned).jsonObject
            val intentName = obj["intent"]?.jsonPrimitive?.content ?: return rules
            val intent = IntentName.entries.firstOrNull { it.name == intentName } ?: return rules
            val slotsObj = obj["slots"]?.jsonObject
            val slots = Slots(
                recipientName = slotsObj?.get("recipientName")?.jsonPrimitive?.content?.takeIf { it != "null" },
                message = slotsObj?.get("message")?.jsonPrimitive?.content?.takeIf { it != "null" },
                chatName = slotsObj?.get("chatName")?.jsonPrimitive?.content?.takeIf { it != "null" },
                messageType = slotsObj?.get("messageType")?.jsonPrimitive?.content?.takeIf { it != "null" },
                recipientSet = slotsObj?.get("recipientSet")?.jsonPrimitive?.content?.takeIf { it != "null" },
                pollOptions = (slotsObj?.get("pollOptions") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList(),
            )
            val conf = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.7
            IntentResult(
                intent = intent, slots = slots, confidence = conf.coerceIn(0.0, 0.99),
                requiresConfirmation = intent in HIGH_RISK_INTENTS,
                risk = if (intent in HIGH_RISK_INTENTS) ai.jarvis.core.RiskLevel.HIGH else ai.jarvis.core.RiskLevel.LOW,
                language = obj["language"]?.jsonPrimitive?.content ?: rules.language,
                source = "llm",
            )
        } catch (_: Exception) { rules }
    }

    companion object {
        val HIGH_RISK_INTENTS = setOf(
            IntentName.SEND_WHATSAPP_MESSAGE, IntentName.SEND_BULK, IntentName.SEND_MEDIA_TO_CHAT,
            IntentName.CREATE_POLL, IntentName.SHARE_LOCATION, IntentName.BLOCK_CONTACT,
            IntentName.AUTOREPLY_ON, IntentName.AUTOREPLY_FOR_CONTACT_ON, IntentName.SEND_STATUS_UPDATE,
        )
    }
}

/** Pending action awaiting a yes/no from the user. TTL is enforced by the caller. */
data class PendingAction(
    val intent: IntentResult,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val expired: Boolean get() = System.currentTimeMillis() - createdAtMs > 120_000
    fun describe(): String = "Pending: ${intent.intent} — ${intent.slots.message ?: "?"} → ${intent.slots.recipientName ?: "?"}"
}

/** Safe arithmetic: recursive-descent only, no identifiers, no eval, no shell. */
object Calculator {
    private val candidate = Regex("""^\s*[-+(]?\s*[\d\s.+\-*/×÷()%^]+\s*$""")

    fun extractExpression(text: String): String? {
        val t = text.trim().removePrefix("=").trim()
        if (t.isEmpty()) return null
        val cleaned = t
            .replace(Regex("""(?i)\b(calculate|kya hoga|kitna hoga|solve|hisab|math|equals?)\b"""), " ")
            .replace("×", "*").replace("÷", "/")
            .replace(Regex("""(?<=\d)\s*[xX]\s*(?=\d)"""), "*")
            .trim()
        if (cleaned.length < 3) return null
        if (!candidate.matches(cleaned)) return null
        if (cleaned.count { it.isDigit() } == 0) return null
        if (!cleaned.any { it in "+-*/%^" }) return null
        return cleaned
    }

    fun evaluate(expression: String): Double? {
        val tokens = tokenize(expression) ?: return null
        val parser = Parser(tokens)
        val value = parser.parseExpression() ?: return null
        if (!parser.atEnd()) return null
        if (value.isNaN() || value.isInfinite()) return null
        return value
    }

    private class Parser(private val tokens: List<String>) {
        private var index = 0
        private var depth = 0

        fun atEnd(): Boolean = index >= tokens.size

        fun parseExpression(): Double? {
            var value = parseTerm() ?: return null
            while (index < tokens.size && (tokens[index] == "+" || tokens[index] == "-")) {
                val op = tokens[index++]
                val rhs = parseTerm() ?: return null
                value = if (op == "+") value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double? {
            var value = parseFactor() ?: return null
            while (index < tokens.size && (tokens[index] == "*" || tokens[index] == "/" || tokens[index] == "%")) {
                val op = tokens[index++]
                val rhs = parseFactor() ?: return null
                value = when (op) {
                    "*" -> value * rhs
                    "/" -> if (rhs == 0.0) return null else value / rhs
                    else -> if (rhs == 0.0) return null else value % rhs
                }
            }
            return value
        }

        private fun parseFactor(): Double? {
            if (depth > 20) return null
            if (index >= tokens.size) return null
            val t = tokens[index]
            if (t == "(") {
                index++
                depth++
                val v = parseExpression() ?: return null
                depth--
                if (index >= tokens.size || tokens[index] != ")") return null
                index++
                return applyPower(v)
            }
            if (t == "-") { index++; val v = parseFactor() ?: return null; return applyPower(-v) }
            if (t == "+") { index++; return parseFactor() }
            val num = t.toDoubleOrNull() ?: return null
            index++
            return applyPower(num)
        }

        private fun applyPower(base: Double): Double? {
            if (index < tokens.size && tokens[index] == "^") {
                index++
                val exp = parseFactor() ?: return null
                return Math.pow(base, exp)
            }
            return base
        }
    }

    private fun tokenize(input: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        val s = input.replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = 0
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                        if (s[i] == '.') dots++
                        if (dots > 1) return null
                        i++
                    }
                    out += s.substring(start, i)
                }
                c in "+-*/%^()" -> { out += c.toString(); i++ }
                else -> return null
            }
        }
        return out
    }
}
