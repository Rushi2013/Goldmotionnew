package ai.jarvis.whatsapp.bulk

import ai.jarvis.core.Numbers
import ai.jarvis.core.PhoneNormalizer

/** One parsed recipient line, before validation. */
data class ParsedRecipient(
    val raw: String,
    val chatId: String,
    val digits: String,
    val name: String? = null,
    val vars: Map<String, String> = emptyMap(),
    val isGroup: Boolean = false,
    val valid: Boolean = true,
    val reason: String? = null,
)

data class ParseSummary(
    val valid: List<ParsedRecipient>,
    val invalid: List<ParsedRecipient>,
    val duplicates: Int,
    val groups: List<ParsedRecipient>,
    val hadHeader: Boolean,
)

/**
 * §8.2 — four ways to input recipients. This handles the two text ones (paste + CSV/TXT),
 * plus header auto-detection and variable columns.
 *
 * Accepts per line: `number`, `number,name`, `name,number`, `+91 98765 43210`,
 * `chatId@c.us`, and full CSV rows with a header.
 */
object RecipientInputParser {

    private val numberHeaders = listOf("number", "phone", "mobile", "whatsapp", "contact", "msisdn", "number ", "phone number")
    private val nameHeaders = listOf("name", "first_name", "firstname", "contact_name", "fullname", "var1")

    fun parsePasted(text: String, defaultCountryCode: String = "91"): ParseSummary {
        val lines = text.split('\n', '\r')
            .flatMap { if (it.contains(';') && !it.contains(',')) it.split(';') else listOf(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val entries = LinkedHashMap<String, ParsedRecipient>()
        for (line in lines) {
            val (number, name) = splitLoose(line)
            val r = toRecipient(number, name, defaultCountryCode)
            // keep the first occurrence of a chatId; duplicates are counted in build()
            entries.putIfAbsent(r.chatId.ifBlank { "invalid:${r.raw}" }, r)
        }
        return build(entries, defaultCountryCode)
    }

    fun parseCsv(content: String, defaultCountryCode: String = "91"): ParseSummary {
        val clean = content.removePrefix("\uFEFF")
        val rows = CsvStreamParser.parse(clean)
        if (rows.isEmpty()) return ParseSummary(emptyList(), emptyList(), 0, emptyList(), false)

        val first = rows.first().map { it.trim().lowercase() }
        val headerIsNumber = first.any { h -> numberHeaders.any { h.contains(it) } }
        val numberCol = first.indexOfFirst { h -> numberHeaders.any { h.contains(it) } }
        val nameCol = first.indexOfFirst { h -> nameHeaders.any { it == h } }
        val varCols = first.withIndex().filter { (i, h) -> i != numberCol && i != nameCol && h.isNotBlank() }

        val entries = LinkedHashMap<String, ParsedRecipient>()
        val start = if (headerIsNumber) 1 else 0
        for (r in start until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue
            val rawNumber = if (headerIsNumber && numberCol >= 0) row.getOrElse(numberCol) { "" }
            else row.firstOrNull { it.any { ch -> ch.isDigit() } } ?: ""
            if (rawNumber.isBlank()) continue
            val name = when {
                nameCol >= 0 -> row.getOrNull(nameCol)?.trim()?.takeIf { it.isNotBlank() }
                !headerIsNumber && row.size >= 2 -> row.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                else -> null
            }
            val vars = varCols.mapNotNull { (i, h) ->
                row.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { h to it.trim() }
            }.toMap()
            val parsed = toRecipient(rawNumber, name, defaultCountryCode).copy(vars = vars)
            val key = parsed.chatId
            if (!entries.containsKey(key)) entries[key] = parsed
        }
        return build(entries.mapValues { it.value }, defaultCountryCode, hadHeader = headerIsNumber)
    }

    private fun build(entries: Map<String, ParsedRecipient>, defaultCountryCode: String, hadHeader: Boolean = false): ParseSummary {
        val valid = mutableListOf<ParsedRecipient>()
        val invalid = mutableListOf<ParsedRecipient>()
        val groups = mutableListOf<ParsedRecipient>()
        val seen = HashSet<String>()
        var duplicates = 0

        for ((_, p) in entries) {
            when {
                !p.valid -> invalid += p
                p.isGroup -> groups += p
                !seen.add(p.chatId) -> duplicates++
                else -> valid += p
            }
        }
        return ParseSummary(valid, invalid, duplicates, groups, hadHeader)
    }

    private fun splitLoose(line: String): Pair<String, String?> {
        // "number,name" | "name,number" | "number;name" | "number\tname"
        val seps = listOf(',', '\t', '|', ';')
        for (sep in seps) {
            if (line.contains(sep)) {
                val parts = line.split(sep).map { it.trim() }
                if (parts.size < 2) continue
                return if (parts[0].any { it.isDigit() } && parts[0].count { it.isDigit() } >= 5) {
                    parts[0] to parts.drop(1).joinToString(",").trim().takeIf { it.isNotBlank() }
                } else {
                    parts[1] to parts[0].takeIf { it.isNotBlank() }
                }
            }
        }
        return line.trim() to null
    }

    private fun toRecipient(rawNumber: String, name: String?, defaultCountryCode: String): ParsedRecipient {
        val trimmed = rawNumber.trim()
        if (Numbers.isGroupChatId(trimmed)) {
            val digits = Numbers.digits(trimmed)
            return ParsedRecipient(trimmed, trimmed, digits, name, isGroup = true, valid = digits.length > 5)
        }
        if (trimmed.contains('@')) {
            val digits = Numbers.digits(trimmed.substringBefore('@'))
            val valid = digits.length in 8..15
            return ParsedRecipient(trimmed, trimmed, digits, name, valid = valid, reason = if (valid) null else "bad chat id")
        }
        val n = PhoneNormalizer.normalize(trimmed, defaultCountryCode)
        return if (n.valid && n.chatId != null) {
            ParsedRecipient(trimmed, n.chatId, n.digits, name)
        } else {
            ParsedRecipient(trimmed, "", Numbers.digits(trimmed), name, valid = false, reason = n.reason ?: "invalid number")
        }
    }

    /** Validation state used by the UI: VALID | NOT_ON_WHATSAPP | INVALID_FORMAT | UNKNOWN. */
    enum class ValidationState { VALID, NOT_ON_WHATSAPP, INVALID_FORMAT, UNKNOWN }

    /** Replaces `{{name}} {{number}} {{date}}` and per-recipient variables. */
    fun renderTemplate(template: String, recipient: ParsedRecipient): String {
        var out = template
            .replace("{{name}}", recipient.name ?: "")
            .replace("{{number}}", recipient.digits)
            .replace("{{date}}", java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date()))
        recipient.vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        // strip any unresolved variable so we never send "{{foo}}" to a human
        out = out.replace(Regex("""\{\{\w+\}\}"""), "").replace(Regex("""\s+"""), " ").trim()
        return out
    }
}

/** Minimal streaming-capable RFC4180 parser: quoted commas, escaped quotes, BOM, CRLF. */
object CsvStreamParser {
    fun parse(content: String, maxRows: Int = 10_000): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        val s = content.removePrefix("\uFEFF")
        while (i < s.length && rows.size < maxRows) {
            val c = s[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ }
                        else inQuotes = false
                    } else field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\n' -> {
                    row.add(field.toString().trimEnd('\r'))
                    rows.add(row.toList())
                    row.clear(); field.setLength(0)
                }
                c == '\r' -> { /* handled with \n */ }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
