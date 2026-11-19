package ai.jarvis.autoreply

import ai.jarvis.ai.MessageClassifier
import ai.jarvis.core.ApiResult
import ai.jarvis.core.DeliveryState
import ai.jarvis.core.Ids
import ai.jarvis.data.SettingsStore
import ai.jarvis.data.api.OpenWaApi
import ai.jarvis.data.db.AutoReplyAllowEntity
import ai.jarvis.data.db.AutoReplyConfigEntity
import ai.jarvis.data.db.AutopilotLogEntity
import ai.jarvis.data.db.ChatMemoryEntity
import ai.jarvis.data.db.EscalationEntity
import ai.jarvis.data.db.JarvisDatabase
import ai.jarvis.data.db.SeenMessageEntity
import ai.jarvis.domain.policy.AutoReplyPolicy
import ai.jarvis.domain.policy.Classification
import ai.jarvis.domain.policy.PolicyContext
import ai.jarvis.domain.policy.PolicyVerdict
import ai.jarvis.domain.policy.ReplyCategory
import ai.jarvis.domain.policy.RuleClassifier
import ai.jarvis.domain.policy.SafetyLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement

/** An inbound message normalised from Socket.IO / webhook / history polling. */
data class InboundEvent(
    val sessionId: String,
    val messageId: String,
    val chatId: String,
    val chatName: String?,
    val fromMe: Boolean,
    val text: String,
    val timestampMs: Long,
    val type: String = "text",
    val kind: String = "individual", // individual | group | channel | status | broadcast | unknown
    val mentionsMe: Boolean = false,
    val quotedMessageId: String? = null,
)

/**
 * Pillar B — the Auto-Reply Engine.
 *
 * Deterministic pipeline (spec §7.3): gate → dedupe → eligibility → rate/caps → context →
 * classify → policy → draft → safety lint → send → log → (escalate when needed).
 *
 * Honest limitation surfaced in the UI: this only runs while the app's foreground service is alive.
 */
class AutoReplyEngine(
    private val db: JarvisDatabase,
    private val api: OpenWaApi,
    private val classifier: MessageClassifier,
    private val settings: SettingsStore,
    private val notifications: Notifier?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val loopBreaker = LoopBreaker()
    private val rateLimiter = ReplyRateLimiter()

    data class EngineState(
        val enabled: Boolean = false,
        val mode: String = "OFF",
        val allowCount: Int = 0,
        val lastDecision: String = "",
        val lastCategory: String = "",
        val autoRepliedToday: Int = 0,
        val escalatedToday: Int = 0,
        val running: Boolean = false,
        val lastError: String? = null,
        val quietNow: Boolean = false,
    )

    // ------------------------------------------------------------------ configuration

    suspend fun loadConfig(): AutoReplyConfigEntity = db.autoReplyDao().getConfig() ?: AutoReplyConfigEntity().also {
        db.autoReplyDao().saveConfig(it)
    }

    suspend fun setMode(mode: ai.jarvis.core.AutoReplyMode): AutoReplyConfigEntity {
        val cfg = loadConfig().copy(mode = mode.name, updatedAt = System.currentTimeMillis())
        db.autoReplyDao().saveConfig(cfg)
        settings.update { it.copy(autoReplyMode = mode) }
        refreshState()
        return cfg
    }

    suspend fun refreshState() {
        val cfg = loadConfig()
        val allowCount = db.autoReplyDao().allowCount()
        val since = startOfToday()
        val autoToday = db.autopilotLogDao().autoSentGlobalSince(since)
        val escToday = db.escalationDao().pending().size
        val s = settings.settings.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val quiet = s.quietHoursEnabled && QuietHours.isQuiet(hour, s.quietStart, s.quietEnd)
        _state.value = _state.value.copy(
            enabled = cfg.mode != "OFF",
            mode = cfg.mode,
            allowCount = allowCount,
            autoRepliedToday = autoToday,
            escalatedToday = escToday,
            quietNow = quiet,
        )
    }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ------------------------------------------------------------------ allowlist

    suspend fun addToAllowlist(chatId: String, contactName: String) {
        db.autoReplyDao().addAllow(AutoReplyAllowEntity(apiChatId = chatId, contactName = contactName))
        refreshState()
    }

    suspend fun removeFromAllowlist(chatId: String) {
        db.autoReplyDao().removeAllow(chatId)
        refreshState()
    }

    // ------------------------------------------------------------------ the pipeline

    /**
     * Handles one inbound message. Returns a short summary of what happened (for the Lab screen).
     * This is the ONLY place that decides whether an automatic message leaves the phone.
     */
    suspend fun onInbound(event: InboundEvent): HandleResult {
        val started = System.currentTimeMillis()
        val cfg = loadConfig()
        val appSettings = settings.settings.value
        val mode = ai.jarvis.core.AutoReplyMode.entries.firstOrNull { it.name == cfg.mode }
            ?: ai.jarvis.core.AutoReplyMode.OFF

        // 0. GATE — never answer ourselves, statuses, channels, old backlog
        if (event.fromMe) return HandleResult.ignored("fromMe — apna message")
        if (event.kind in setOf("status", "channel", "broadcast")) return HandleResult.ignored("kind=${event.kind}")
        if (event.type !in setOf("text", "image")) return HandleResult.ignored("type=${event.type} (non-text)")
        val ageMs = System.currentTimeMillis() - event.timestampMs
        if (ageMs > 5 * 60_000) return HandleResult.ignored("5 min se purana message — backlog storm rok diya")

        // 1. DEDUPE
        val seeKey = DedupeKey.of(event.sessionId, event.messageId, event.text)
        val inserted = db.seenMessageDao().insert(SeenMessageEntity(key = seeKey))
        if (inserted == -1L) return HandleResult.ignored("duplicate messageId")
        if (db.autopilotLogDao().seen(event.messageId) > 0) return HandleResult.ignored("already processed")

        // 2. ELIGIBILITY
        val allowlist = db.autoReplyDao().allowlist()
        val memory = db.chatMemoryDao().get(event.chatId)
        val inAllow = allowlist.any { it.apiChatId == event.chatId }
        val ctx = PolicyContext(
            mode = mode,
            chatInAllowlist = inAllow,
            chatInDenylist = false,
            doNotAutoReply = memory?.doNotAutoReply == true,
            isGroupMessage = event.kind == "group",
            mentionsMe = event.mentionsMe,
            userEnabledCategories = enabledCategories(cfg.categoriesJson),
            hasApprovedPriceSheet = false,
            hasMatchingSavedFact = factMatch(memory, event.text),
            inboundIsQuestion = RuleClassifier.isQuestion(event.text),
            fromMe = event.fromMe,
        )

        if (mode == ai.jarvis.core.AutoReplyMode.OFF) {
            return HandleResult.ignored("Auto-reply OFF hai")
        }

        // 3. RATE / CAPS / QUIET HOURS
        if (appSettings.quietHoursEnabled) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (QuietHours.isQuiet(hour, appSettings.quietStart, appSettings.quietEnd)) {
                return HandleResult.ignored("Quiet hours — reply rok diya")
            }
        }
        val loop = loopBreaker.check(event.chatId, event.text, event.messageId)
        if (!loop.allowed) {
            log(event, ReplyCategory.UNKNOWN, "BLOCKED", 0.0, null, null, "BLOCKED", loop.reason, started)
            return HandleResult.blocked(loop.reason)
        }
        val perChatToday = db.autopilotLogDao().autoSentSince(event.chatId, startOfToday())
        val globalHour = db.autopilotLogDao().autoSentGlobalSince(System.currentTimeMillis() - 3600_000L)
        val rate = rateLimiter.check(
            event.chatId, perChatToday, globalHour,
            appSettings.maxPerChatPerDay, appSettings.maxPerHourTotal,
        )
        if (!rate.allowed) {
            log(event, ReplyCategory.UNKNOWN, "BLOCKED", 0.0, null, null, "BLOCKED", rate.reason, started)
            return HandleResult.blocked(rate.reason)
        }

        // 4. CONTEXT — last N messages from the gateway (strip media, keep sender+body)
        val context = fetchContext(event.chatId)

        // 5. CLASSIFY
        val classification = classifier.classify(
            text = event.text,
            contactName = event.chatName,
            previous = context,
            familyFlag = memory?.familyFlag == true,
            hasSavedFact = ctx.hasMatchingSavedFact,
            isGroup = event.kind == "group",
        )

        // 6. POLICY
        val verdict = AutoReplyPolicy.decide(classification, ctx)
        when (verdict) {
            is PolicyVerdict.Block -> {
                log(event, classification.category, "BLOCKED", classification.confidence, null, null, "BLOCKED", verdict.reason, started, classification.source)
                return HandleResult.ignored(verdict.reason)
            }
            is PolicyVerdict.Silence -> {
                log(event, classification.category, "SILENCE", classification.confidence, null, null, "SILENCE", verdict.reason, started, classification.source)
                return HandleResult.silenced(classification, verdict.reason)
            }
            is PolicyVerdict.AskUser -> {
                val escal = createEscalation(event, classification, verdict.reason)
                log(event, classification.category, "ASK_USER", classification.confidence, null, null, "ESCALATED", verdict.reason, started, classification.source)
                notifications?.notifyEscalation(
                    id = escal.id.toInt(),
                    title = "[${classification.category.name}] ${event.chatName ?: event.chatId}",
                    body = event.text.take(120),
                    category = classification.category.name,
                )
                refreshState()
                return HandleResult.escalated(classification, verdict.reason, escal.id)
            }
            PolicyVerdict.AutoSend -> Unit
        }

        // 7. DRAFT — deterministic template first, LLM for SMALL_TALK and as an upgrade
        val contactName = event.chatName
        var draft = AutoReplyPolicy.templateFor(
            classification.category, event.text, contactName, appSettings.greetingTemplate,
        )
        if (draft.isBlank() || classification.category == ReplyCategory.SMALL_TALK) {
            val llmDraft = classifier.draft(event.text, classification.category, context)
            if (!llmDraft.isNullOrBlank()) draft = llmDraft else if (draft.isBlank()) {
                // No template + no model: escalate instead of inventing text.
                val escal = createEscalation(event, classification, "Template nahi mila aur AI draft bhi nahi aaya")
                log(event, classification.category, "ASK_USER", classification.confidence, null, null, "ESCALATED", "no draft source", started, classification.source)
                return HandleResult.escalated(classification, "Koi safe draft nahi bana", escal.id)
            }
        }

        // 8. SAFETY LINT
        val lint = SafetyLint.check(
            draft = draft,
            category = classification.category,
            inboundLanguage = classification.language,
            requestedLanguage = appSettings.autoReplyLanguage,
        )
        if (!lint.safe) {
            val escal = createEscalation(event, classification, "Safety lint: ${lint.reasonText}")
            log(event, classification.category, "ASK_USER", classification.confidence, draft, null, "ESCALATED", lint.reasonText, started, classification.source)
            return HandleResult.escalated(classification, "Draft safe nahi tha", escal.id)
        }

        // 9. SEND — idempotency key derived from the inbound message so retries can't double-reply
        val sessionId = event.sessionId
        val idem = Ids.idemKey("autoreply", sessionId, event.messageId, draft.hashCode().toString())
        val result = if (event.quotedMessageId != null) {
            api.reply(sessionId, event.chatId, event.quotedMessageId, draft, idempotencyKey = idem)
        } else {
            api.sendText(sessionId, event.chatId, draft, idempotencyKey = idem)
        }

        // 10. LOG
        return when (result) {
            is ApiResult.Success -> {
                loopBreaker.recordAutoReply(event.chatId)
                rateLimiter.record(event.chatId)
                log(
                    event, classification.category, "AUTO_SEND", classification.confidence,
                    draft, draft, DeliveryState.ACCEPTED.name, null, started, classification.source,
                    result.data.messageId,
                )
                db.chatMessageDao().insert(
                    ai.jarvis.data.db.ChatMessageEntity(
                        conversationId = event.chatId,
                        role = "JARVIS",
                        text = draft,
                        timestampEpochMs = System.currentTimeMillis(),
                        deliveryState = DeliveryState.ACCEPTED.name,
                        apiMessageId = result.data.messageId,
                        chatIdApi = event.chatId,
                        fromMe = true,
                    )
                )
                if (appSettings.notifyEveryReply) {
                    notifications?.notifyAutoReply(event.chatName ?: event.chatId, draft)
                }
                refreshState()
                HandleResult.autoSent(classification, draft, result.data.messageId)
            }
            is ApiResult.Failure -> {
                log(
                    event, classification.category, "AUTO_SEND", classification.confidence,
                    draft, null,
                    if (result.error.transport) DeliveryState.OUTCOME_UNKNOWN.name else DeliveryState.FAILED.name,
                    result.error.oneLine, started, classification.source,
                )
                // A failed send is escalated so the user can retry by hand — never silently dropped.
                val escal = createEscalation(event, classification, "Auto-send fail hua: ${result.error.oneLine}")
                HandleResult.escalated(classification, "Send fail: ${result.error.oneLine}", escal.id)
            }
        }
    }

    private suspend fun fetchContext(chatId: String): List<String> {
        val sessionId = settings.settings.value.sessionId
        if (sessionId.isBlank()) return emptyList()
        return when (val res = api.messages(sessionId, chatId = chatId, limit = 8, orderBy = "desc")) {
            is ApiResult.Success -> res.data.messages
                .sortedBy { it.timestamp ?: 0 }
                .mapNotNull { m -> m.body?.takeIf { it.isNotBlank() }?.let { body -> (if (m.isOutgoing) "Me: " else "Them: ") + body } }
            is ApiResult.Failure -> emptyList()
        }
    }

    private fun factMatch(memory: ChatMemoryEntity?, text: String): Boolean {
        if (memory == null) return false
        return try {
            val facts = json.parseToJsonElement(memory.factsJson)
            val obj = facts as? kotlinx.serialization.json.JsonObject ?: return false
            val lower = text.lowercase()
            obj.entries.any { (k, v) ->
                val value = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                value.isNotBlank() && lower.contains(k.lowercase())
            }
        } catch (_: Exception) { false }
    }

    private fun enabledCategories(categoriesJson: String): Set<ReplyCategory> = try {
        val el = json.parseToJsonElement(categoriesJson)
        when (el) {
            is kotlinx.serialization.json.JsonArray -> el.mapNotNull {
                if (it.jsonPrimitive.content.equals("true", true)) null else null
            }.toSet()
            is kotlinx.serialization.json.JsonObject -> el.entries
                .filter { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
                .mapNotNull { ReplyCategory.entries.firstOrNull { c -> c.name == it.key } }
                .toSet()
            else -> emptySet()
        }
    } catch (_: Exception) { emptySet() }

    private suspend fun createEscalation(event: InboundEvent, classification: Classification, reason: String): EscalationEntity {
        val existing = db.escalationDao().byInbound(event.messageId)
        if (existing != null) return existing
        val suggestions = mutableListOf<String>()
        // Deterministic safe suggestions that the user can send with one tap.
        suggestions += when (classification.category) {
            ReplyCategory.WORK_OFFICE -> "Mil gaya. Main dekh ke batata hoon."
            ReplyCategory.URGENT -> "Abhi dekh raha hoon, thodi der me batata hoon."
            ReplyCategory.MONEY_PAYMENT -> "Mil gaya, check karke batata hoon."
            else -> "Noted, main jaldi reply karta hoon."
        }
        val llm = runCatching { classifier.draft(event.text, classification.category) }.getOrNull()
        if (!llm.isNullOrBlank()) suggestions.add(0, llm)

        val entity = EscalationEntity(
            chatId = event.chatId,
            chatName = event.chatName,
            inboundMessageId = event.messageId,
            inboundText = if (classification.category == ReplyCategory.OTP_SECURITY) "[OTP content hidden]" else event.text,
            category = classification.category.name,
            suggestedJson = suggestions.distinct().joinToString(
                prefix = "[", postfix = "]", separator = ",",
            ) { kotlinx.serialization.json.JsonPrimitive(it).toString() },
            reason = reason,
        )
        val id = db.escalationDao().insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun log(
        event: InboundEvent,
        category: ReplyCategory,
        decision: String,
        confidence: Double,
        draft: String?,
        sentText: String?,
        resultState: String,
        error: String?,
        startedMs: Long,
        source: String = "rules",
        apiMessageId: String? = null,
    ) {
        db.autopilotLogDao().insert(
            AutopilotLogEntity(
                inboundMessageId = event.messageId,
                chatId = event.chatId,
                chatName = event.chatName,
                inboundText = if (category == ReplyCategory.OTP_SECURITY) "[hidden]" else event.text.take(500),
                category = category.name,
                decision = decision,
                confidence = confidence,
                draft = draft,
                sentText = sentText,
                resultState = resultState,
                errorText = error,
                model = source,
                latencyMs = System.currentTimeMillis() - startedMs,
            )
        )
    }

    /** Sends an approved escalation reply (quoted) and marks it SENT. */
    suspend fun sendEscalation(escalationId: Long, text: String, useQuote: Boolean = true): ApiResult<String> {
        val esc = db.escalationDao().pending().firstOrNull { it.id == escalationId }
            ?: return ApiResult.Failure(ai.jarvis.core.ApiError(0, "Escalation not found or already handled"))
        val sessionId = settings.settings.value.sessionId
        val result = if (useQuote && esc.inboundMessageId.isNotBlank()) {
            api.reply(sessionId, esc.chatId, esc.inboundMessageId, text, idempotencyKey = Ids.idemKey("escalation", "$escalationId", text.hashCode().toString()))
        } else {
            api.sendText(sessionId, esc.chatId, text, idempotencyKey = Ids.idemKey("escalation", "$escalationId", text.hashCode().toString()))
        }
        return when (result) {
            is ApiResult.Success -> {
                db.escalationDao().setState(escalationId, "SENT")
                ApiResult.Success(result.data.messageId)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun dismissEscalation(id: Long) = db.escalationDao().setState(id, "DISMISSED")

    fun resetLoopBreaker() = loopBreaker.reset()

    sealed class HandleResult {
        data class AutoSent(val classification: Classification, val draft: String, val messageId: String) : HandleResult()
        data class Escalated(val classification: Classification, val reason: String, val escalationId: Long) : HandleResult()
        data class Silenced(val classification: Classification, val reason: String) : HandleResult()
        data class Ignored(val reason: String) : HandleResult()
        data class Blocked(val reason: String) : HandleResult()

        companion object {
            /** Lower-case factories used throughout the pipeline. */
            fun ignored(reason: String): HandleResult = Ignored(reason)
            fun blocked(reason: String): HandleResult = Blocked(reason)
            fun silenced(classification: Classification, reason: String): HandleResult =
                Silenced(classification, reason)
            fun escalated(classification: Classification, reason: String, escalationId: Long): HandleResult =
                Escalated(classification, reason, escalationId)
            fun autoSent(classification: Classification, draft: String, messageId: String): HandleResult =
                AutoSent(classification, draft, messageId)
        }

        val summary: String
            get() = when (this) {
                is AutoSent -> "AUTO_SEND → \"$draft\" (${classification.category.name}, ${(classification.confidence * 100).toInt()}%) messageId=$messageId"
                is Escalated -> "ASK_USER (${classification.category.name}) — $reason"
                is Silenced -> "SILENCE (${classification.category.name}) — $reason"
                is Ignored -> "IGNORED — $reason"
                is Blocked -> "BLOCKED — $reason"
            }
    }
}

/** Minimal notification surface so the engine stays unit-testable. */
interface Notifier {
    fun notifyEscalation(id: Int, title: String, body: String, category: String)
    fun notifyAutoReply(chat: String, draft: String)
    fun notifyEngineRunning(enabled: Boolean, text: String)
}
