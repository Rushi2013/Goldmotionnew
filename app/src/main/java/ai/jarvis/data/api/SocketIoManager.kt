package ai.jarvis.data.api

import ai.jarvis.autoreply.InboundEvent
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.net.URI

/**
 * OpenWA realtime — Socket.IO namespace `/events`, handshake `auth: { apiKey }`.
 *
 * Envelope shapes (verified):
 *  client → "message" {type:"subscribe", sessionId:"*", events:["*"]}
 *  server → "message" {type:"subscribed"|"pong"|"error", ...}
 *  server → "message" {type:"event", timestamp, payload:{event, sessionId, data}}
 */
class SocketIoManager(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var socket: Socket? = null

    private val _connection = MutableStateFlow(RealtimeState.DISCONNECTED)
    val connection: StateFlow<RealtimeState> = _connection.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    enum class RealtimeState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class RealtimeEvent(
        val event: String,
        val sessionId: String,
        val data: JsonObject?,
        val raw: String,
    )

    private var lastError: String? = null
    fun lastError(): String? = lastError

    fun connect() {
        if (socket?.connected() == true) return
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            lastError = "API key missing — realtime disabled"
            _connection.value = RealtimeState.ERROR
            return
        }
        val base = baseUrlProvider().trimEnd('/')
        val origin = runCatching {
            val uri = URI(base)
            val scheme = if (uri.scheme == "https") "https" else "http"
            "$scheme://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrElse {
            lastError = "Invalid base URL: $base"
            _connection.value = RealtimeState.ERROR
            return
        }

        _connection.value = RealtimeState.CONNECTING
        runCatching {
            val opts = IO.Options().apply {
                auth = mapOf("apiKey" to apiKey)
                reconnection = true
                reconnectionDelay = 2_000
                reconnectionDelayMax = 30_000
                timeout = 15_000
                transports = arrayOf("websocket", "polling")
                forceNew = true
            }
            val s = IO.socket("$origin/events", opts)
            s.on(Socket.EVENT_CONNECT) {
                lastError = null
                _connection.value = RealtimeState.CONNECTED
                subscribe(s, "*", listOf("*"))
            }
            s.on(Socket.EVENT_DISCONNECT) { _connection.value = RealtimeState.DISCONNECTED }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                lastError = args.firstOrNull()?.toString()?.take(300)
                _connection.value = RealtimeState.ERROR
            }
            s.on("message") { args ->
                val payload = args.firstOrNull()?.toString() ?: return@on
                handleMessage(payload)
            }
            socket = s
            s.connect()
        }.onFailure {
            lastError = it.message
            _connection.value = RealtimeState.ERROR
        }
    }

    fun subscribe(sessionId: String = "*", events: List<String> = listOf("*")) {
        val s = socket ?: return
        if (!s.connected()) return
        val obj = JSONObject().apply {
            put("type", "subscribe")
            put("sessionId", sessionId)
            put("events", org.json.JSONArray(events))
        }
        s.emit("message", obj)
    }

    fun disconnect() {
        runCatching { socket?.disconnect(); socket?.off() }
        socket = null
        _connection.value = RealtimeState.DISCONNECTED
    }

    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * Converts a Socket.IO "message" payload into our typed event, and — for message.received —
     * into the engine's InboundEvent. The nested `payload` shape is handled here exactly once.
     */
    fun handleMessage(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = root["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "event" -> {
                val payload = root["payload"]?.jsonObject ?: return
                val eventName = payload["event"]?.jsonPrimitive?.content ?: "unknown"
                val sessionId = payload["sessionId"]?.jsonPrimitive?.content.orEmpty()
                val data = payload["data"]?.jsonObject
                _events.tryEmit(RealtimeEvent(eventName, sessionId, data, raw))
            }
            "subscribed", "unsubscribed", "pong" -> Unit
            "error" -> lastError = root["message"]?.jsonPrimitive?.content ?: root.toString().take(200)
        }
    }

    /** Normalises a `message.received` payload into the engine's InboundEvent. */
    fun toInbound(event: RealtimeEvent): InboundEvent? {
        if (event.event != "message.received" && event.event != "message.sent") return null
        val d = event.data ?: return null
        val id = d["id"]?.jsonPrimitive?.content ?: d["messageId"]?.jsonPrimitive?.content ?: return null
        val chatId = d["chatId"]?.jsonPrimitive?.content
            ?: d["from"]?.jsonPrimitive?.content
            ?: return null
        val fromMe = d["fromMe"]?.jsonPrimitive?.booleanOrNull ?: false
        val body = d["body"]?.jsonPrimitive?.content.orEmpty()
        val tsSeconds = d["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val tsMs = if (tsSeconds in 1..9_999_999_999) tsSeconds * 1000 else System.currentTimeMillis()
        return InboundEvent(
            sessionId = event.sessionId,
            messageId = id,
            chatId = chatId,
            chatName = d["contact"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: d["contact"]?.jsonObject?.get("pushName")?.jsonPrimitive?.content
                ?: d["chatName"]?.jsonPrimitive?.content,
            fromMe = fromMe || event.event == "message.sent",
            text = body,
            timestampMs = tsMs,
            type = d["type"]?.jsonPrimitive?.content ?: "text",
            kind = d["kind"]?.jsonPrimitive?.content ?: if (chatId.endsWith("@g.us")) "group" else "individual",
            mentionsMe = (d["mentions"]?.let { m ->
                runCatching { (m as kotlinx.serialization.json.JsonArray).isNotEmpty() }.getOrDefault(false)
            } ?: false),
            quotedMessageId = d["quotedMessageId"]?.jsonPrimitive?.content,
        )
    }
}
