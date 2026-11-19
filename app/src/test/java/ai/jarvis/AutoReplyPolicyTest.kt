package ai.jarvis

import ai.jarvis.core.AutoReplyMode
import ai.jarvis.domain.policy.AutoReplyPolicy
import ai.jarvis.domain.policy.Classification
import ai.jarvis.domain.policy.PolicyContext
import ai.jarvis.domain.policy.PolicyVerdict
import ai.jarvis.domain.policy.ReplyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the §7.5 policy matrix. */
class AutoReplyPolicyTest {

    private fun ctx(
        mode: AutoReplyMode = AutoReplyMode.ALL,
        allow: Boolean = false,
        enabled: Set<ReplyCategory> = emptySet(),
        hasFact: Boolean = false,
        hasPrice: Boolean = false,
        question: Boolean = false,
    ) = PolicyContext(
        mode = mode,
        chatInAllowlist = allow,
        userEnabledCategories = enabled,
        hasMatchingSavedFact = hasFact,
        hasApprovedPriceSheet = hasPrice,
        inboundIsQuestion = question,
    )

    private fun cls(cat: ReplyCategory, conf: Double = 0.9, requiresHuman: Boolean = false, wantsAction: Boolean = false) =
        Classification(cat, conf, requiresHuman = requiresHuman, wantsAction = wantsAction)

    @Test
    fun autoCategoriesAreAutoSent() {
        listOf(
            ReplyCategory.GREETING, ReplyCategory.CONGRATULATION, ReplyCategory.THANKS,
            ReplyCategory.SMALL_TALK, ReplyCategory.OK_ACK, ReplyCategory.FAREWELL,
        ).forEach { cat ->
            assertEquals("$cat should auto-send", PolicyVerdict.AutoSend, AutoReplyPolicy.decide(cls(cat), ctx()))
        }
    }

    @Test
    fun sensitiveCategoriesAlwaysAsk() {
        listOf(
            ReplyCategory.WORK_OFFICE, ReplyCategory.MONEY_PAYMENT, ReplyCategory.LEGAL_MEDICAL,
            ReplyCategory.COMPLAINT_ANGER, ReplyCategory.PERSONAL_EMOTIONAL, ReplyCategory.URGENT,
            ReplyCategory.BUSINESS_ORDER,
        ).forEach { cat ->
            val verdict = AutoReplyPolicy.decide(cls(cat), ctx())
            assertTrue("$cat must escalate, got $verdict", verdict is PolicyVerdict.AskUser)
        }
    }

    @Test
    fun otpAlwaysSilentEvenWithHighConfidence() {
        val verdict = AutoReplyPolicy.decide(cls(ReplyCategory.OTP_SECURITY, 0.99), ctx())
        assertTrue(verdict is PolicyVerdict.Silence)
    }

    @Test
    fun spamIsSilent() {
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.SPAM_SCAM, 0.99), ctx()) is PolicyVerdict.Silence)
    }

    @Test
    fun unknownAndLowConfidenceEscalate() {
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.UNKNOWN, 0.9), ctx()) is PolicyVerdict.AskUser)
        assertTrue(
            "confidence <0.6 must escalate even for GREETING",
            AutoReplyPolicy.decide(cls(ReplyCategory.GREETING, 0.4), ctx()) is PolicyVerdict.AskUser,
        )
        assertTrue(
            "requiresHuman must escalate",
            AutoReplyPolicy.decide(cls(ReplyCategory.GREETING, 0.95, requiresHuman = true), ctx()) is PolicyVerdict.AskUser,
        )
    }

    @Test
    fun modeOffBlocksEverything() {
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), ctx(mode = AutoReplyMode.OFF)) is PolicyVerdict.Block)
    }

    @Test
    fun allowlistModeRequiresMembership() {
        assertTrue(
            AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), ctx(mode = AutoReplyMode.ALLOWLIST, allow = false)) is PolicyVerdict.Block,
        )
        assertEquals(
            PolicyVerdict.AutoSend,
            AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), ctx(mode = AutoReplyMode.ALLOWLIST, allow = true)),
        )
    }

    @Test
    fun fromMeAlwaysBlocked() {
        val context = ctx().copy(fromMe = true)
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), context) is PolicyVerdict.Block)
    }

    @Test
    fun groupsBlockedUnlessAllowlistedAndMentioned() {
        val group = ctx().copy(isGroupMessage = true, mentionsMe = true, chatInAllowlist = false)
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), group) is PolicyVerdict.Block)

        val allowNoMention = ctx().copy(isGroupMessage = true, mentionsMe = false, chatInAllowlist = true)
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), allowNoMention) is PolicyVerdict.Block)

        val allowMention = ctx().copy(isGroupMessage = true, mentionsMe = true, chatInAllowlist = true)
        assertEquals(PolicyVerdict.AutoSend, AutoReplyPolicy.decide(cls(ReplyCategory.GREETING), allowMention))
    }

    @Test
    fun priceRequiresApprovedSheet() {
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.PRICE_INQUIRY), ctx()) is PolicyVerdict.AskUser)
        assertTrue(
            AutoReplyPolicy.decide(
                cls(ReplyCategory.PRICE_INQUIRY),
                ctx(enabled = setOf(ReplyCategory.PRICE_INQUIRY), hasPrice = true),
            ) is PolicyVerdict.AutoSend,
        )
    }

    @Test
    fun infoRequestNeedsSavedFact() {
        assertTrue(AutoReplyPolicy.decide(cls(ReplyCategory.INFO_REQUEST), ctx()) is PolicyVerdict.AskUser)
        assertTrue(
            AutoReplyPolicy.decide(cls(ReplyCategory.INFO_REQUEST), ctx(hasFact = true)) is PolicyVerdict.AutoSend,
        )
    }

    @Test
    fun schedulingNeverAutoPromises() {
        assertTrue(
            AutoReplyPolicy.decide(
                cls(ReplyCategory.SCHEDULING),
                ctx(enabled = setOf(ReplyCategory.SCHEDULING)),
            ) is PolicyVerdict.AskUser,
        )
    }

    @Test
    fun templatesAreSafe() {
        val greeting = AutoReplyPolicy.templateFor(ReplyCategory.GREETING, "hi", "Pramila", "Namaste! Main JARVIS hoon.")
        assertEquals("Namaste! Main JARVIS hoon.", greeting)
        val congrats = AutoReplyPolicy.templateFor(ReplyCategory.CONGRATULATION, "Congratulations!", "Pramila Sapkal", "")
        assertTrue(congrats.contains("Pramila"))
        assertTrue(congrats.contains("Thank you"))
        assertEquals("👍", AutoReplyPolicy.templateFor(ReplyCategory.OK_ACK, "ok", null, ""))
    }
}
