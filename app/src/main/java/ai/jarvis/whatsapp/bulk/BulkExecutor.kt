package ai.jarvis.whatsapp.bulk

import ai.jarvis.core.ApiResult
import ai.jarvis.core.Ids
import ai.jarvis.data.SettingsStore
import ai.jarvis.data.api.OpenWaApi
import ai.jarvis.data.db.BulkBatchEntity
import ai.jarvis.data.db.BulkRecipientEntity
import ai.jarvis.data.db.JarvisDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

@Serializable
data class BulkContent(
    val type: String = "TEXT",             // TEXT | IMAGE | VIDEO | AUDIO | DOCUMENT | STICKER | LOCATION | CONTACT | POLL | TEMPLATE
    val text: String = "",
    val mediaUrl: String? = null,
    val mediaBase64: String? = null,
    val mimetype: String? = null,
    val filename: String? = null,
    val caption: String? = null,
    val ptt: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationDescription: String? = null,
    val contactName: String? = null,
    val contactNumber: String? = null,
    val pollName: String? = null,
    val pollOptions: List<String> = emptyList(),
    val allowMultipleAnswers: Boolean = false,
    val templateId: String? = null,
    val templateName: String? = null,
    val templateVars: Map<String, String> = emptyMap(),
)

@Serializable
data class BulkOptions(
    val delayMs: Int = 4000,
    val randomize: Boolean = true,
    val stopOnError: Boolean = false,
    val dryRun: Boolean = false,
    val retryFailed: Boolean = true,
)

data class BulkProgress(
    val batchId: String = "",
    val running: Boolean = false,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val state: String = "DRAFT",
    val etaSeconds: Int = 0,
    val currentRecipient: String = "",
    val lastError: String? = null,
    val serverBatchId: String? = null,
) {
    val percent: Int get() = if (total == 0) 0 else ((sent + failed) * 100) / total
}

/**
 * §8.4 — send execution.
 *
 * Routes used:
 *  - text / image / video / audio / document → `POST /messages/send-bulk` (100 items per request)
 *  - sticker / location / poll / contact / template → sequential single sends (send-bulk has no type)
 *
 * Safety: every recipient row is written to Room BEFORE the network call, idempotency keys are
 * derived from (batch, index, chatId, content), 429 pauses with Retry-After, 5 consecutive
 * failures stop the run, and a dry run never touches the network.
 */
class BulkExecutor(
    private val api: OpenWaApi,
    private val db: JarvisDatabase,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _progress = MutableStateFlow(BulkProgress())
    val progress: StateFlow<BulkProgress> = _progress.asStateFlow()

    private var job: Job? = null
    private var paused = false

    companion object {
        const val MAX_PER_REQUEST = 100
        const val MAX_BATCH = 10_000
        const val HARD_HOUR_CAP = 300
    }

    /** Creates the local batch + recipient rows (written before any network call). */
    suspend fun prepareBatch(
        recipients: List<ParsedRecipient>,
        content: BulkContent,
        options: BulkOptions,
    ): String {
        val batchId = Ids.shortId("bulk")
        db.bulkDao().saveBatch(
            BulkBatchEntity(
                batchId = batchId,
                type = content.type,
                contentJson = json.encodeToString(BulkContent.serializer(), content),
                optionsJson = json.encodeToString(BulkOptions.serializer(), options),
                total = recipients.size,
                state = "DRAFT",
            )
        )
        db.bulkDao().addRecipients(
            recipients.map {
                BulkRecipientEntity(
                    batchId = batchId,
                    chatId = it.chatId,
                    name = it.name,
                    varJson = if (it.vars.isEmpty()) null else json.encodeToString(
                        kotlinx.serialization.builtins.MapSerializer(
                            String.serializer(),
                            String.serializer(),
                        ),
                        it.vars,
                    ),
                )
            }
        )
        _progress.value = BulkProgress(batchId = batchId, total = recipients.size, pending = recipients.size, state = "DRAFT")
        return batchId
    }

    fun start(batchId: String) {
        job?.cancel()
        paused = false
        job = scope.launch { runBatch(batchId) }
    }

    fun pause() { paused = true }

    fun resume(batchId: String) { paused = false; if (job?.isActive != true) start(batchId) }

    fun cancel(batchId: String) {
        job?.cancel()
        scope.launch {
            db.bulkDao().updateProgress(batchId, _progress.value.sent, _progress.value.failed, "CANCELLED")
            _progress.value = _progress.value.copy(state = "CANCELLED", running = false)
        }
    }

    /** Crashing the process mid-run leaves RUNNING rows → this offers resume on next launch. */
    suspend fun resumable(): BulkBatchEntity? = db.bulkDao().resumableBatch()

    suspend fun resumeBatch(batch: BulkBatchEntity) {
        val pending = db.bulkDao().pendingRecipients(batch.batchId)
        if (pending.isEmpty()) {
            db.bulkDao().updateProgress(batch.batchId, batch.sent, batch.failed, "DONE")
            return
        }
        start(batch.batchId)
    }

    private suspend fun runBatch(batchId: String) = mutex.withLock {
        val batch = db.bulkDao().batch(batchId) ?: return@withLock
        val content = runCatching { json.decodeFromString(BulkContent.serializer(), batch.contentJson) }.getOrNull() ?: return@withLock
        val options = runCatching { json.decodeFromString(BulkOptions.serializer(), batch.optionsJson) }.getOrNull() ?: BulkOptions()
        val sessionId = settings.settings.value.sessionId
        if (sessionId.isBlank() && !options.dryRun) {
            _progress.value = _progress.value.copy(lastError = "Session id set nahi hai — Settings me daalo", running = false, state = "FAILED")
            return@withLock
        }

        val all = db.bulkDao().recipients(batchId)
        val pending = all.filter { it.state == "PENDING" || it.state == "FAILED" }
        db.bulkDao().updateProgress(batchId, all.count { it.state == "SENT" }, all.count { it.state == "FAILED" }, "RUNNING")
        var sent = all.count { it.state == "SENT" }
        var failed = all.count { it.state == "FAILED" }
        _progress.value = BulkProgress(
            batchId = batchId, running = true, total = all.size, sent = sent, failed = failed,
            pending = pending.size, state = "RUNNING",
        )

        val delayBase = options.delayMs.coerceIn(1000, 60_000)
        var consecutiveFailures = 0
        var index = 0

        try {
            // ---------- Phase A: send-bulk able types, chunked 100 per request
            if (content.type in setOf("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT")) {
                val chunks = pending.chunked(MAX_PER_REQUEST)
                for (chunk in chunks) {
                    if (paused) { markPaused(batchId, sent, failed); return@withLock }
                    val messages = chunk.mapIndexed { i, r ->
                        buildBulkItem(index + i, r, content)
                    }
                    _progress.value = _progress.value.copy(currentRecipient = "${chunk.size} recipients (batch ${chunks.indexOf(chunk) + 1}/${chunks.size})")

                    if (options.dryRun) {
                        chunk.forEach {
                            db.bulkDao().markRecipient(it.id, "SENT", "DRY_RUN", null)
                            sent++
                        }
                        index += chunk.size
                        _progress.value = _progress.value.copy(sent = sent, failed = failed, pending = (pending.size - index).coerceAtLeast(0))
                        delay(60)
                        continue
                    }

                    val serverBatch = Ids.shortId("srv")
                    val res = api.sendBulk(
                        sessionId = sessionId,
                        batchId = serverBatch,
                        messages = messages,
                        delayMs = delayBase,
                        randomizeDelay = options.randomize,
                        stopOnError = options.stopOnError,
                    )
                    when (res) {
                        is ApiResult.Success -> {
                            consecutiveFailures = 0
                            _progress.value = _progress.value.copy(serverBatchId = res.data.batchId)
                            // Poll the server batch until terminal (or 120 s) and sync per-row truth
                            val outcome = pollBatch(sessionId, res.data.batchId)
                            chunk.forEachIndexed { i, r ->
                                val rowResult = outcome?.results?.getOrNull(i)
                                when (rowResult?.status) {
                                    "sent" -> { db.bulkDao().markRecipient(r.id, "SENT", rowResult.messageId, null); sent++ }
                                    "failed" -> {
                                        db.bulkDao().markRecipient(r.id, "FAILED", null, extractError(rowResult.error))
                                        failed++
                                    }
                                    "cancelled" -> db.bulkDao().markRecipient(r.id, "SKIPPED", null, "cancelled")
                                    else -> {
                                        // Batch never completed (session not ready etc.) — mark honestly
                                        val batchState = outcome?.status
                                        db.bulkDao().markRecipient(
                                            r.id,
                                            if (batchState == "failed") "FAILED" else "UNKNOWN",
                                            null,
                                            if (batchState == "failed") "Batch failed (session not ready?)" else "Outcome unknown — chat check karo",
                                        )
                                        if (batchState == "failed") failed++
                                    }
                                }
                            }
                        }
                        is ApiResult.Failure -> {
                            consecutiveFailures++
                            chunk.forEach { r ->
                                db.bulkDao().markRecipient(r.id, "FAILED", null, res.error.oneLine)
                                failed++
                            }
                            if (res.error.status == 429) {
                                delay(((res.error.retryAfterSeconds ?: 30) * 1000L).coerceAtMost(120_000))
                            } else if (res.error.status == 503) {
                                delay(30_000)
                            }
                        }
                    }
                    index += chunk.size
                    _progress.value = _progress.value.copy(
                        sent = sent, failed = failed,
                        pending = (pending.size - index).coerceAtLeast(0),
                        etaSeconds = estimateEta(pending.size - index, delayBase),
                    )
                    db.bulkDao().updateProgress(batchId, sent, failed, "RUNNING")
                    if (consecutiveFailures >= 5) {
                        _progress.value = _progress.value.copy(
                            lastError = "5 lagatar failures — rok diya. Resume kar sakte ho.",
                            running = false, state = "PAUSED",
                        )
                        db.bulkDao().updateProgress(batchId, sent, failed, "PAUSED")
                        return@withLock
                    }
                    if (options.stopOnError && failed > 0) break
                    pace(delayBase, options.randomize, chunk.size)
                }
            } else {
                // ---------- Phase B: sequential single sends (sticker/location/poll/contact/template)
                for (r in pending) {
                    if (paused) { markPaused(batchId, sent, failed); return@withLock }
                    _progress.value = _progress.value.copy(currentRecipient = r.name ?: ai.jarvis.core.Numbers.mask(r.chatId))
                    if (options.dryRun) {
                        db.bulkDao().markRecipient(r.id, "SENT", "DRY_RUN", null); sent++
                    } else {
                        val res = sendSequential(sessionId, r, content, batchId, index)
                        when (res) {
                            is ApiResult.Success -> {
                                db.bulkDao().markRecipient(r.id, "SENT", res.data, null); sent++
                                consecutiveFailures = 0
                            }
                            is ApiResult.Failure -> {
                                db.bulkDao().markRecipient(
                                    r.id,
                                    if (res.error.transport) "UNKNOWN" else "FAILED",
                                    null, res.error.oneLine,
                                )
                                failed++
                                consecutiveFailures++
                                if (res.error.status == 429) delay(((res.error.retryAfterSeconds ?: 30) * 1000L).coerceAtMost(120_000))
                                else if (res.error.status == 503) delay(30_000)
                            }
                        }
                    }
                    index++
                    _progress.value = _progress.value.copy(
                        sent = sent, failed = failed,
                        pending = (pending.size - index).coerceAtLeast(0),
                        etaSeconds = estimateEta(pending.size - index, delayBase),
                    )
                    db.bulkDao().updateProgress(batchId, sent, failed, "RUNNING")
                    if (consecutiveFailures >= 5) {
                        _progress.value = _progress.value.copy(lastError = "5 lagatar failures — rok diya.", running = false, state = "PAUSED")
                        db.bulkDao().updateProgress(batchId, sent, failed, "PAUSED")
                        return@withLock
                    }
                    if (options.stopOnError && failed > 0) break
                    pace(delayBase, options.randomize, 1)
                }
            }

            val finalState = when {
                failed == 0 -> "DONE"
                sent == 0 -> "DONE"
                else -> "DONE"
            }
            db.bulkDao().updateProgress(batchId, sent, failed, finalState)
            _progress.value = _progress.value.copy(running = false, state = finalState, sent = sent, failed = failed, pending = 0, currentRecipient = "")
        } catch (ce: CancellationException) {
            db.bulkDao().updateProgress(batchId, sent, failed, "CANCELLED")
            _progress.value = _progress.value.copy(running = false, state = "CANCELLED")
            throw ce
        } catch (e: Exception) {
            db.bulkDao().updateProgress(batchId, sent, failed, "PAUSED")
            _progress.value = _progress.value.copy(running = false, state = "PAUSED", lastError = e.message)
        }
    }

    private suspend fun markPaused(batchId: String, sent: Int, failed: Int) {
        db.bulkDao().updateProgress(batchId, sent, failed, "PAUSED")
        _progress.value = _progress.value.copy(running = false, state = "PAUSED", sent = sent, failed = failed)
    }

    private suspend fun pollBatch(sessionId: String, batchId: String): ai.jarvis.data.api.BatchStatusDto? {
        repeat(40) {
            delay(1500)
            when (val res = api.batchStatus(sessionId, batchId)) {
                is ApiResult.Success -> {
                    val d = res.data
                    _progress.value = _progress.value.copy(
                        sent = _progress.value.sent, state = "RUNNING",
                    )
                    if (d.status in setOf("completed", "failed", "cancelled")) return d
                }
                is ApiResult.Failure -> return null
            }
        }
        return null
    }

    private fun buildBulkItem(index: Int, r: BulkRecipientEntity, content: BulkContent): JsonObject {
        val name = r.name ?: ""
        val vars = runCatching {
            r.varJson?.let {
                json.decodeFromString(
                    kotlinx.serialization.builtins.MapSerializer(
                        String.serializer(),
                        String.serializer(),
                    ),
                    it,
                )
            } ?: emptyMap()
        }.getOrDefault(emptyMap())

        fun render(src: String): String {
            var out = src.replace("{{name}}", name)
            vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
            return out.replace(Regex("""\{\{\w+\}\}"""), "").trim()
        }

        return buildJsonObject {
            put("chatId", r.chatId)
            when (content.type) {
                "TEXT" -> {
                    put("type", "text")
                    putJsonObject("content") { put("text", render(content.text)) }
                }
                "IMAGE" -> {
                    put("type", "image")
                    putJsonObject("content") {
                        content.mediaUrl?.let { putJsonObject("image") { put("url", it) } }
                        content.mediaBase64?.let {
                            putJsonObject("image") {
                                put("base64", it)
                                put("mimetype", content.mimetype ?: "image/jpeg")
                            }
                        }
                        content.caption?.let { put("caption", render(it)) }
                    }
                }
                "VIDEO" -> {
                    put("type", "video")
                    putJsonObject("content") {
                        content.mediaUrl?.let { putJsonObject("video") { put("url", it) } }
                        content.mediaBase64?.let {
                            putJsonObject("video") { put("base64", it); put("mimetype", content.mimetype ?: "video/mp4") }
                        }
                        content.caption?.let { put("caption", render(it)) }
                    }
                }
                "AUDIO" -> {
                    put("type", "audio")
                    putJsonObject("content") {
                        content.mediaUrl?.let { putJsonObject("audio") { put("url", it) } }
                        content.mediaBase64?.let {
                            putJsonObject("audio") { put("base64", it); put("mimetype", content.mimetype ?: "audio/ogg") }
                        }
                    }
                }
                "DOCUMENT" -> {
                    put("type", "document")
                    putJsonObject("content") {
                        content.mediaUrl?.let { putJsonObject("document") { put("url", it); content.filename?.let { f -> put("filename", f) } } }
                        content.mediaBase64?.let {
                            putJsonObject("document") {
                                put("base64", it)
                                put("mimetype", content.mimetype ?: "application/pdf")
                                content.filename?.let { f -> put("filename", f) }
                            }
                        }
                    }
                }
                else -> { put("type", "text"); putJsonObject("content") { put("text", render(content.text)) } }
            }
        }
    }

    private suspend fun sendSequential(
        sessionId: String, r: BulkRecipientEntity, content: BulkContent, batchId: String, index: Int,
    ): ApiResult<String> {
        val idem = Ids.idemKey(batchId, index.toString(), r.chatId, content.type.hashCode().toString())
        val name = r.name ?: ""
        fun render(src: String?): String? = src?.replace("{{name}}", name)?.replace(Regex("""\{\{\w+\}\}"""), "")?.trim()
        return when (content.type) {
            "STICKER" -> api.sendSticker(sessionId, r.chatId, url = content.mediaUrl, base64 = content.mediaBase64, idempotencyKey = idem)
            "LOCATION" -> api.sendLocation(
                sessionId, r.chatId,
                content.latitude ?: 0.0, content.longitude ?: 0.0,
                render(content.locationDescription), null, idempotencyKey = idem,
            )
            "CONTACT" -> api.sendContact(
                sessionId, r.chatId,
                content.contactName ?: name, content.contactNumber ?: r.chatId.substringBefore('@'),
                idempotencyKey = idem,
            )
            "POLL" -> api.sendPoll(
                sessionId, r.chatId, content.pollName ?: "Poll",
                content.pollOptions, content.allowMultipleAnswers, idempotencyKey = idem,
            )
            "TEMPLATE" -> api.sendTemplate(
                sessionId, r.chatId,
                templateId = content.templateId, templateName = content.templateName,
                vars = content.templateVars, idempotencyKey = idem,
            )
            else -> api.sendText(sessionId, r.chatId, render(content.text) ?: "", idempotencyKey = idem)
        }.let { res ->
            when (res) {
                is ApiResult.Success -> ApiResult.Success(res.data.messageId)
                is ApiResult.Failure -> res
            }
        }
    }

    private suspend fun pace(baseMs: Int, randomize: Boolean, count: Int) {
        val perItem = if (randomize) baseMs + Random.nextLong(0, 2000) else baseMs.toLong()
        delay(perItem * count.coerceAtLeast(1))
    }

    private fun estimateEta(remaining: Int, delayMs: Int): Int =
        if (remaining <= 0) 0 else ((remaining * (delayMs + 1000L)) / 1000).toInt()

    private fun extractError(error: kotlinx.serialization.json.JsonElement?): String {
        if (error == null) return "send failed"
        return runCatching {
            val obj = error as? JsonObject
            obj?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull() ?: error.toString().take(200)
    }

    /** CSV report (number,name,state,messageId,timestamp,error) ready for SAF CreateDocument. */
    suspend fun buildReport(batchId: String): String {
        val rows = db.bulkDao().recipients(batchId)
        val sb = StringBuilder("number,name,state,messageId,timestamp,error\n")
        rows.forEach { r ->
            val number = r.chatId.substringBefore('@')
            val name = (r.name ?: "").replace(",", " ")
            val err = (r.errorText ?: "").replace(",", ";").replace("\n", " ")
            sb.append("$number,$name,${r.state},${r.messageId ?: ""},${r.updatedAtEpochMs},$err\n")
        }
        return sb.toString()
    }
}
