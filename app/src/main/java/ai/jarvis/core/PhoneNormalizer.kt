package ai.jarvis.core

/** Pure, unit-tested phone normalisation used by every recipient path (chat, voice, bulk). */
object PhoneNormalizer {

    data class Result(
        val chatId: String?,
        val digits: String,
        val valid: Boolean,
        val reason: String? = null,
    )

    fun normalize(raw: String, defaultCountryCode: String = "91"): Result {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return Result(null, "", false, "empty")
        if (cleaned.contains('@')) {
            val digits = Numbers.digits(cleaned.substringBefore('@'))
            return Result(cleaned, digits, digits.length in 5..15, if (digits.length in 5..15) null else "bad chatId")
        }
        var s = cleaned.removePrefix("whatsapp:").removePrefix("tel:").removePrefix("sip:")
        val hadPlus = s.trimStart().startsWith("+")
        var digits = Numbers.digits(s)
        if (digits.isEmpty()) return Result(null, "", false, "no digits")
        if (digits.startsWith("00")) { digits = digits.drop(2); }
        else if (!hadPlus && digits.length == 10) digits = defaultCountryCode + digits
        else if (!hadPlus && digits.length == 11 && digits.startsWith("0")) digits = defaultCountryCode + digits.drop(1)
        else if (!hadPlus && digits.length == 12 && digits.startsWith(defaultCountryCode)) { /* already cc+10 */ }

        if (digits.length < 8) return Result(null, digits, false, "too short")
        if (digits.length > 15) return Result(null, digits, false, "too long")
        if (digits.startsWith("0")) return Result(null, digits, false, "leading zero after normalisation")
        return Result("$digits@c.us", digits, true)
    }

    /** Human display: +91 98765 43210 */
    fun pretty(digits: String): String {
        val d = digits.filter { it.isDigit() }
        if (d.length < 10) return d
        val cc = d.dropLast(10)
        val rest = d.takeLast(10)
        val grouped = rest.chunked(5).joinToString(" ")
        return if (cc.isEmpty()) grouped else "+$cc $grouped"
    }

    fun digitsOfCallerJid(jid: String): String = jid.substringBefore('@').substringBefore('_')
}
