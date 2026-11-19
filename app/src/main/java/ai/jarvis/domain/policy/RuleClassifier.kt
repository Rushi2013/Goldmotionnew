package ai.jarvis.domain.policy

/**
 * Deterministic keyword/phonetic classifier — no model, no network, fully unit-testable.
 * Used (a) as the offline fallback when no LLM is configured, and (b) as the first pass that
 * the LLM only refines for ambiguous texts.
 *
 * Ordering matters: sensitive categories (OTP, money, work, legal) are checked before
 * light ones (greeting, thanks) so that "payment ka OTP bhejo" never becomes a greeting.
 */
object RuleClassifier {

    private data class Rule(val category: ReplyCategory, val keywords: List<String>, val weight: Double = 1.0)

    private val rules: List<Rule> = listOf(
        // 1. Highest priority, never-reply categories
        Rule(ReplyCategory.OTP_SECURITY, listOf(
            "otp", "one time password", "verification code", "verify code", "2fa", "two factor",
            "login code", "authentication code", "सत्यापन कोड", "ओटीपी", "password", "पासवर्ड",
        )),
        Rule(ReplyCategory.SPAM_SCAM, listOf(
            "lottery", "you have won", "you've won", "aap jeet gaye", "lucky draw", "prize money",
            "crypto investment", "double your money", "loan approved", "instant loan", "click this link",
            "free gift", "work from home earn", "part time job earn", "नेटवर्क मार्केटिंग",
        )),

        // 2. Work / office
        Rule(ReplyCategory.WORK_OFFICE, listOf(
            "report", "deadline", "client", "boss", "meeting", "presentation", "invoice", "salary",
            "office", "kaam", "काम", "रिपोर्ट", "ऑफिस", "मीटिंग", "बॉस", "project", "ppt", "excel sheet",
            "attendance", "leave application", "appraisal", "target achieve", "deliverable", "standup",
            "ticket", "jira", "email kar", "mail kar", "submit kar",
        )),
        // 3. Money
        Rule(ReplyCategory.MONEY_PAYMENT, listOf(
            "payment", "paise", "पैसे", "paisa", "upi", "gpay", "paytm", "phonepe", "refund", "amount",
            "receipt", "bill", "बिल", "bank", "बैंक", "account number", "ifsc", "transfer kar",
            "kitne rupaye", "रुपये", "emi", "loan", "balance", "pending payment", "invoice bhej",
        )),
        // 4. Legal / medical
        Rule(ReplyCategory.LEGAL_MEDICAL, listOf(
            "lawyer", "court", "fir", "legal notice", "advocate", "case", "वकील", "कोर्ट", "कानून",
            "doctor", "medicine", "dawai", "दवाई", "hospital", "अस्पताल", "health", "tabiyat", "तबीयत",
            "insurance", "policy", "operation", "surgery", "report aaya", "test result", "doctor ko",
        )),
        // 5. Complaint / anger
        Rule(ReplyCategory.COMPLAINT_ANGER, listOf(
            "complaint", "शिकायत", "shikayat", "gussa", "गुस्सा", "worst", "bakwas", "बकवास",
            "refund chahiye", "no response", "not working", "kharab", "खराब", "fake", "fraud", "cheated",
            "threat", "police", "complaint karunga", "unacceptable", "very disappointed", "waste of money",
        )),
        // 6. Personal / emotional
        Rule(ReplyCategory.PERSONAL_EMOTIONAL, listOf(
            "sorry", "maafi", "माफी", "breakup", "relationship", "condolence", "shok", "शोक",
            "ninda", "death", "passed away", "rip", "miss you", "yaad aa rahi", "alone", "depressed",
            "broke up", "divorce", "fight ho gaya", "rukh", "rona", "emotional",
        )),
        // 7. Urgent
        Rule(ReplyCategory.URGENT, listOf(
            "urgent", "अर्जेंट", "asap", "turant", "तुरंत", "abhi", "अभी", "emergency", "इमरजेंसी",
            "immediately", "right now", "jaldi", "जल्दी", "sos", "help me now", "missed call",
            "phone uthao", "call back abhi", "critical",
        )),
        // 8. Business order
        Rule(ReplyCategory.BUSINESS_ORDER, listOf(
            "order", "ऑर्डर", "delivery", "डिलीवरी", "stock", "स्टॉक", "quotation", "quote bhejo",
            "gst", "जीएसटी", "bill bana", "dispatch", "shipment", "wholesale", "rate list",
            "bulk order", "purchase order", "catalog bhej", "product chahiye",
        )),
        // 9. Price
        Rule(ReplyCategory.PRICE_INQUIRY, listOf(
            "price", "कीमत", "rate kya", "kitne ka", "कितने का", "kitna", "how much", "cost",
            "pricing", "quote", "kitne me milega", "discount", "offer kya", "best price",
        )),
        // 10. Scheduling
        Rule(ReplyCategory.SCHEDULING, listOf(
            "kal milte", "milte hai", "कब मिल", "meeting kab", "time kya", "slot", "book karna",
            "appointment", "schedule", "reschedule", "kab free", "kal aana", "aaj mil", "shaam ko mil",
            "5 baje", "6 baje", "baje mil",
        )),
        // 11. Info request
        Rule(ReplyCategory.INFO_REQUEST, listOf(
            "address", "पता", "pata kya", "timing", "shop kab khulta", "kaha hai", "कहाँ", "location kya",
            "number kya", "details bhejo", "kaise pahunchu", "direction", "open hai kya", "kitne baje tak",
        )),
        // 12. Farewell
        Rule(ReplyCategory.FAREWELL, listOf(
            "bye", "बाय", "good night", "गुड नाइट", "shubh ratri", "शुभ रात्रि", "milte hai",
            "मिलते हैं", "see you", "tata", "alvida", "chalta hu", "chalti hu", "gn", "tc", "take care",
        )),
        // 13. Thanks
        Rule(ReplyCategory.THANKS, listOf(
            "thank", "thanks", "thanx", "thx", "shukriya", "शुक्रिया", "dhanyavad", "धन्यवाद",
            "dhanyawad", "tysm", "ty", "appreciate", "grateful", "aabhar",
        )),
        // 14. Congratulation
        Rule(ReplyCategory.CONGRATULATION, listOf(
            "congrat", "congrats", "बधाई", "badhai", "badhaai", "mubarak", "मुबारक", "shaadi",
            "शादी", "wedding", "promotion", "result aaya", "topper", "passed", "selected",
            "naya ghar", "baby boy", "baby girl", "anniversary", "happy birthday", "janamdin",
        )),
        // 15. OK / ack
        Rule(ReplyCategory.OK_ACK, listOf(
            "ok", "okay", "okk", "hmm", "thik hai", "theek hai", "ठीक है", "noted", "noted👍",
            "got it", "samajh gaya", "accha", "अच्छा", "haan", "हाँ", "yes", "ji", "ji haan", "sahi hai",
        )),
        // 16. Small talk
        Rule(ReplyCategory.SMALL_TALK, listOf(
            "kaise ho", "kaisa hai", "कैसे हो", "kya haal", "क्या हाल", "how are you", "how's it going",
            "what's up", "wassup", "sup", "kya chal raha", "sab badhiya", "majama", "khana khaya",
            "weather", "mausam", "मौसम", "boring", "kya kar rahe",
        )),
        // 17. Greeting (lowest sensitivity of the auto group)
        Rule(ReplyCategory.GREETING, listOf(
            "hi", "hello", "hey", "hii", "hiii", "namaste", "नमस्ते", "namaskar", "नमस्कार",
            "good morning", "गुड मॉर्निंग", "gm", "good afternoon", "good evening", "salaam",
            "assalam", "sat sri akal", "जय श्री कृष्ण", "ram ram", "राम राम", "pranam", "प्रणाम",
        )),
    )

    /** Family terms imply LOVE_FAMILY only for casual banter (never for money/work). */
    private val familyTerms = listOf("mummy", "papa", "mumma", "pappa", "bhai", "behen", "didi", "bhaiya", "sis", "bro", "mom", "dad", "maa", "बेटा", "बेटी")

    fun classify(
        text: String,
        isGroup: Boolean = false,
        knownContact: Boolean = true,
        familyFlag: Boolean = false,
    ): Classification {
        val original = text.trim()
        val normalized = normalize(original)
        if (normalized.isBlank()) {
            return Classification(ReplyCategory.UNKNOWN, 0.0, languageOf(original), evidence = "(empty)")
        }

        val hits = mutableListOf<Triple<ReplyCategory, String, Int>>()
        for (rule in rules) {
            for (kw in rule.keywords) {
                val nkw = normalize(kw)
                val matched = when {
                    nkw.length <= 3 -> tokensOf(normalized).any { it == nkw }
                    else -> normalized.contains(nkw)
                }
                if (matched) hits += Triple(rule.category, kw, nkw.length)
            }
        }

        if (hits.isEmpty()) {
            // Question mark ⇒ likely info/work; otherwise unknown
            return Classification(
                category = ReplyCategory.UNKNOWN,
                confidence = 0.35,
                language = languageOf(original),
                wantsAction = original.contains("?"),
                evidence = "",
                source = "rules",
            )
        }

        // category priority = order in the rules list (sensitive first)
        val priorityIndex = rules.map { it.category }
        hits.sortWith(compareBy({ priorityIndex.indexOf(it.first) }, { -it.third }))
        val best = hits.first()
        val bestCategory = best.first
        val supporting = hits.count { it.first == bestCategory }

        // disambiguate family banter
        val looksFamily = familyTerms.any { normalized.contains(it) }
        val category = if (
            familyFlag && looksFamily &&
            bestCategory in setOf(ReplyCategory.GREETING, ReplyCategory.SMALL_TALK, ReplyCategory.THANKS, ReplyCategory.FAREWELL)
        ) ReplyCategory.LOVE_FAMILY else bestCategory

        val confidence = when {
            supporting >= 2 -> 0.90
            best.second.length >= 8 -> 0.85
            else -> 0.72
        }

        val wantsAction = original.contains("?") ||
            Regex("""(?i)\b(bhej|send|kar do|karo|batao|bata do|chahiye|please|pls)\b""").containsMatchIn(original)

        return Classification(
            category = category,
            confidence = confidence,
            language = languageOf(original),
            urgency = if (category == ReplyCategory.URGENT) 0.9 else 0.2,
            sentiment = if (category == ReplyCategory.COMPLAINT_ANGER) -0.6 else 0.1,
            wantsAction = wantsAction,
            requiresHuman = category in setOf(
                ReplyCategory.WORK_OFFICE, ReplyCategory.MONEY_PAYMENT, ReplyCategory.LEGAL_MEDICAL,
                ReplyCategory.COMPLAINT_ANGER, ReplyCategory.PERSONAL_EMOTIONAL, ReplyCategory.BUSINESS_ORDER,
            ),
            evidence = best.second,
            jobDetected = category == ReplyCategory.WORK_OFFICE,
            source = "rules",
        )
    }

    fun normalize(s: String): String = s
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun tokensOf(s: String): List<String> = s.split(' ').filter { it.isNotBlank() }

    /** Rough language of a single message (needed for language-matched drafts). */
    fun languageOf(text: String): String {
        if (text.any { it.code in 0x0900..0x097F }) return "hi-deva"
        val lower = text.lowercase()
        val hinglishMarkers = listOf(
            "hai", "hain", "kya", "aur", "nahi", "nahin", "kar", "karo", "karna", "mera", "meri",
            "aap", "tum", "bhai", "yaar", "accha", "haan", "theek", "bahut", "abhi", "kal", "aaj",
        )
        val markerCount = hinglishMarkers.count { lower.split(' ').contains(it) || lower.contains(" $it ") }
        return if (markerCount >= 2) "hinglish" else "en"
    }

    /** True when the text contains a clear question / action request. */
    fun isQuestion(text: String): Boolean =
        text.contains("?") || Regex("""(?i)\b(kya|kaise|kab|kahan|kaun|kitna|batao|bhejo|send|kar do)\b""").containsMatchIn(text)
}
