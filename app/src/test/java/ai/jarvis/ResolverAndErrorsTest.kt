package ai.jarvis

import ai.jarvis.core.ApiError
import ai.jarvis.core.ApiResult
import ai.jarvis.core.PhoneNormalizer
import ai.jarvis.data.api.HttpCore
import ai.jarvis.data.contacts.Candidate
import ai.jarvis.data.contacts.RecipientResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverAndErrorsTest {

    @Test
    fun normalizesIndianNumbers() {
        assertEquals("919812345678@c.us", PhoneNormalizer.normalize("9812345678").chatId)
        assertEquals("919812345678@c.us", PhoneNormalizer.normalize("+91 98123 45678").chatId)
        assertEquals("919812345678@c.us", PhoneNormalizer.normalize("09812345678").chatId)
        assertEquals("919812345678@c.us", PhoneNormalizer.normalize("0091 98123 45678").chatId)
        assertNotNull(PhoneNormalizer.normalize("120363001234567890@g.us").chatId)
        assertFalse(PhoneNormalizer.normalize("12345").valid)
        assertFalse(PhoneNormalizer.normalize("1234567890123456789").valid)
    }

    @Test
    fun resolverExactAndTypo() {
        val resolver = RecipientResolver()
        val pool = listOf(
            Candidate("919812345678@c.us", "Pramila Sapkal", "919812345678", "openwa"),
            Candidate("919876543210@c.us", "Pramila Deshmukh", "919876543210", "openwa"),
            Candidate("919900011111@c.us", "Rahul Sharma", "919900011111", "openwa"),
        )

        val exactNumber = resolver.resolve("+91 99000 11111", pool)
        assertNotNull(exactNumber.autoSelected)
        assertEquals("919900011111@c.us", exactNumber.autoSelected!!.chatId)

        // Two Pramilas → disambiguation, never a silent pick
        val ambiguous = resolver.resolve("Pramila", pool)
        assertNull(ambiguous.autoSelected)
        assertTrue(ambiguous.needsDisambiguation)
        assertEquals(2, ambiguous.candidates.size)

        // Typo path
        val typo = resolver.resolve("pramlaa", pool)
        assertTrue(typo.candidates.isNotEmpty())

        // Unique full name
        val unique = resolver.resolve("Rahul Sharma", pool)
        assertNotNull(unique.autoSelected)

        // Nothing
        assertTrue(resolver.resolve("zzzznobody", pool).nothingFound)
    }

    @Test
    fun jaroWinklerBasics() {
        val r = RecipientResolver()
        assertEquals(1.0, r.jaroWinkler("pramila", "pramila"), 0.0)
        assertTrue(r.jaroWinkler("pramila", "pramlaa") > 0.85)
        assertTrue(r.jaroWinkler("pramila", "zzzz") < 0.6)
    }

    @Test
    fun devanagariTransliteration() {
        val r = RecipientResolver()
        val out = r.transliterate("प्रमिला")
        assertTrue("expected romanisation, got $out", out.contains("r") && out.contains("m"))
    }

    @Test
    fun apiErrorParsesStringMessage() {
        val http = HttpCore({ "http://x/api" }, { "k" })
        val err = http.parseError(400, """{"statusCode":400,"message":"Session is not active. Start the session first.","error":"Bad Request"}""")
        assertEquals(400, err.status)
        assertTrue(err.message.contains("not active"))
        assertTrue("409-equivalent handling", err.needsSessionStart)
    }

    @Test
    fun apiErrorParsesArrayMessage() {
        val http = HttpCore({ "http://x/api" }, { "k" })
        val err = http.parseError(400, """{"statusCode":400,"message":["property foo should not exist","another error"],"error":"Bad Request"}""")
        assertTrue(err.message.contains("property foo should not exist"))
        assertTrue(err.message.contains("another error"))
    }

    @Test
    fun apiErrorHandlesEmptyAndHtmlBodies() {
        val http = HttpCore({ "http://x/api" }, { "k" })
        assertEquals(401, http.parseError(401, "").status)
        assertTrue(http.parseError(401, "").message.isNotBlank())
        val html = http.parseError(502, "<html>bad gateway</html>")
        assertTrue(html.message.contains("bad gateway"))
    }

    @Test
    fun errorFlagsAreTruthful() {
        assertEquals("API key rejected (401)", ApiError(401, "x").headline)
        assertTrue(ApiError(501, "x").isUnsupported)
        assertTrue(ApiError(403, "x").isScopeProblem)
        assertFalse(ApiError(200, "x").isAuthProblem)
        assertTrue(ApiError(0, "network", transport = true).transport)
    }

    @Test
    fun resultHelpers() {
        val ok: ApiResult<String> = ApiResult.ok("hi")
        assertEquals("hi", ok.valueOrNull())
        assertNull(ApiResult.Failure(ApiError(500, "no")).valueOrNull())
    }

    @Test
    fun urlBuilderEncodesQuery() {
        val http = HttpCore({ "http://host:2785/api" }, { "k" })
        val url = http.buildUrl("/sessions", mapOf("limit" to "10", "q" to "a b", "skip" to null))
        assertTrue(url.startsWith("http://host:2785/api/sessions?"))
        assertTrue(url.contains("limit=10"))
        assertTrue(url.contains("q=a+b") || url.contains("q=a%20b"))
        assertFalse(url.contains("skip"))
    }
}
