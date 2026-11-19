package ai.jarvis

import ai.jarvis.domain.intents.Calculator
import ai.jarvis.domain.intents.IntentName
import ai.jarvis.domain.intents.IntentParser
import ai.jarvis.domain.policy.RuleClassifier
import ai.jarvis.whatsapp.bulk.CsvStreamParser
import ai.jarvis.whatsapp.bulk.RecipientInputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §10.3 utterance fixtures — rules-only path must pass every row. */
class IntentAndBulkTest {

    private val parser = IntentParser()

    @Test
    fun sendsMessageFixtures() {
        listOf(
            "Send hi to Pramila on WhatsApp",
            "WhatsApp par Pramila ko hi bhej",
            "Pramila Sapkal ko WhatsApp pe hello send kar",
            "Hey Jarvis, can you send a hi message to Pramila?",
        ).forEach { utterance ->
            val r = parser.parse(utterance)
            assertEquals("$utterance", IntentName.SEND_WHATSAPP_MESSAGE, r.intent)
            assertNotNull("$utterance recipient", r.slots.recipientName)
            assertTrue("$utterance should name Pramila: ${r.slots.recipientName}", r.slots.recipientName!!.contains("ramila"))
        }
    }

    @Test
    fun devanagariSend() {
        val r = parser.parse("प्रमिला को व्हाट्सएप पर हाय भेजो")
        assertEquals(IntentName.SEND_WHATSAPP_MESSAGE, r.intent)
    }

    @Test
    fun autoReplyFixtures() {
        listOf("auto reply on kar do", "sabko auto reply do", "auto reply sabke liye chalu").forEach {
            assertEquals(it, IntentName.AUTOREPLY_ON, parser.parse(it).intent)
        }
        assertEquals(IntentName.AUTOREPLY_OFF, parser.parse("auto reply off").intent)
        assertEquals(IntentName.AUTOREPLY_STATUS, parser.parse("auto reply status").intent)
        assertEquals(IntentName.AUTOREPLY_STATUS, parser.parse("kaun kaun auto reply me hai").intent)
    }

    @Test
    fun perContactAutoReply() {
        val on = parser.parse("Pramila ke liye auto reply on")
        assertEquals(IntentName.AUTOREPLY_FOR_CONTACT_ON, on.intent)
        assertEquals("Pramila", on.slots.recipientName)

        val off = parser.parse("Pramila ka auto reply band karo")
        assertEquals(IntentName.AUTOREPLY_FOR_CONTACT_OFF, off.intent)
    }

    @Test
    fun bulkFixture() {
        val r = parser.parse("sabko good morning bhej do")
        assertEquals(IntentName.SEND_BULK, r.intent)
        assertEquals("TEXT", r.slots.messageType)
        assertEquals("ALL_CONTACTS", r.slots.recipientSet)
        assertNotNull(r.slots.message)
    }

    @Test
    fun timeAndDate() {
        assertEquals(IntentName.GET_TIME, parser.parse("kitne baje hain").intent)
        assertEquals(IntentName.GET_DATE, parser.parse("aaj kaunsi tarikh").intent)
    }

    @Test
    fun calculatorIsSafeAndCorrect() {
        assertEquals(IntentName.CALCULATE, parser.parse("12 * 8 + 4").intent)
        assertEquals(100.0, Calculator.evaluate("12 * 8 + 4")!!, 0.0001)
        assertEquals(7.0, Calculator.evaluate("(3 + 4)")!!, 0.0001)
        assertEquals(2.0, Calculator.evaluate("10 / 5")!!, 0.0001)
        assertEquals(8.0, Calculator.evaluate("2 ^ 3")!!, 0.0001)
        // rejection: identifiers, eval-like input, division by zero
        assertNull(Calculator.extractExpression("runtime.error()"))
        assertNull(Calculator.extractExpression("import os"))
        assertNull(Calculator.evaluate("5 / 0"))
    }

    @Test
    fun confirmationsNeedPendingAction() {
        // No pending action → "haan" must NOT be treated as a confirmation for anything dangerous
        val r = parser.parse("haan bhej do")
        assertTrue(r.intent != IntentName.CONFIRM_YES)

        val pending = ai.jarvis.domain.intents.PendingAction(parser.parse("Pramila ko hi bhej"))
        assertEquals(IntentName.CONFIRM_YES, parser.parse("haan bhej do", pending).intent)
        assertEquals(IntentName.CONFIRM_NO, parser.parse("nahi mat bhejo", pending).intent)
    }

    @Test
    fun reminderParsing() {
        val r = parser.parse("kal 6 baje mom ko call karne ka reminder")
        assertEquals(IntentName.CREATE_REMINDER, r.intent)
        assertNotNull(r.slots.reminderAtEpochMs)
    }

    @Test
    fun pollParsing() {
        val r = parser.parse("poll banao: lunch ya dinner")
        assertEquals(IntentName.CREATE_POLL, r.intent)
        assertEquals(2, r.slots.pollOptions.size)
    }

    @Test
    fun pastedNumbersAreParsed() {
        val text = """
            919812345678
            919876543210, Rahul
            +91 90000 11111
            12345
            919812345678
        """.trimIndent()
        val p = RecipientInputParser.parsePasted(text)
        assertEquals(3, p.valid.size)
        assertEquals(1, p.invalid.size)
        assertEquals(1, p.duplicates)
        assertEquals("Rahul", p.valid.first { it.name == "Rahul" }.name)
    }

    @Test
    fun csvWithHeaderAndQuotes() {
        val csv = "\uFEFFphone,name,city\r\n\"919812345678\",\"Pramila, Sapkal\",\"Pune\"\r\n919900001111,Rahul,Mumbai\r\n"
        val rows = CsvStreamParser.parse(csv)
        assertEquals(3, rows.size)
        assertEquals("Pramila, Sapkal", rows[1][1])

        val p = RecipientInputParser.parseCsv(csv)
        assertTrue(p.hadHeader)
        assertEquals(2, p.valid.size)
        assertEquals("Pramila, Sapkal", p.valid.first().name)
        assertEquals("Pune", p.valid.first().vars["city"])
    }

    @Test
    fun templateRendering() {
        val recipient = RecipientInputParser.parsePasted("919812345678, Pramila").valid.first()
        val out = RecipientInputParser.renderTemplate("Namaste {{name}}, aapka {{number}} {{unknown}}", recipient)
        assertTrue(out.contains("Pramila"))
        assertTrue(out.contains("919812345678"))
        assertTrue(!out.contains("{{"))
    }

    @Test
    fun languageDetection() {
        assertEquals("hi-deva", RuleClassifier.languageOf("नमस्ते कैसे हो"))
        assertEquals("hinglish", RuleClassifier.languageOf("bhai kya haal hai"))
        assertEquals("en", RuleClassifier.languageOf("Hello, how are you doing today"))
    }
}
