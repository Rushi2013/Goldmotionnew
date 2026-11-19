package ai.jarvis.data.contacts

import ai.jarvis.core.PhoneNormalizer
import kotlin.math.max

/**
 * §6 — Recipient resolution. Merges three sources and never guesses:
 *  1. Android contacts (READ_CONTACTS, only when granted)
 *  2. OpenWA contacts (WhatsApp's own address book)
 *  3. Past chats (people/groups already talked to)
 *
 * Matching ladder: exact name 1.00 → exact number 1.00 → all tokens ⊂ name 0.90 →
 * last/first token 0.85 → Jaro-Winkler ≥0.92 (typos) 0.70–0.84 → Devanagari transliteration 0.80.
 * `score ≥ 0.92 && unique` → auto-select; 0.60–0.92 or multiple hits → disambiguation.
 */
data class Candidate(
    val chatId: String,
    val displayName: String,
    val number: String = "",
    val source: String,               // android | openwa | chat
    val isGroup: Boolean = false,
    val isBusiness: Boolean = false,
    val score: Double = 0.0,
) {
    val maskedNumber: String get() = ai.jarvis.core.Numbers.mask(number.ifBlank { chatId.substringBefore('@') })
}

data class MatchResult(
    val query: String,
    val candidates: List<Candidate>,
    val autoSelected: Candidate?,
    val needsDisambiguation: Boolean,
    val nothingFound: Boolean,
) {
    val best: Candidate? get() = candidates.firstOrNull()
}

class RecipientResolver {

    fun resolve(query: String, pool: List<Candidate>, defaultCountryCode: String = "91"): MatchResult {
        val q = normalize(query)
        if (q.isBlank()) return MatchResult(query, emptyList(), null, false, true)

        // exact number → chatId wins immediately
        val asChat = PhoneNormalizer.normalize(query, defaultCountryCode)
        if (asChat.valid && asChat.chatId != null) {
            val exact = pool.firstOrNull { it.chatId == asChat.chatId }
            if (exact != null) return MatchResult(query, listOf(exact.copy(score = 1.0)), exact.copy(score = 1.0), false, false)
            if (ai.jarvis.core.Numbers.digits(query).length >= 8) {
                val fresh = Candidate(
                    chatId = asChat.chatId!!,
                    displayName = PhoneNormalizer.pretty(asChat.digits),
                    number = asChat.digits,
                    source = "manual",
                    score = 1.0,
                )
                return MatchResult(query, listOf(fresh), fresh, false, false)
            }
        }

        val scored = pool.mapNotNull { c ->
            val s = scoreAgainst(q, c)
            if (s < 0.60) null else c.copy(score = s)
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) return MatchResult(query, emptyList(), null, false, true)

        val top = scored.first()
        val near = scored.filter { it.score >= 0.60 }
        val unique = near.count { it.score >= 0.92 } == 1 && top.score >= 0.92
        return if (unique) MatchResult(query, scored, top, false, false)
        else MatchResult(query, scored.take(12), null, true, false)
    }

    private fun scoreAgainst(q: String, c: Candidate): Double {
        val name = normalize(c.displayName)
        val number = c.number.ifBlank { c.chatId.substringBefore('@') }
        val qDigits = ai.jarvis.core.Numbers.digits(q)

        if (qDigits.length >= 8 && qDigits == ai.jarvis.core.Numbers.digits(number)) return 1.0
        if (name == q) return 1.0
        if (name.isBlank()) return 0.0

        val qTokens = q.split(' ').filter { it.isNotBlank() }
        val nTokens = name.split(' ').filter { it.isNotBlank() }

        if (qTokens.isNotEmpty() && qTokens.all { qt -> nTokens.any { it == qt } }) return 0.90
        if (qTokens.any { qt -> nTokens.firstOrNull() == qt } || qTokens.any { qt -> nTokens.lastOrNull() == qt }) return 0.85

        val jw = jaroWinkler(q, name)
        if (jw >= 0.92) return 0.70 + (jw - 0.92) * 1.75   // 0.70 → 0.84

        val translit = transliterate(q)
        if (translit != q && (jaroWinkler(translit, name) >= 0.92 || name.contains(translit))) return 0.80

        // prefix match on any token e.g. "pra" → "pramila"
        if (q.length >= 3 && nTokens.any { it.startsWith(q) }) return 0.66
        return 0.0
    }

    fun normalize(s: String): String = s
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .split(' ')
        .filterNot { it in HONORIFICS }
        .joinToString(" ")

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchDistance = max(a.length, b.length) / 2 - 1
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = max(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatched[j] || a[i] != b[j]) continue
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Very small Devanagari → Latin mapping, enough for common Indian names. */
    fun transliterate(input: String): String {
        if (input.none { it.code in 0x0900..0x097F }) return input
        val map = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
            'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
            'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ं' to "n",
            'ँ' to "n", 'ः' to "h", '्' to "",
        )
        val sb = StringBuilder()
        for (ch in input) {
            sb.append(map[ch] ?: if (ch.code in 0x0900..0x097F) "" else ch.toString())
        }
        return normalize(sb.toString())
    }

    companion object {
        val HONORIFICS = setOf(
            "ji", "mr", "mrs", "ms", "shri", "sri", "bhai", "didi", "sir", "madam", "shree", "dr",
        )
    }
}
