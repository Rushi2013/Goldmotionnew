package ai.jarvis.ai

import ai.jarvis.core.ApiResult
import ai.jarvis.domain.policy.Classification
import ai.jarvis.domain.policy.ReplyCategory
import ai.jarvis.domain.policy.RuleClassifier
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Prompts live in code so they can be unit-tested and audited. */
object Prompts {
    val classifySystem: String = """
You are a strict JSON classifier for WhatsApp messages received by an Indian user.
Reply with ONE JSON object and nothing else. No markdown, no prose.

Schema:
{"category":"<CATEGORY>","confidence":0.0-1.0,"language":"en|hinglish|hi-deva|other",
 "urgency":0.0-1.0,"sentiment":-1.0..1.0,"wants_action":true|false,
 "requires_human":true|false,"evidence":"<quote from the message that decided it>","job_detected":true|false}

CATEGORY must be exactly one of:
GREETING, CONGRATULATION, THANKS, SMALL_TALK, OK_ACK, FAREWELL, LOVE_FAMILY,
SCHEDULING, INFO_REQUEST, PRICE_INQUIRY,
WORK_OFFICE, MONEY_PAYMENT, LEGAL_MEDICAL, COMPLAINT_ANGER, PERSONAL_EMOTIONAL, URGENT,
BUSINESS_ORDER, OTP_SECURITY, SPAM_SCAM, UNKNOWN

Rules that override everything:
- Any OTP, verification code, login code, password or 2FA content => OTP_SECURITY.
- Money, payment, bill, bank, UPI, refund, amounts, salary => MONEY_PAYMENT.
- Work/office: report, deadline, client, boss, meeting, presentation, invoice => WORK_OFFICE and job_detected=true.
- Anything you are less than 60% sure about => UNKNOWN.
- Hindi/Hinglish text must still be classified the same way; never translate the evidence field.
""".trimIndent()

    fun classifyUser(text: String, contactName: String?, previous: List<String>, hasFact: Boolean): String = buildString {
        appendLine("Inbound message: ${text.replace("\n", " ")}")
        appendLine("Sender name: ${contactName ?: "unknown"}")
        appendLine("Previous messages (oldest first):")
        previous.takeLast(6).forEach { appendLine("- ${it.replace("\n", " ").take(200)}") }
        appendLine("Saved facts available: $hasFact")
        appendLine("Answer with the JSON object only.")
    }

    val draftSystem: String = """
You are a polite personal assistant replying on behalf of the phone owner.
Return JSON ONLY: {"draft":"<reply text>"} or {"draft":null} when you should not answer.

Hard constraints:
- Same language as the inbound message (Hindi / Hinglish / English).
- At most 2 sentences and at most 220 characters.
- No promises ("I will", "kar dunga", "ho jayega"), no prices, no amounts, no dates or times,
  no links, no email addresses, no phone numbers, no OTP digits, no invented facts.
- If the message needs the owner's decision, return {"draft":null}.
""".trimIndent()

    fun draftUser(inbound: String, category: String, previous: List<String>, userName: String): String = buildString {
        appendLine("Owner name: $userName")
        appendLine("Detected category: $category")
        appendLine("Recent conversation:")
        previous.takeLast(6).forEach { appendLine("- ${it.take(200)}") }
        appendLine("Inbound message to reply to: ${inbound.take(800)}")
        appendLine("Return only the JSON object.")
    }

    val intentSystem: String = """
You convert a user's spoken/typed command into ONE JSON object for an Android WhatsApp assistant.
Reply with JSON only, no markdown.

Schema: {"intent":"<INTENT>","slots":{"recipientName":null,"recipientPhoneHint":null,"message":null,
"autoReplyScope":null,"messageType":null,"recipientSet":null,"pollOptions":[],"chatName":null,
"reminderText":null,"expression":null,"query":null},"confidence":0.0,"language":"en|hinglish|hi-deva"}

INTENT must be one of:
SEND_WHATSAPP_MESSAGE, WHATSAPP_SESSION_STATUS, GENERAL_QUESTION, CALCULATE, GET_TIME, GET_DATE,
CREATE_REMINDER, LIST_REMINDERS, AUTOREPLY_ON, AUTOREPLY_OFF, AUTOREPLY_FOR_CONTACT_ON,
AUTOREPLY_FOR_CONTACT_OFF, AUTOREPLY_STATUS, SEND_BULK, SEND_STATUS_UPDATE, OPEN_CHAT,
READ_UNREAD_MESSAGES, REPLY_TO_LAST, APPROVE_ESCALATION, LIST_ESCALATIONS, SEND_MEDIA_TO_CHAT,
CREATE_POLL, SHARE_LOCATION, MUTE_CHAT, BLOCK_CONTACT, LINK_SESSION, CONFIRM_YES, CONFIRM_NO, CANCEL, UNKNOWN

autoReplyScope = GLOBAL | CONTACT | OFF. recipientSet = ALL_CONTACTS | CURRENT_CHAT | NAMED_LIST.
messageType = TEXT | IMAGE | VIDEO | AUDIO | DOCUMENT | STICKER | LOCATION | CONTACT | POLL.
Never invent phone numbers. If a name is ambiguous, still return it and set confidence <= 0.7.
""".trimIndent()
}

/**
 * Classifier that prefers the LLM and falls back to the deterministic rules.
 * The `source` field always tells the truth about which one answered.
 */
class MessageClassifier(
    private val gateway: AiGateway,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun classify(
        text: String,
        contactName: String? = null,
        previous: List<String> = emptyList(),
        familyFlag: Boolean = false,
        hasSavedFact: Boolean = false,
        isGroup: Boolean = false,
    ): Classification {
        val rules = RuleClassifier.classify(text, isGroup = isGroup, familyFlag = familyFlag)

        val provider = gateway.current()
        if (provider.id == "none") return rules

        val result = withTimeoutOrNull(15_000) {
            provider.jsonCall(
                Prompts.classifySystem,
                Prompts.classifyUser(text, contactName, previous, hasSavedFact),
                maxTokens = 260,
                temperature = 0.2,
            )
        } ?: return rules

        return when (result) {
            is ApiResult.Success -> parseClassification(result.data, rules) ?: rules
            is ApiResult.Failure -> rules
        }
    }

    private fun parseClassification(raw: String, fallback: Classification): Classification? = try {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val category = ReplyCategory.parse(obj["category"]?.jsonPrimitive?.content)
        val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        Classification(
            category = category,
            confidence = confidence.coerceIn(0.0, 1.0),
            language = obj["language"]?.jsonPrimitive?.content ?: fallback.language,
            urgency = obj["urgency"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            sentiment = obj["sentiment"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            wantsAction = obj["wants_action"]?.jsonPrimitive?.booleanOrNull
                ?: obj["wantsAction"]?.jsonPrimitive?.booleanOrNull ?: fallback.wantsAction,
            requiresHuman = obj["requires_human"]?.jsonPrimitive?.booleanOrNull
                ?: obj["requiresHuman"]?.jsonPrimitive?.booleanOrNull ?: false,
            evidence = obj["evidence"]?.jsonPrimitive?.content.orEmpty(),
            jobDetected = obj["job_detected"]?.jsonPrimitive?.booleanOrNull
                ?: obj["jobDetected"]?.jsonPrimitive?.booleanOrNull ?: false,
            source = "llm",
        )
    } catch (_: Exception) {
        null
    }

    /** Drafts a reply; null means "I should not answer this one". */
    suspend fun draft(
        inbound: String,
        category: ReplyCategory,
        previous: List<String> = emptyList(),
        userName: String = "the owner",
    ): String? {
        val provider = gateway.current()
        if (provider.id == "none") return null
        val result = withTimeoutOrNull(15_000) {
            provider.jsonCall(
                Prompts.draftSystem,
                Prompts.draftUser(inbound, category.name, previous, userName),
                maxTokens = 200,
                temperature = 0.5,
            )
        } ?: return null
        return when (result) {
            is ApiResult.Success -> {
                try {
                    val cleaned = result.data.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val obj = json.parseToJsonElement(cleaned).jsonObject
                    val d = obj["draft"]
                    if (d == null || d is kotlinx.serialization.json.JsonNull) null
                    else d.jsonPrimitive.content.takeIf { it.isNotBlank() && !it.equals("null", true) }
                } catch (_: Exception) { null }
            }
            is ApiResult.Failure -> null
        }
    }

    /** Two or three alternative drafts for the escalation screen. */
    suspend fun draftVariants(
        inbound: String,
        category: ReplyCategory,
        previous: List<String> = emptyList(),
        count: Int = 3,
    ): List<String> {
        val main = draft(inbound, category, previous) ?: return emptyList()
        val variants = mutableListOf(main)
        // Deterministic tonal variants keep quality honest without extra model calls.
        val second = draft(inbound, category, previous, "formal") ?: ""
        if (second.isNotBlank()) variants += second
        return variants.distinct().take(count)
    }
}
