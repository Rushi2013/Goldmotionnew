package ai.jarvis

import ai.jarvis.domain.policy.ReplyCategory
import ai.jarvis.domain.policy.RuleClassifier
import ai.jarvis.domain.policy.SafetyLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierAndSafetyTest {

    @Test
    fun classifiesSensitiveBeforeLightCategories() {
        // Contains "otp" and "bhejo" — must never be a greeting
        val c = RuleClassifier.classify("Payment ka OTP bhejo please")
        assertEquals(ReplyCategory.OTP_SECURITY, c.category)

        assertEquals(ReplyCategory.WORK_OFFICE, RuleClassifier.classify("kal ki report bhej do").category)
        assertEquals(ReplyCategory.MONEY_PAYMENT, RuleClassifier.classify("Refund kab milega?").category)
        assertEquals(ReplyCategory.LEGAL_MEDICAL, RuleClassifier.classify("Doctor ne dawai badal di").category)
        assertEquals(ReplyCategory.URGENT, RuleClassifier.classify("URGENT abhi call karo").category)
    }

    @Test
    fun classifiesLightCategories() {
        assertEquals(ReplyCategory.GREETING, RuleClassifier.classify("Namaste ji").category)
        assertEquals(ReplyCategory.GREETING, RuleClassifier.classify("Good morning").category)
        assertEquals(ReplyCategory.CONGRATULATION, RuleClassifier.classify("Congratulations! 🎉").category)
        assertEquals(ReplyCategory.THANKS, RuleClassifier.classify("Thank you so much").category)
        assertEquals(ReplyCategory.THANKS, RuleClassifier.classify("shukriya").category)
        assertEquals(ReplyCategory.FAREWELL, RuleClassifier.classify("good night").category)
    }

    @Test
    fun devanagariIsClassified() {
        val c = RuleClassifier.classify("नमस्ते, कैसे हो?")
        assertTrue(c.category == ReplyCategory.GREETING || c.category == ReplyCategory.SMALL_TALK)
        assertEquals("hi-deva", c.language)
    }

    @Test
    fun spamIsDetected() {
        assertEquals(
            ReplyCategory.SPAM_SCAM,
            RuleClassifier.classify("Congratulations, you have won a lucky draw prize money!").category,
        )
    }

    @Test
    fun safetyLintBlocksMoney() {
        val v = SafetyLint.check("Aap ₹500 bhej do", ReplyCategory.GREETING)
        assertFalse(v.safe)
        assertTrue(v.reasons.any { it.contains("Paise") })
    }

    @Test
    fun safetyLintBlocksPromises() {
        val v = SafetyLint.check("Main kal report bhej dunga", ReplyCategory.GREETING)
        assertFalse(v.safe)
    }

    @Test
    fun safetyLintBlocksUrlsAndPhones() {
        assertFalse(SafetyLint.check("Check https://evil.example", ReplyCategory.GREETING).safe)
        assertFalse(SafetyLint.check("Call me on 9876543210", ReplyCategory.GREETING).safe)
        assertFalse(SafetyLint.check("Mail at a@b.com", ReplyCategory.GREETING).safe)
    }

    @Test
    fun safetyLintBlocksOtpDigits() {
        assertFalse(SafetyLint.check("Your code is 482913", ReplyCategory.GREETING).safe)
    }

    @Test
    fun safetyLintAllowsShortTemplates() {
        assertTrue(SafetyLint.check("Thank you so much! 🙏", ReplyCategory.CONGRATULATION).safe)
        assertTrue(SafetyLint.check("👍", ReplyCategory.OK_ACK).safe)
        assertTrue(SafetyLint.check("You're welcome! 😊", ReplyCategory.THANKS).safe)
        assertTrue(SafetyLint.check("Namaste! Main JARVIS hoon.", ReplyCategory.GREETING).safe)
    }

    @Test
    fun safetyLintBlocksLongDrafts() {
        val long = "a".repeat(400) + " ji"
        assertFalse(SafetyLint.check(long, ReplyCategory.GREETING).safe)
    }
}
