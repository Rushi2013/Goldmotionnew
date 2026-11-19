package ai.jarvis.domain.policy

/**
 * The policy matrix from the spec (§7.5), implemented as a pure function so every row is unit-tested.
 *
 * Hard rules (override everything):
 *  - fromMe → never reply (loop protection)
 *  - OTP_SECURITY / SPAM_SCAM  → SILENCE (never reply, never echo digits)
 *  - WORK_OFFICE, MONEY_PAYMENT, LEGAL_MEDICAL, COMPLAINT_ANGER, PERSONAL_EMOTIONAL,
 *    URGENT, BUSINESS_ORDER, UNKNOWN  → ASK_USER (escalate), regardless of toggles
 *  - requiresHuman || confidence < 0.6 → ASK_USER regardless of category
 *  - groups: default OFF unless explicitly allowlisted AND (mentioned or pattern match)
 *  - a question that wants an action, in a non auto-allowed category → ASK_USER
 */
object AutoReplyPolicy {

    fun decide(classification: Classification, ctx: PolicyContext): PolicyVerdict {
        val cat = classification.category

        // 0. Gate
        if (ctx.fromMe) return PolicyVerdict.Block("Apne hi message ka reply nahi (fromMe)")
        if (ctx.mode == ai.jarvis.core.AutoReplyMode.OFF) return PolicyVerdict.Block("Auto-reply OFF hai")
        if (ctx.chatInDenylist) return PolicyVerdict.Block("Ye chat denylist me hai")
        if (ctx.doNotAutoReply) return PolicyVerdict.Block("Is contact ke liye auto-reply band hai")
        if (ctx.mode == ai.jarvis.core.AutoReplyMode.ALLOWLIST && !ctx.chatInAllowlist) {
            return PolicyVerdict.Block("ALLOWLIST mode: ye chat allowlist me nahi hai")
        }
        if (ctx.isGroupMessage && !ctx.chatInAllowlist) {
            return PolicyVerdict.Block("Group default OFF hai — sirf allowlisted group par reply hota hai")
        }
        if (ctx.isGroupMessage && !ctx.mentionsMe) {
            return PolicyVerdict.Block("Group message me aapko mention nahi kiya")
        }

        // 1. Silent categories — never send, never echo
        if (cat == ReplyCategory.OTP_SECURITY) {
            return PolicyVerdict.Silence("OTP/security message — kabhi reply nahi, log me digits store nahi")
        }
        if (cat == ReplyCategory.SPAM_SCAM) {
            return PolicyVerdict.Silence("Spam/scam lag raha hai — reply nahi")
        }

        // 2. Always-ask categories (hard escalation)
        val alwaysAsk = setOf(
            ReplyCategory.WORK_OFFICE, ReplyCategory.MONEY_PAYMENT, ReplyCategory.LEGAL_MEDICAL,
            ReplyCategory.COMPLAINT_ANGER, ReplyCategory.PERSONAL_EMOTIONAL, ReplyCategory.URGENT,
            ReplyCategory.BUSINESS_ORDER, ReplyCategory.UNKNOWN,
        )
        if (cat in alwaysAsk) {
            val reason = when (cat) {
                ReplyCategory.WORK_OFFICE -> "Office/kaam ka message lag raha hai"
                ReplyCategory.MONEY_PAYMENT -> "Paise/payment ka zikr hai"
                ReplyCategory.LEGAL_MEDICAL -> "Legal/medical message — savdhani"
                ReplyCategory.COMPLAINT_ANGER -> "Complaint/gussa lag raha hai"
                ReplyCategory.PERSONAL_EMOTIONAL -> "Personal/emotional message — aap khud dekh lein"
                ReplyCategory.URGENT -> "Urgent lag raha hai — turant aapko dikha raha hoon"
                ReplyCategory.BUSINESS_ORDER -> "Business order/delivery ka message"
                ReplyCategory.UNKNOWN -> "Category pakka nahi hui (low confidence)"
                else -> "Aapse poochna zaroori hai"
            }
            return PolicyVerdict.AskUser(reason)
        }

        // 3. Confidence / human-required gate
        if (classification.requiresHuman || classification.confidence < 0.6) {
            return PolicyVerdict.AskUser("Confidence kam hai (${(classification.confidence * 100).toInt()}%)")
        }

        // 4. Needs-explicit-enable categories
        when (cat) {
            ReplyCategory.SCHEDULING -> {
                if (cat !in ctx.userEnabledCategories) {
                    return PolicyVerdict.AskUser("Scheduling reply ke liye aapki setting chahiye (kabhi time promise nahi karta)")
                }
                return PolicyVerdict.AskUser("Scheduling me time/date promise nahi kar sakta — confirm karo")
            }
            ReplyCategory.INFO_REQUEST -> {
                return if (ctx.hasMatchingSavedFact) PolicyVerdict.AutoSend
                else PolicyVerdict.AskUser("Info ka jawab saved facts me nahi mila — invent nahi karunga")
            }
            ReplyCategory.PRICE_INQUIRY -> {
                return if (ctx.hasApprovedPriceSheet && cat in ctx.userEnabledCategories) PolicyVerdict.AutoSend
                else PolicyVerdict.AskUser("Price ka confirmed source nahi hai — price invent nahi karunga")
            }
            ReplyCategory.LOVE_FAMILY -> {
                return PolicyVerdict.AskUser("Family banter auto-reply ke liye contact ko 'family' mark karo")
            }
            else -> Unit
        }

        // 5. Auto-allowed categories (only if not disabled by the user)
        val enabled = ctx.userEnabledCategories
        val autoAllowed = when (cat) {
            ReplyCategory.GREETING, ReplyCategory.CONGRATULATION, ReplyCategory.THANKS,
            ReplyCategory.SMALL_TALK, ReplyCategory.OK_ACK, ReplyCategory.FAREWELL -> true
            else -> cat in enabled
        }
        if (!autoAllowed) return PolicyVerdict.AskUser("Ye category aapne off rakhi hai")

        // 6. Question that wants an action in a non-auto category → escalate
        if (ctx.inboundIsQuestion && classification.wantsAction &&
            cat !in setOf(ReplyCategory.GREETING, ReplyCategory.SMALL_TALK, ReplyCategory.OK_ACK)
        ) {
            return PolicyVerdict.AskUser("Sawaal + action chahiye — aapse confirm karunga")
        }

        return PolicyVerdict.AutoSend
    }

    /** Deterministic templates for the categories where an LLM answer adds nothing. */
    fun templateFor(category: ReplyCategory, inbound: String, contactName: String?, greetingTemplate: String): String =
        when (category) {
            ReplyCategory.GREETING -> greetingTemplate
            ReplyCategory.CONGRATULATION -> {
                val name = contactName?.takeIf { it.isNotBlank() }?.let { " ${it.split(' ').first()}," } ?: ""
                "Thank you so much$name! 🙏"
            }
            ReplyCategory.THANKS -> if (isHindi(inbound)) "Koi baat nahi 🙏" else "You're welcome! 😊"
            ReplyCategory.OK_ACK -> "👍"
            ReplyCategory.FAREWELL -> if (isHindi(inbound)) "Theek hai, milte hain! 🙏" else "See you soon! 🙏"
            ReplyCategory.SMALL_TALK -> if (isHindi(inbound)) "Sab badhiya! Aap kaise ho? 😊" else "All good here! How are you? 😊"
            else -> ""
        }

    private fun isHindi(text: String): Boolean {
        if (text.any { it.code in 0x0900..0x097F }) return true
        val hinglish = listOf("kaise", "kaisa", "kya", "hai", "ho", "bhai", "yaar", "namaste", "shukriya", "dhanyavad", "milte", "theek", "badhiya")
        val lower = text.lowercase()
        return hinglish.any { lower.contains(it) }
    }
}
