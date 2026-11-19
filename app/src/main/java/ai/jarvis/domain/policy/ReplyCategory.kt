package ai.jarvis.domain.policy

/**
 * Fixed 23-category taxonomy. The classifier (LLM or rules) may only choose from these.
 * Grouping tells the UI which toggles are "auto-allowed", "needs explicit enable", "always ask".
 */
enum class ReplyCategory(val label: String, val group: CategoryGroup, val autoSendByDefault: Boolean) {
    GREETING("Greeting", CategoryGroup.AUTO, true),
    CONGRATULATION("Congratulation", CategoryGroup.AUTO, true),
    THANKS("Thanks", CategoryGroup.AUTO, true),
    SMALL_TALK("Small talk", CategoryGroup.AUTO, true),
    OK_ACK("OK / acknowledgement", CategoryGroup.AUTO, true),
    FAREWELL("Farewell", CategoryGroup.AUTO, true),
    LOVE_FAMILY("Family banter", CategoryGroup.NEEDS_ENABLE, false),

    SCHEDULING("Scheduling", CategoryGroup.NEEDS_ENABLE, false),
    INFO_REQUEST("Info request", CategoryGroup.NEEDS_ENABLE, false),
    PRICE_INQUIRY("Price inquiry", CategoryGroup.NEEDS_ENABLE, false),

    WORK_OFFICE("Work / office", CategoryGroup.ALWAYS_ASK, false),
    MONEY_PAYMENT("Money / payment", CategoryGroup.ALWAYS_ASK, false),
    LEGAL_MEDICAL("Legal / medical", CategoryGroup.ALWAYS_ASK, false),
    COMPLAINT_ANGER("Complaint / anger", CategoryGroup.ALWAYS_ASK, false),
    PERSONAL_EMOTIONAL("Personal / emotional", CategoryGroup.ALWAYS_ASK, false),
    URGENT("Urgent", CategoryGroup.ALWAYS_ASK, false),
    BUSINESS_ORDER("Business order", CategoryGroup.ALWAYS_ASK, false),
    OTP_SECURITY("OTP / security", CategoryGroup.SILENT, false),
    UNKNOWN("Unknown", CategoryGroup.ALWAYS_ASK, false),
    SPAM_SCAM("Spam / scam", CategoryGroup.SILENT, false);

    companion object {
        val ALL_NAMES: List<String> = entries.map { it.name }
        fun parse(raw: String?): ReplyCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), true) } ?: UNKNOWN
    }
}

enum class CategoryGroup(val label: String) {
    AUTO("Auto-send allowed"),
    NEEDS_ENABLE("Only if you enable it"),
    ALWAYS_ASK("Always asks you"),
    SILENT("Never replies"),
}

/** What the engine is allowed to do with a classified inbound message. */
enum class Decision {
    AUTO_SEND,
    ASK_USER,
    SILENCE,
    HANDOFF,
    BLOCKED,
}

data class Classification(
    val category: ReplyCategory,
    val confidence: Double,
    val language: String = "unknown",
    val urgency: Double = 0.0,
    val sentiment: Double = 0.0,
    val wantsAction: Boolean = false,
    val requiresHuman: Boolean = false,
    val evidence: String = "",
    val jobDetected: Boolean = false,
    val source: String = "rules",
) {
    val categoryLabel: String get() = category.label
}

data class PolicyContext(
    val mode: ai.jarvis.core.AutoReplyMode = ai.jarvis.core.AutoReplyMode.OFF,
    val chatInAllowlist: Boolean = false,
    val chatInDenylist: Boolean = false,
    val doNotAutoReply: Boolean = false,
    val isGroupMessage: Boolean = false,
    val mentionsMe: Boolean = false,
    val userEnabledCategories: Set<ReplyCategory> = emptySet(),
    val hasApprovedPriceSheet: Boolean = false,
    val hasMatchingSavedFact: Boolean = false,
    val inboundIsQuestion: Boolean = false,
    val fromMe: Boolean = false,
)

sealed class PolicyVerdict {
    data object AutoSend : PolicyVerdict()
    data class AskUser(val reason: String) : PolicyVerdict()
    data class Silence(val reason: String) : PolicyVerdict()
    data class Block(val reason: String) : PolicyVerdict()
}
