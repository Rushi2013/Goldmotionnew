package ai.jarvis.domain.policy

/**
 * Runs on every outgoing auto-draft. If it fails, the engine escalates instead of sending.
 * Spec §7.6 — "no promises, no prices, no links, no invented facts".
 */
object SafetyLint {

    data class Verdict(val safe: Boolean, val reasons: List<String> = emptyList()) {
        val reasonText: String get() = reasons.joinToString("; ")
    }

    private val moneyPatterns = listOf(
        Regex("""[₹$]"""),
        Regex("""(?i)\b(rs\.?|rupees?|inr|usd|dollars?|paisa|paise|rupaye)\b"""),
        Regex("""(?i)\b(upi|gpay|paytm|neft|imps|refund|amount|payment|bill|invoice)\b"""),
        Regex("""(?i)\bpay\s+(me|now|here)\b"""),
    )
    private val promisePatterns = listOf(
        Regex("""(?i)\b(i will|i'll|will do|kar dunga|kar dungi|ho jayega|de dunga|bhej dunga|pakka\s+(kar|bhej))\b"""),
        Regex("""(?i)\bguarantee\b|\bpromise\b"""),
    )
    private val dateTimePatterns = listOf(
        Regex("""(?i)\b(tomorrow|kal|parso|aaj shaam|tonight|next week|agle hafte)\b"""),
        Regex("""(?i)\b\d{1,2}\s*(am|pm|baje|o'clock)\b"""),
        Regex("""\b\d{1,2}[:/.-]\d{1,2}\b"""),
    )
    private val urlPatterns = listOf(
        Regex("""(?i)https?://\S+"""),
        Regex("""(?i)\bwww\.\S+"""),
    )
    private val emailPatterns = listOf(Regex("""(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}"""))
    private val phonePatterns = listOf(Regex("""(?<!\d)(?:\+?\d[\d\s-]{8,}\d)(?!\d)"""))
    private val otpPatterns = listOf(
        Regex("""(?i)\b(otp|one[\s-]?time\s+password|verification\s+code|2fa)\b"""),
        Regex("""\b\d{4,8}\b"""),
    )
    private val aadhaarPatterns = listOf(Regex("""\b\d{4}\s?\d{4}\s?\d{4}\b"""))
    private val sensitiveTopics = listOf(
        Regex("""(?i)\b(aadhaar|aadhar|pan number|passport|bank account|cvv|pin code)\b"""),
        Regex("""(?i)\b(diagnosis|medicine|dosage|legal advice|court order)\b"""),
    )

    fun check(
        draft: String,
        category: ReplyCategory,
        inboundLanguage: String = "unknown",
        requestedLanguage: String = "auto",
        maxChars: Int = 320,
    ): Verdict {
        if (draft.isBlank()) return Verdict(false, listOf("Draft khali hai"))
        val reasons = mutableListOf<String>()

        if (draft.length > maxChars) reasons += "Draft bahut lamba hai (${draft.length} > $maxChars chars)"
        if (draft.length > 220) reasons += "Auto-reply 220 chars se chhota hona chahiye"
        if (draft.count { it == '\uD83D' || it.code in 0x1F300..0x1FAFF } > 4) reasons += "Bahut zyada emoji"
        if (draft.count { it.isUpperCase() } > draft.length / 2 && draft.length > 20) reasons += "ALL CAPS"

        // OTP wording AND any 4+ digit run is forbidden in EVERY category — a greeting must never
        // echo a code either ("Your code is 482913" is exactly the message we must not mirror).
        if (otpPatterns.any { it.containsMatchIn(draft) }) reasons += "OTP/digits pattern mila"
        if (Regex("""\d{4,}""").containsMatchIn(draft)) reasons += "4+ digit sequence mila"
        if (moneyPatterns.any { it.containsMatchIn(draft) }) reasons += "Paise/amount ka zikr"
        if (promisePatterns.any { it.containsMatchIn(draft) }) reasons += "Promise/commitment ka zikr"
        if (category != ReplyCategory.SCHEDULING && dateTimePatterns.any { it.containsMatchIn(draft) }) {
            reasons += "Date/time ka zikr (scheduling ke bina)"
        }
        if (urlPatterns.any { it.containsMatchIn(draft) }) reasons += "URL mila"
        if (emailPatterns.any { it.containsMatchIn(draft) }) reasons += "Email mila"
        if (phonePatterns.any { it.containsMatchIn(draft) }) reasons += "Phone number mila"
        if (aadhaarPatterns.any { it.containsMatchIn(draft) }) reasons += "Aadhaar-like number mila"
        if (sensitiveTopics.any { it.containsMatchIn(draft) }) reasons += "Sensitive personal data ka zikr"

        // Language mismatch: if inbound was Devanagari and draft is pure Latin (or vice versa)
        if (requestedLanguage == "auto") {
            val inboundDevanagari = inboundLanguage.contains("hi-deva", true) || inboundLanguage == "hi"
            val draftDevanagari = draft.any { it.code in 0x0900..0x097F }
            if (inboundDevanagari && !draftDevanagari) {
                // allow Hinglish for the common greeting templates — only flag pure mismatch on long drafts
                if (draft.length > 60) reasons += "Inbound Devanagari thi, draft Roman me hai"
            }
        }

        return Verdict(reasons.isEmpty(), reasons)
    }

    /** Last-resort sanitisation used only for the deterministic templates. */
    fun sanitize(text: String, maxChars: Int = 220): String = text
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(maxChars)
}
