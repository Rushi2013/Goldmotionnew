package ai.jarvis

import ai.jarvis.autoreply.LoopBreaker
import ai.jarvis.autoreply.QuietHours
import ai.jarvis.autoreply.ReplyRateLimiter
import ai.jarvis.data.api.HttpCore
import ai.jarvis.data.api.OpenWaApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoopBreakerTest {
    private var now = 1_000_000L
    private val breaker = LoopBreaker { now }

    @Test
    fun threeAutoRepliesIn90sBlockTheChat() {
        assertTrue(breaker.check("chat1", "hi").allowed)
        breaker.recordAutoReply("chat1"); now += 1_000
        assertTrue(breaker.check("chat1", "hi").allowed)
        breaker.recordAutoReply("chat1"); now += 1_000
        assertTrue(breaker.check("chat1", "hi").allowed)
        breaker.recordAutoReply("chat1"); now += 1_000

        val blocked = breaker.check("chat1", "hello")
        assertFalse(blocked.allowed)
        assertTrue(blocked.reason.contains("90s") || blocked.reason.contains("Loop"))

        // other chats unaffected
        assertTrue(breaker.check("chat2", "hi").allowed)
        // still blocked 1h later, released after 6h
        now += 3_600_000
        assertFalse(breaker.check("chat1", "hi").allowed)
        now += 6 * 3_600_000
        assertTrue(breaker.check("chat1", "hi").allowed)
    }

    @Test
    fun repeatedIdenticalTextLooksLikeBot() {
        assertTrue(breaker.check("c", "same text").allowed)
        assertTrue(breaker.check("c", "same text").allowed)
        assertTrue(breaker.check("c", "same text").allowed)
        val verdict = breaker.check("c", "same text")
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason.contains("repeated"))
    }
}

class RateLimiterTest {
    @Test
    fun cooldownAndCaps() {
        var now = 0L
        val limiter = ReplyRateLimiter { now }
        assertTrue(limiter.check("c", 0, 0, 25, 60, cooldownSeconds = 60).allowed)
        limiter.record("c")
        assertFalse("cooldown must block", limiter.check("c", 0, 0, 25, 60).allowed)

        now += 61_000
        assertFalse("per-chat daily cap", limiter.check("c", 25, 0, 25, 60).allowed)
        assertFalse("hourly cap", limiter.check("c", 0, 60, 25, 60).allowed)
        assertTrue(limiter.check("c", 5, 5, 25, 60).allowed)
    }

    @Test
    fun quietHoursWrapMidnight() {
        assertTrue(QuietHours.isQuiet(23, 22, 7))
        assertTrue(QuietHours.isQuiet(2, 22, 7))
        assertFalse(QuietHours.isQuiet(12, 22, 7))
        assertFalse(QuietHours.isQuiet(7, 22, 7))
    }
}

class ContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OpenWaApi
    private var baseUrl = ""

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api").toString().trimEnd('/')
        api = OpenWaApi(HttpCore({ baseUrl }, { "test-key" }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendTextHitsExactPathWithKeyAndIdempotency() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"messageId":"true_919812345678@c.us_3EB0ABC","timestamp":1706868000}""")
        )
        val res = api.sendText("sid1", "919812345678@c.us", "Hello", idempotencyKey = "idem-1")
        assertTrue(res is ai.jarvis.core.ApiResult.Success)
        val msgId = (res as ai.jarvis.core.ApiResult.Success).data.messageId
        assertEquals("true_919812345678@c.us_3EB0ABC", msgId)
        assertEquals(1706868000L, res.data.timestamp)

        val recorded = server.takeRequest()
        assertEquals("/api/sessions/sid1/messages/send-text", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("test-key", recorded.getHeader("X-API-Key"))
        assertEquals("idem-1", recorded.getHeader("Idempotency-Key"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8())
        assertEquals("919812345678@c.us", body.jsonObjectPrim("chatId"))
        assertEquals("Hello", body.jsonObjectPrim("text"))
    }

    @Test
    fun getSessionPathAndQuery() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"a","name":"jarvis","status":"ready","engineLoaded":true}]"""))
        val res = api.listSessions()
        assertTrue(res is ai.jarvis.core.ApiResult.Success)
        assertEquals("jarvis", (res as ai.jarvis.core.ApiResult.Success).data.first().name)
        assertEquals("/api/sessions", server.takeRequest().path)
    }

    @Test
    fun sendBulkPathAndBody() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"batchId":"batch_1","status":"pending","totalMessages":1,"statusUrl":"/x"}""")
        )
        val item = Json.parseToJsonElement(
            """{"chatId":"919812345678@c.us","type":"text","content":{"text":"hi"}}"""
        ) as kotlinx.serialization.json.JsonObject
        val res = api.sendBulk("sid", "batch_local", listOf(item))
        assertTrue(res is ai.jarvis.core.ApiResult.Success)
        val rec = server.takeRequest()
        assertEquals("/api/sessions/sid/messages/send-bulk", rec.path)
        val body = Json.parseToJsonElement(rec.body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertNotNull(body["messages"])
        assertNotNull(body["options"])
    }

    @Test
    fun errorMessageArrayIsFlattened() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"statusCode":400,"message":["property foo should not exist"],"error":"Bad Request"}""")
        )
        val res = api.sendText("sid", "chat@c.us", "x")
        assertTrue(res is ai.jarvis.core.ApiResult.Failure)
        val err = (res as ai.jarvis.core.ApiResult.Failure).error
        assertEquals(400, err.status)
        assertTrue(err.message.contains("property foo"))
    }

    @Test
    fun notReady409MapsToStartSession() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"statusCode":409,"message":"Session is not connected. The WhatsApp client is not ready.","error":"Conflict"}""")
        )
        val res = api.checkNumber("sid", "919812345678")
        assertTrue((res as? ai.jarvis.core.ApiResult.Failure)?.error?.needsSessionStart == true)
    }

    @Test
    fun mediaSendBodyHasNoUndocumentedFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"messageId":"m","timestamp":1}"""))
        api.sendImage("sid", "chat@c.us", url = "https://x/y.jpg", caption = "hi")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as kotlinx.serialization.json.JsonObject
        // whitelist + forbidNonWhitelisted: only documented fields allowed
        val allowed = setOf("chatId", "url", "base64", "mimetype", "filename", "caption", "mentions", "quotedMessageId")
        body.keys.forEach { k -> assertTrue("unexpected field $k", k in allowed) }
    }

    @Test
    fun rawCallReachesAnyPath() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val res = api.raw("GET", "/infra/status")
        assertTrue(res is ai.jarvis.core.ApiResult.Success)
        assertEquals("/api/infra/status", server.takeRequest().path)
    }

    @Test
    fun chatIdIsUrlEncoded() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"messages":[],"total":0}"""))
        api.chatHistory("sid", "120363001234567890@g.us")
        val path = server.takeRequest().path.orEmpty()
        assertTrue("group id must be encoded in the path: $path", path.contains("120363001234567890%40g.us"))
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectPrim(key: String): String? =
    (this as? kotlinx.serialization.json.JsonObject)
        ?.get(key)
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
