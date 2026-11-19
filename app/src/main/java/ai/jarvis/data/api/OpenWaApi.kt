package ai.jarvis.data.api

import ai.jarvis.core.ApiResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Typed facade over the 164 OpenWA 0.30.3 paths (204 operations).
 *
 * Every operation is expressed as `method + path + JSON body` so the app can never drift from
 * the gateway contract, and routes that OpenWA adds later stay reachable through [raw].
 *
 * Conventions:
 *  - all paths are relative to the configured base URL (…/api)
 *  - send routes accept an optional Idempotency-Key
 *  - list routes are decoded into the real DTOs; no invented envelopes
 */
class OpenWaApi(private val http: HttpCore) {

    private val json get() = http.json

    // ---------------------------------------------------------------------------------
    // Generic escape hatch (Dev Tools / API console / future routes)
    // ---------------------------------------------------------------------------------
    suspend fun raw(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResult<ApiOutcome> = http.execute(method, path, body, query, idempotencyKey, extraHeaders)

    // =================================================================================
    // AUTH / KEYS (7)
    // =================================================================================
    suspend fun validateKey(apiKey: String): ApiResult<ValidateApiKeyDto> =
        http.call("POST", "/auth/validate", body = buildJsonObject { put("apiKey", apiKey) }.toString())

    suspend fun listApiKeys(): ApiResult<List<ApiKeyDto>> =
        http.call("GET", "/auth/api-keys")

    suspend fun createApiKey(
        name: String,
        role: String = "operator",
        allowedSessions: List<String>? = null,
        allowedChats: List<String>? = null,
        allowedIps: List<String>? = null,
        expiresAt: String? = null,
    ): ApiResult<ApiKeyDto> = http.call(
        "POST", "/auth/api-keys",
        body = buildJsonObject {
            put("name", name)
            put("role", role)
            allowedSessions?.let { putJsonArray("allowedSessions") { it.forEach { s -> add(s) } } }
            allowedChats?.let { putJsonArray("allowedChats") { it.forEach { s -> add(s) } } }
            allowedIps?.let { putJsonArray("allowedIps") { it.forEach { s -> add(s) } } }
            expiresAt?.let { put("expiresAt", it) }
        }.toString()
    )

    suspend fun getApiKey(id: String): ApiResult<ApiKeyDto> = http.call("GET", "/auth/api-keys/$id")

    suspend fun updateApiKey(id: String, updates: JsonObject): ApiResult<ApiKeyDto> =
        http.call("PUT", "/auth/api-keys/$id", body = updates.toString())

    suspend fun revokeApiKey(id: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/auth/api-keys/$id/revoke")

    suspend fun deleteApiKey(id: String): ApiResult<ApiOutcome> = http.execute("DELETE", "/auth/api-keys/$id")

    // =================================================================================
    // HEALTH (public, no key)
    // =================================================================================
    suspend fun healthReady(): ApiResult<HealthDto> = http.call("GET", "/health/ready")
    suspend fun health(): ApiResult<HealthDto> = http.call("GET", "/health")
    suspend fun healthLive(): ApiResult<HealthDto> = http.call("GET", "/health/live")

    // =================================================================================
    // SESSIONS (28) — lifecycle, QR, config, presence, chats, groups shortcut
    // =================================================================================
    suspend fun listSessions(): ApiResult<List<SessionDto>> = http.call("GET", "/sessions")

    suspend fun createSession(name: String, proxyUrl: String? = null, proxyType: String? = null): ApiResult<SessionDto> =
        http.call("POST", "/sessions", body = buildJsonObject {
            put("name", name)
            proxyUrl?.let { put("proxyUrl", it) }
            proxyType?.let { put("proxyType", it) }
        }.toString())

    suspend fun getSession(id: String): ApiResult<SessionDto> = http.call("GET", "/sessions/$id")

    suspend fun deleteSession(id: String): ApiResult<ApiOutcome> = http.execute("DELETE", "/sessions/$id")

    suspend fun startSession(id: String): ApiResult<SessionDto> = http.call("POST", "/sessions/$id/start")

    suspend fun stopSession(id: String): ApiResult<SessionDto> = http.call("POST", "/sessions/$id/stop")

    suspend fun logoutSession(id: String): ApiResult<ApiOutcome> = http.execute("POST", "/sessions/$id/logout")

    suspend fun forceKillSession(id: String): ApiResult<ApiOutcome> = http.execute("POST", "/sessions/$id/force-kill")

    suspend fun getQr(id: String): ApiResult<QrDto> = http.call("GET", "/sessions/$id/qr")

    suspend fun requestPairingCode(id: String, phoneNumber: String): ApiResult<PairingCodeDto> =
        http.call("POST", "/sessions/$id/pairing-code", body = buildJsonObject { put("phoneNumber", phoneNumber) }.toString())

    suspend fun getSessionConfig(id: String): ApiResult<JsonElement> = http.call("GET", "/sessions/$id/config")

    suspend fun updateSessionConfig(id: String, config: JsonObject): ApiResult<JsonElement> =
        http.call("PATCH", "/sessions/$id/config", body = config.toString())

    suspend fun getSessionProxy(id: String): ApiResult<JsonElement> = http.call("GET", "/sessions/$id/proxy")

    suspend fun updateSessionProxy(id: String, body: JsonObject): ApiResult<JsonElement> =
        http.call("PATCH", "/sessions/$id/proxy", body = body.toString())

    suspend fun setPresence(id: String, presence: String, chatId: String? = null): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$id/presence", body = buildJsonObject {
            put("presence", presence)
            chatId?.let { put("chatId", it) }
        }.toString())

    suspend fun subscribePresence(id: String, chatId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$id/presence/subscribe", body = buildJsonObject { put("chatId", chatId) }.toString())

    suspend fun getPresence(id: String, chatId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$id/presence/$chatId")

    suspend fun sessionChats(id: String, limit: Int = 200, offset: Int = 0): ApiResult<List<ChatSummaryDto>> =
        http.call("GET", "/sessions/$id/chats", query = mapOf("limit" to "$limit", "offset" to "$offset"))

    suspend fun sessionGroups(id: String): ApiResult<List<GroupSummaryDto>> = http.call("GET", "/sessions/$id/groups")

    suspend fun sessionStatsOverview(): ApiResult<JsonElement> = http.call("GET", "/sessions/stats/overview")

    // =================================================================================
    // CHATS (8)
    // =================================================================================
    suspend fun archiveChat(sessionId: String, chatId: String, archive: Boolean): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/archive", body = buildJsonObject {
            put("chatId", chatId); put("archive", archive)
        }.toString())

    suspend fun deleteChat(sessionId: String, chatId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/delete", body = buildJsonObject { put("chatId", chatId) }.toString())

    suspend fun muteChat(sessionId: String, chatId: String, muteUntilMs: Long? = null): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/mute", body = buildJsonObject {
            put("chatId", chatId)
            muteUntilMs?.let { put("muteUntilMs", it) }
        }.toString())

    suspend fun pinChat(sessionId: String, chatId: String, pin: Boolean = true): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/pin", body = buildJsonObject {
            put("chatId", chatId); put("pin", pin)
        }.toString())

    suspend fun markChatRead(sessionId: String, chatId: String, messageIds: List<String>? = null): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/read", body = buildJsonObject {
            put("chatId", chatId)
            messageIds?.let { ids -> putJsonArray("messageIds") { ids.forEach { add(it) } } }
        }.toString())

    suspend fun markChatUnread(sessionId: String, chatId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/unread", body = buildJsonObject { put("chatId", chatId) }.toString())

    suspend fun sendTyping(sessionId: String, chatId: String, typing: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/chats/typing", body = buildJsonObject {
            put("chatId", chatId); put("typing", typing)
        }.toString())

    suspend fun clearChatMessages(sessionId: String, chatId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/chats/${encode(chatId)}/messages")

    // =================================================================================
    // CONTACTS (12)
    // =================================================================================
    suspend fun listContacts(sessionId: String, limit: Int = 500, offset: Int = 0): ApiResult<List<ContactDto>> =
        http.call("GET", "/sessions/$sessionId/contacts", query = mapOf("limit" to "$limit", "offset" to "$offset"))

    suspend fun blockedContacts(sessionId: String): ApiResult<List<ContactDto>> =
        http.call("GET", "/sessions/$sessionId/contacts/blocked")

    suspend fun checkNumber(sessionId: String, number: String): ApiResult<NumberCheckDto> =
        http.call("GET", "/sessions/$sessionId/contacts/check/${encode(number)}")

    suspend fun contactProfilePictures(sessionId: String): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/contacts/profile-pictures")

    suspend fun getContact(sessionId: String, contactId: String): ApiResult<ContactDto> =
        http.call("GET", "/sessions/$sessionId/contacts/${encode(contactId)}")

    suspend fun updateContact(sessionId: String, contactId: String, firstName: String? = null, lastName: String? = null): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/contacts/${encode(contactId)}", body = buildJsonObject {
            firstName?.let { put("firstName", it) }
            lastName?.let { put("lastName", it) }
        }.toString())

    suspend fun deleteContact(sessionId: String, contactId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/contacts/${encode(contactId)}")

    suspend fun blockContact(sessionId: String, contactId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/contacts/${encode(contactId)}/block")

    suspend fun unblockContact(sessionId: String, contactId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/contacts/${encode(contactId)}/block")

    suspend fun contactPhone(sessionId: String, contactId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/contacts/${encode(contactId)}/phone")

    suspend fun contactProfilePicture(sessionId: String, contactId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/contacts/${encode(contactId)}/profile-picture")

    suspend fun contactLid(sessionId: String, number: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/contacts/${encode(number)}/lid")

    // =================================================================================
    // GROUPS (22)
    // =================================================================================
    suspend fun createGroup(sessionId: String, name: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups", body = buildJsonObject {
            put("name", name)
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun joinGroup(sessionId: String, inviteCode: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/join", body = buildJsonObject { put("inviteCode", inviteCode) }.toString())

    suspend fun groupJoinInfo(sessionId: String, inviteCode: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/groups/join-info", query = mapOf("inviteCode" to inviteCode))

    suspend fun groupInfo(sessionId: String, groupId: String): ApiResult<GroupInfoDto> =
        http.call("GET", "/sessions/$sessionId/groups/${encode(groupId)}")

    suspend fun setGroupSubject(sessionId: String, groupId: String, subject: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/groups/${encode(groupId)}/subject", body = buildJsonObject { put("subject", subject) }.toString())

    suspend fun setGroupDescription(sessionId: String, groupId: String, description: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/groups/${encode(groupId)}/description", body = buildJsonObject { put("description", description) }.toString())

    suspend fun setGroupMemberLabel(sessionId: String, groupId: String, label: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/groups/${encode(groupId)}/member-label", body = buildJsonObject { put("label", label) }.toString())

    suspend fun leaveGroup(sessionId: String, groupId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/leave")

    suspend fun groupSettings(sessionId: String, groupId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/groups/${encode(groupId)}/settings")

    suspend fun updateGroupSettings(sessionId: String, groupId: String, settings: JsonObject): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/groups/${encode(groupId)}/settings", body = settings.toString())

    suspend fun addGroupParticipants(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/participants", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun removeGroupParticipants(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/groups/${encode(groupId)}/participants", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun promoteParticipants(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/participants/promote", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun demoteParticipants(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/participants/demote", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun groupPicture(sessionId: String, groupId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/groups/${encode(groupId)}/picture")

    suspend fun setGroupPicture(sessionId: String, groupId: String, url: String? = null, base64: String? = null): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/groups/${encode(groupId)}/picture", body = buildJsonObject {
            url?.let { put("url", it) }
            base64?.let { put("base64", it) }
        }.toString())

    suspend fun deleteGroupPicture(sessionId: String, groupId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/groups/${encode(groupId)}/picture")

    suspend fun groupInviteCode(sessionId: String, groupId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/groups/${encode(groupId)}/invite-code")

    suspend fun revokeGroupInviteCode(sessionId: String, groupId: String): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/groups/${encode(groupId)}/invite-code/revoke")

    suspend fun membershipRequests(sessionId: String, groupId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/groups/${encode(groupId)}/membership-requests")

    suspend fun approveMembership(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/membership-requests/approve", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    suspend fun rejectMembership(sessionId: String, groupId: String, participants: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/groups/${encode(groupId)}/membership-requests/reject", body = buildJsonObject {
            putJsonArray("participants") { participants.forEach { add(it) } }
        }.toString())

    // =================================================================================
    // MESSAGES — send (11 + bulk + reply)
    // =================================================================================
    suspend fun sendText(
        sessionId: String,
        chatId: String,
        text: String,
        mentions: List<String>? = null,
        linkPreview: Boolean? = null,
        quotedMessageId: String? = null,
        idempotencyKey: String? = null,
    ): ApiResult<SendResultDto> = http.call(
        "POST", "/sessions/$sessionId/messages/send-text",
        body = buildJsonObject {
            put("chatId", chatId)
            put("text", text)
            mentions?.let { m -> putJsonArray("mentions") { m.forEach { add(it) } } }
            linkPreview?.let { put("linkPreview", it) }
            quotedMessageId?.let { put("quotedMessageId", it) }
        }.toString(),
        idempotencyKey = idempotencyKey,
    )

    private fun mediaBody(
        chatId: String, url: String?, base64: String?, mimetype: String?, filename: String?,
        caption: String?, mentions: List<String>?, quotedMessageId: String?, ptt: Boolean?,
    ): String = buildJsonObject {
        put("chatId", chatId)
        url?.let { put("url", it) }
        base64?.let { put("base64", it) }
        mimetype?.let { put("mimetype", it) }
        filename?.let { put("filename", it) }
        caption?.let { put("caption", it) }
        mentions?.let { m -> putJsonArray("mentions") { m.forEach { add(it) } } }
        quotedMessageId?.let { put("quotedMessageId", it) }
        ptt?.let { put("ptt", it) }
    }.toString()

    suspend fun sendImage(sessionId: String, chatId: String, url: String? = null, base64: String? = null,
                          mimetype: String? = null, filename: String? = null, caption: String? = null,
                          quotedMessageId: String? = null, idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-image",
            body = mediaBody(chatId, url, base64, mimetype, filename, caption, null, quotedMessageId, null),
            idempotencyKey = idempotencyKey)

    suspend fun sendVideo(sessionId: String, chatId: String, url: String? = null, base64: String? = null,
                          mimetype: String? = null, filename: String? = null, caption: String? = null,
                          quotedMessageId: String? = null, idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-video",
            body = mediaBody(chatId, url, base64, mimetype, filename, caption, null, quotedMessageId, null),
            idempotencyKey = idempotencyKey)

    suspend fun sendAudio(sessionId: String, chatId: String, url: String? = null, base64: String? = null,
                          mimetype: String? = null, filename: String? = null, ptt: Boolean? = null,
                          idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-audio",
            body = mediaBody(chatId, url, base64, mimetype, filename, null, null, null, ptt),
            idempotencyKey = idempotencyKey)

    suspend fun sendDocument(sessionId: String, chatId: String, url: String? = null, base64: String? = null,
                             mimetype: String? = null, filename: String? = null, caption: String? = null,
                             idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-document",
            body = mediaBody(chatId, url, base64, mimetype, filename, caption, null, null, null),
            idempotencyKey = idempotencyKey)

    suspend fun sendSticker(sessionId: String, chatId: String, url: String? = null, base64: String? = null,
                            mimetype: String = "image/webp", idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-sticker",
            body = mediaBody(chatId, url, base64, mimetype, null, null, null, null, null),
            idempotencyKey = idempotencyKey)

    suspend fun sendLocation(sessionId: String, chatId: String, latitude: Double, longitude: Double,
                             description: String? = null, address: String? = null,
                             quotedMessageId: String? = null, idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-location", body = buildJsonObject {
            put("chatId", chatId)
            put("latitude", latitude)
            put("longitude", longitude)
            description?.let { put("description", it) }
            address?.let { put("address", it) }
            quotedMessageId?.let { put("quotedMessageId", it) }
        }.toString(), idempotencyKey = idempotencyKey)

    suspend fun sendContact(sessionId: String, chatId: String, contactName: String, contactNumber: String,
                            quotedMessageId: String? = null, idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-contact", body = buildJsonObject {
            put("chatId", chatId)
            put("contactName", contactName)
            put("contactNumber", contactNumber)
            quotedMessageId?.let { put("quotedMessageId", it) }
        }.toString(), idempotencyKey = idempotencyKey)

    suspend fun sendPoll(sessionId: String, chatId: String, name: String, options: List<String>,
                         allowMultipleAnswers: Boolean? = null, quotedMessageId: String? = null,
                         idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-poll", body = buildJsonObject {
            put("chatId", chatId)
            put("name", name)
            putJsonArray("options") { options.forEach { add(it) } }
            allowMultipleAnswers?.let { put("allowMultipleAnswers", it) }
            quotedMessageId?.let { put("quotedMessageId", it) }
        }.toString(), idempotencyKey = idempotencyKey)

    suspend fun sendTemplate(sessionId: String, chatId: String, templateId: String? = null, templateName: String? = null,
                             vars: Map<String, String> = emptyMap(), linkPreview: Boolean? = null,
                             idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/send-template", body = buildJsonObject {
            put("chatId", chatId)
            templateId?.let { put("templateId", it) }
            templateName?.let { put("templateName", it) }
            if (vars.isNotEmpty()) putJsonObject("vars") { vars.forEach { (k, v) -> put(k, v) } }
            linkPreview?.let { put("linkPreview", it) }
        }.toString(), idempotencyKey = idempotencyKey)

    suspend fun sendProduct(sessionId: String, chatId: String, productId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/send-product", body = buildJsonObject {
            put("chatId", chatId); put("productId", productId)
        }.toString())

    suspend fun reply(sessionId: String, chatId: String, quotedMessageId: String, text: String,
                      mentions: List<String>? = null, idempotencyKey: String? = null): ApiResult<SendResultDto> =
        http.call("POST", "/sessions/$sessionId/messages/reply", body = buildJsonObject {
            put("chatId", chatId)
            put("quotedMessageId", quotedMessageId)
            put("text", text)
            mentions?.let { m -> putJsonArray("mentions") { m.forEach { add(it) } } }
        }.toString(), idempotencyKey = idempotencyKey)

    /** send-bulk: max 100 items per request; types text|image|video|audio|document only. */
    suspend fun sendBulk(
        sessionId: String,
        batchId: String?,
        messages: List<JsonObject>,
        delayMs: Int = 3000,
        randomizeDelay: Boolean = true,
        stopOnError: Boolean = false,
    ): ApiResult<BulkSendResponseDto> = http.call(
        "POST", "/sessions/$sessionId/messages/send-bulk",
        body = buildJsonObject {
            batchId?.let { put("batchId", it) }
            putJsonArray("messages") { messages.forEach { add(it) } }
            putJsonObject("options") {
                put("delayBetweenMessages", delayMs)
                put("randomizeDelay", randomizeDelay)
                put("stopOnError", stopOnError)
            }
        }.toString()
    )

    // =================================================================================
    // MESSAGES — actions (10)
    // =================================================================================
    suspend fun react(sessionId: String, chatId: String, messageId: String, emoji: String,
                      idempotencyKey: String? = null): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/react", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("emoji", emoji)
        }.toString(), idempotencyKey = idempotencyKey)

    suspend fun editMessage(sessionId: String, chatId: String, messageId: String, body: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/edit", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("body", body)
        }.toString())

    suspend fun deleteMessage(sessionId: String, chatId: String, messageId: String, forEveryone: Boolean = true): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/delete", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("forEveryone", forEveryone)
        }.toString())

    suspend fun forwardMessage(sessionId: String, fromChatId: String, toChatId: String, messageId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/forward", body = buildJsonObject {
            put("fromChatId", fromChatId); put("toChatId", toChatId); put("messageId", messageId)
        }.toString())

    suspend fun pinMessage(sessionId: String, chatId: String, messageId: String, durationSeconds: Int = 86400): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/pin", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("durationSeconds", durationSeconds)
        }.toString())

    suspend fun unpinMessage(sessionId: String, chatId: String, messageId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/unpin", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId)
        }.toString())

    suspend fun starMessage(sessionId: String, chatId: String, messageId: String, star: Boolean): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/star", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("star", star)
        }.toString())

    suspend fun clickButton(sessionId: String, chatId: String, messageId: String, buttonId: String, text: String? = null): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/click-button", body = buildJsonObject {
            put("chatId", chatId); put("messageId", messageId); put("buttonId", buttonId)
            text?.let { put("text", it) }
        }.toString())

    suspend fun votePoll(sessionId: String, chatId: String, pollMessageId: String, options: List<String>): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/vote-poll", body = buildJsonObject {
            put("chatId", chatId); put("pollMessageId", pollMessageId)
            putJsonArray("options") { options.forEach { add(it) } }
        }.toString())

    // =================================================================================
    // MESSAGES — reads (7)
    // =================================================================================
    suspend fun messages(
        sessionId: String,
        chatId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        before: Long? = null,
        after: String? = null,
        direction: String? = null,
        orderBy: String? = null,
        type: String? = null,
        messageId: String? = null,
    ): ApiResult<MessageListDto> = http.call(
        "GET", "/sessions/$sessionId/messages",
        query = mapOf(
            "chatId" to chatId, "limit" to "$limit", "offset" to "$offset",
            "from" to before?.toString(), "after" to after, "direction" to direction,
            "orderBy" to orderBy, "type" to type, "messageId" to messageId,
        )
    )

    suspend fun chatHistory(sessionId: String, chatId: String, limit: Int = 50, includeMedia: Boolean = false, deep: Boolean = false): ApiResult<JsonArray> =
        http.call("GET", "/sessions/$sessionId/messages/${encode(chatId)}/history",
            query = mapOf("limit" to "$limit", "includeMedia" to "$includeMedia", "deep" to "$deep"))

    suspend fun messageMedia(sessionId: String, chatId: String, messageId: String): ApiResult<ApiOutcome> =
        http.execute("GET", "/sessions/$sessionId/messages/${encode(chatId)}/${encode(messageId)}/media")

    /**
     * Download the real bytes of a media message. Held in memory only — never written to disk,
     * which keeps the promise that WhatsApp media does not pile up on the device.
     */
    suspend fun messageMediaBytes(sessionId: String, chatId: String, messageId: String): ApiResult<ByteArray> =
        http.binary("GET", "/sessions/$sessionId/messages/${encode(chatId)}/${encode(messageId)}/media")

    /** Absolute URL of a media path reported by the gateway (for Coil / previews). */
    fun mediaUrl(mediaPath: String): String = http.absoluteUrl(mediaPath)

    suspend fun messageReactions(sessionId: String, chatId: String, messageId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/messages/${encode(chatId)}/${encode(messageId)}/reactions")

    suspend fun messageReceipts(sessionId: String, chatId: String, messageId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/messages/${encode(chatId)}/${encode(messageId)}/receipts")

    suspend fun batchStatus(sessionId: String, batchId: String): ApiResult<BatchStatusDto> =
        http.call("GET", "/sessions/$sessionId/messages/batch/${encode(batchId)}")

    suspend fun cancelBatch(sessionId: String, batchId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/messages/batch/${encode(batchId)}/cancel")

    // =================================================================================
    // MEDIA (3)
    // =================================================================================
    suspend fun mediaConvertStatus(sessionId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/media/convert")

    suspend fun convertVoice(sessionId: String, url: String? = null, base64: String? = null, mimetype: String? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/media/convert/voice", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }; mimetype?.let { put("mimetype", it) }
        }.toString())

    suspend fun convertVideo(sessionId: String, url: String? = null, base64: String? = null, mimetype: String? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/media/convert/video", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }; mimetype?.let { put("mimetype", it) }
        }.toString())

    // =================================================================================
    // STATUS / STORIES (8)
    // =================================================================================
    suspend fun statusList(sessionId: String): ApiResult<StatusListDto> =
        http.call("GET", "/sessions/$sessionId/status")

    suspend fun postTextStatus(sessionId: String, text: String, backgroundColor: String? = null, font: Int? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/status/send-text", body = buildJsonObject {
            put("text", text)
            backgroundColor?.let { put("backgroundColor", it) }
            font?.let { put("font", it) }
        }.toString())

    suspend fun postImageStatus(sessionId: String, url: String? = null, base64: String? = null,
                                mimetype: String? = null, caption: String? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/status/send-image", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }
            mimetype?.let { put("mimetype", it) }; caption?.let { put("caption", it) }
        }.toString())

    suspend fun postVideoStatus(sessionId: String, url: String? = null, base64: String? = null,
                                mimetype: String? = null, caption: String? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/status/send-video", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }
            mimetype?.let { put("mimetype", it) }; caption?.let { put("caption", it) }
        }.toString())

    suspend fun postVoiceStatus(sessionId: String, url: String? = null, base64: String? = null,
                                mimetype: String? = null, caption: String? = null): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/status/send-voice", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }
            mimetype?.let { put("mimetype", it) }; caption?.let { put("caption", it) }
        }.toString())

    suspend fun statusFromContact(sessionId: String, id: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/status/${encode(id)}")

    suspend fun statusMedia(sessionId: String, statusId: String): ApiResult<ApiOutcome> =
        http.execute("GET", "/sessions/$sessionId/status/${encode(statusId)}/media")

    suspend fun deleteStatus(sessionId: String, id: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/status/${encode(id)}")

    /** Like / react to somebody else's status. */
    suspend fun likeStatus(sessionId: String, statusId: String, emoji: String = "❤️"): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/status/${encode(statusId)}/like",
            body = buildJsonObject { put("emoji", emoji) }.toString())

    /** Mark a status as seen/played (this is what makes the blue ring go away). */
    suspend fun viewStatus(sessionId: String, statusId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/status/${encode(statusId)}/view")

    /** Who viewed one of MY statuses. 501 when the engine does not expose receipts. */
    suspend fun statusViewers(sessionId: String, statusId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/status/${encode(statusId)}/viewers")

    /** Who liked one of MY statuses. */
    suspend fun statusLikes(sessionId: String, statusId: String): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/status/${encode(statusId)}/likes")

    // =================================================================================
    // CHANNELS / NEWSLETTERS (10)
    // =================================================================================
    suspend fun listChannels(sessionId: String): ApiResult<List<ChannelDto>> =
        http.call("GET", "/sessions/$sessionId/channels")

    suspend fun createChannel(sessionId: String, name: String, description: String? = null): ApiResult<ChannelDto> =
        http.call("POST", "/sessions/$sessionId/channels", body = buildJsonObject {
            put("name", name)
            description?.let { put("description", it) }
        }.toString())

    /**
     * Public-channel discovery. Some engines expose it as `/channels/search`, others as the
     * generic `/search?type=channels`; we try the dedicated route first and fall back.
     */
    suspend fun searchChannels(sessionId: String, query: String, limit: Int = 30): ApiResult<JsonElement> {
        val primary = http.call<JsonElement>(
            "GET", "/sessions/$sessionId/channels/search",
            query = mapOf("q" to query, "limit" to "$limit"),
        )
        if (primary is ApiResult.Success) return primary
        val secondary = http.call<JsonElement>(
            "GET", "/search",
            query = mapOf("q" to query, "type" to "channels", "sessionId" to sessionId, "limit" to "$limit"),
        )
        return if (secondary is ApiResult.Success) secondary else primary
    }

    suspend fun subscribeChannel(sessionId: String, inviteCode: String): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/channels/subscribe", body = buildJsonObject { put("inviteCode", inviteCode) }.toString())

    suspend fun getChannel(sessionId: String, channelId: String): ApiResult<ChannelDto> =
        http.call("GET", "/sessions/$sessionId/channels/${encode(channelId)}")

    suspend fun channelMessages(sessionId: String, channelId: String, limit: Int = 50): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/channels/${encode(channelId)}/messages", query = mapOf("limit" to "$limit"))

    suspend fun muteChannel(sessionId: String, channelId: String, mute: Boolean): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/channels/${encode(channelId)}/mute", body = buildJsonObject { put("mute", mute) }.toString())

    suspend fun demoteChannelAdmin(sessionId: String, channelId: String, userId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/channels/${encode(channelId)}/admins/demote", body = buildJsonObject { put("userId", userId) }.toString())

    suspend fun transferChannelOwnership(sessionId: String, channelId: String, newOwnerId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/channels/${encode(channelId)}/owner/transfer", body = buildJsonObject { put("newOwnerId", newOwnerId) }.toString())

    suspend fun deleteChannelContent(sessionId: String, channelId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/channels/${encode(channelId)}/delete")

    suspend fun unsubscribeChannel(sessionId: String, channelId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/channels/${encode(channelId)}")

    // =================================================================================
    // LABELS (7)
    // =================================================================================
    suspend fun listLabels(sessionId: String): ApiResult<List<LabelDto>> =
        http.call("GET", "/sessions/$sessionId/labels")

    suspend fun getLabel(sessionId: String, labelId: String): ApiResult<LabelDto> =
        http.call("GET", "/sessions/$sessionId/labels/${encode(labelId)}")

    suspend fun upsertLabel(sessionId: String, labelId: String, name: String, hexColor: String): ApiResult<LabelDto> =
        http.call("PUT", "/sessions/$sessionId/labels/${encode(labelId)}", body = buildJsonObject {
            put("name", name); put("hexColor", hexColor)
        }.toString())

    suspend fun deleteLabel(sessionId: String, labelId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/labels/${encode(labelId)}")

    suspend fun chatsWithLabel(sessionId: String, labelId: String): ApiResult<List<ChatSummaryDto>> =
        http.call("GET", "/sessions/$sessionId/labels/${encode(labelId)}/chats")

    suspend fun chatLabels(sessionId: String, chatId: String): ApiResult<List<LabelDto>> =
        http.call("GET", "/sessions/$sessionId/labels/chat/${encode(chatId)}")

    suspend fun addLabelToChat(sessionId: String, chatId: String, labelId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/labels/chat/${encode(chatId)}", body = buildJsonObject { put("labelId", labelId) }.toString())

    suspend fun removeLabelFromChat(sessionId: String, chatId: String, labelId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/labels/chat/${encode(chatId)}/${encode(labelId)}")

    // =================================================================================
    // TEMPLATES (5)
    // =================================================================================
    suspend fun listTemplates(sessionId: String): ApiResult<List<TemplateDto>> =
        http.call("GET", "/sessions/$sessionId/templates")

    suspend fun createTemplate(sessionId: String, name: String, body: String, header: String? = null, footer: String? = null): ApiResult<TemplateDto> =
        http.call("POST", "/sessions/$sessionId/templates", body = buildJsonObject {
            put("name", name); put("body", body)
            header?.let { put("header", it) }; footer?.let { put("footer", it) }
        }.toString())

    suspend fun getTemplate(sessionId: String, id: String): ApiResult<TemplateDto> =
        http.call("GET", "/sessions/$sessionId/templates/$id")

    suspend fun updateTemplate(sessionId: String, id: String, body: JsonObject): ApiResult<TemplateDto> =
        http.call("PUT", "/sessions/$sessionId/templates/$id", body = body.toString())

    suspend fun deleteTemplate(sessionId: String, id: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/templates/$id")

    // =================================================================================
    // CATALOG (3)
    // =================================================================================
    suspend fun catalog(sessionId: String): ApiResult<CatalogDto> = http.call("GET", "/sessions/$sessionId/catalog")

    suspend fun products(sessionId: String, limit: Int = 50): ApiResult<JsonElement> =
        http.call("GET", "/sessions/$sessionId/catalog/products", query = mapOf("limit" to "$limit"))

    suspend fun product(sessionId: String, productId: String): ApiResult<ProductDto> =
        http.call("GET", "/sessions/$sessionId/catalog/products/${encode(productId)}")

    // =================================================================================
    // CALLS (2)
    // =================================================================================
    suspend fun createCallLink(sessionId: String, body: JsonObject = buildJsonObject { }): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/calls/link", body = body.toString())

    suspend fun rejectCall(sessionId: String, callId: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/sessions/$sessionId/calls/${encode(callId)}/reject")

    // =================================================================================
    // PROFILE (4)
    // =================================================================================
    suspend fun setProfileName(sessionId: String, name: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/profile/name", body = buildJsonObject { put("name", name) }.toString())

    suspend fun setProfileStatus(sessionId: String, status: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/profile/status", body = buildJsonObject { put("status", status) }.toString())

    suspend fun setProfilePicture(sessionId: String, url: String? = null, base64: String? = null): ApiResult<ApiOutcome> =
        http.execute("PUT", "/sessions/$sessionId/profile/picture", body = buildJsonObject {
            url?.let { put("url", it) }; base64?.let { put("base64", it) }
        }.toString())

    suspend fun deleteProfilePicture(sessionId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/profile/picture")

    // =================================================================================
    // AUTOMATION RULES (5)
    // =================================================================================
    suspend fun listRules(sessionId: String): ApiResult<List<AutomationRuleDto>> =
        http.call("GET", "/sessions/$sessionId/automation-rules")

    suspend fun createRule(
        sessionId: String, name: String, replyText: String,
        conditions: JsonObject? = null, cooldownSeconds: Int = 60, enabled: Boolean = true,
    ): ApiResult<AutomationRuleDto> = http.call("POST", "/sessions/$sessionId/automation-rules", body = buildJsonObject {
        put("name", name); put("replyText", replyText)
        conditions?.let { put("conditions", it) }
        put("cooldownSeconds", cooldownSeconds); put("enabled", enabled)
    }.toString())

    suspend fun getRule(sessionId: String, ruleId: String): ApiResult<AutomationRuleDto> =
        http.call("GET", "/sessions/$sessionId/automation-rules/$ruleId")

    suspend fun updateRule(sessionId: String, ruleId: String, body: JsonObject): ApiResult<AutomationRuleDto> =
        http.call("PUT", "/sessions/$sessionId/automation-rules/$ruleId", body = body.toString())

    suspend fun deleteRule(sessionId: String, ruleId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/automation-rules/$ruleId")

    // =================================================================================
    // WEBHOOKS (9)
    // =================================================================================
    suspend fun listWebhooks(sessionId: String): ApiResult<List<WebhookDto>> =
        http.call("GET", "/sessions/$sessionId/webhooks")

    suspend fun createWebhook(
        sessionId: String, url: String, events: List<String>, secret: String? = null,
        headers: JsonObject? = null, filters: JsonObject? = null, retryCount: Int? = null,
    ): ApiResult<WebhookDto> = http.call("POST", "/sessions/$sessionId/webhooks", body = buildJsonObject {
        put("url", url)
        putJsonArray("events") { events.forEach { add(it) } }
        secret?.let { put("secret", it) }
        headers?.let { put("headers", it) }
        filters?.let { put("filters", it) }
        retryCount?.let { put("retryCount", it) }
    }.toString())

    suspend fun getWebhook(sessionId: String, id: String): ApiResult<WebhookDto> =
        http.call("GET", "/sessions/$sessionId/webhooks/$id")

    suspend fun updateWebhook(sessionId: String, id: String, body: JsonObject): ApiResult<WebhookDto> =
        http.call("PUT", "/sessions/$sessionId/webhooks/$id", body = body.toString())

    suspend fun deleteWebhook(sessionId: String, id: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/sessions/$sessionId/webhooks/$id")

    suspend fun testWebhook(sessionId: String, id: String): ApiResult<JsonElement> =
        http.call("POST", "/sessions/$sessionId/webhooks/$id/test")

    suspend fun allWebhooks(): ApiResult<List<WebhookDto>> = http.call("GET", "/webhooks")

    suspend fun deliveryFailures(): ApiResult<JsonElement> = http.call("GET", "/webhooks/delivery-failures")

    suspend fun redriveDeliveries(body: JsonObject): ApiResult<JsonElement> =
        http.call("POST", "/webhooks/delivery-failures/redrive", body = body.toString())

    // =================================================================================
    // STATS / SEARCH / SETTINGS / AUDIT (8)
    // =================================================================================
    suspend fun statsOverview(): ApiResult<JsonElement> = http.call("GET", "/stats/overview")
    suspend fun messageStats(days: Int = 7): ApiResult<JsonElement> =
        http.call("GET", "/stats/messages", query = mapOf("days" to "$days"))
    suspend fun sessionStats(sessionId: String): ApiResult<JsonElement> =
        http.call("GET", "/stats/sessions/$sessionId")

    suspend fun search(
        query: String, match: String = "contains", sessionId: String? = null,
        chatId: String? = null, limit: Int = 50, offset: Int = 0,
    ): ApiResult<JsonElement> = http.call("GET", "/search", query = mapOf(
        "q" to query, "match" to match, "sessionId" to sessionId, "chatId" to chatId,
        "limit" to "$limit", "offset" to "$offset",
    ))

    suspend fun settings(): ApiResult<JsonElement> = http.call("GET", "/settings")

    suspend fun audit(limit: Int = 100): ApiResult<JsonElement> =
        http.call("GET", "/audit", query = mapOf("limit" to "$limit"))

    // =================================================================================
    // INFRA (13) + PLUGINS (14) + INTEGRATION/INGRESS
    // =================================================================================
    suspend fun infraConfig(): ApiResult<JsonElement> = http.call("GET", "/infra/config")
    suspend fun saveInfraConfig(body: JsonObject): ApiResult<JsonElement> = http.call("PUT", "/infra/config", body = body.toString())
    suspend fun infraEngines(): ApiResult<JsonElement> = http.call("GET", "/infra/engines")
    suspend fun infraCurrentEngine(): ApiResult<JsonElement> = http.call("GET", "/infra/engines/current")
    suspend fun infraStatus(): ApiResult<JsonElement> = http.call("GET", "/infra/status")
    suspend fun infraHealth(): ApiResult<JsonElement> = http.call("GET", "/infra/health")
    suspend fun infraRestart(): ApiResult<JsonElement> = http.call("POST", "/infra/restart")
    suspend fun infraUpdateCheck(): ApiResult<JsonElement> = http.call("GET", "/infra/update-check")
    suspend fun infraExportData(): ApiResult<ApiOutcome> = http.execute("GET", "/infra/export-data")
    suspend fun infraImportData(body: JsonObject): ApiResult<JsonElement> = http.call("POST", "/infra/import-data", body = body.toString())
    suspend fun storageFileCount(): ApiResult<JsonElement> = http.call("GET", "/infra/storage/files/count")
    suspend fun storageExport(): ApiResult<ApiOutcome> = http.execute("GET", "/infra/storage/export")
    suspend fun storageImport(body: JsonObject): ApiResult<JsonElement> = http.call("POST", "/infra/storage/import", body = body.toString())

    suspend fun listPlugins(): ApiResult<JsonElement> = http.call("GET", "/plugins")
    suspend fun pluginCatalog(): ApiResult<JsonElement> = http.call("GET", "/plugins/catalog")
    suspend fun installPlugin(body: JsonObject): ApiResult<JsonElement> = http.call("POST", "/plugins/install", body = body.toString())
    suspend fun installPluginFromUrl(body: JsonObject): ApiResult<JsonElement> = http.call("POST", "/plugins/install-url", body = body.toString())
    suspend fun getPlugin(id: String): ApiResult<JsonElement> = http.call("GET", "/plugins/$id")
    suspend fun deletePlugin(id: String): ApiResult<ApiOutcome> = http.execute("DELETE", "/plugins/$id")
    suspend fun pluginConfig(id: String, body: JsonObject): ApiResult<JsonElement> = http.call("PUT", "/plugins/$id/config", body = body.toString())
    suspend fun pluginConfigUi(id: String): ApiResult<ApiOutcome> = http.execute("GET", "/plugins/$id/config-ui")
    suspend fun pluginSessionConfig(id: String, sessionId: String, body: JsonObject): ApiResult<JsonElement> =
        http.call("PUT", "/plugins/$id/config/$sessionId", body = body.toString())
    suspend fun pluginHealth(id: String): ApiResult<JsonElement> = http.call("GET", "/plugins/$id/health")
    suspend fun enablePlugin(id: String): ApiResult<JsonElement> = http.call("POST", "/plugins/$id/enable")
    suspend fun disablePlugin(id: String): ApiResult<JsonElement> = http.call("POST", "/plugins/$id/disable")
    suspend fun updatePlugin(id: String): ApiResult<JsonElement> = http.call("POST", "/plugins/$id/update")
    suspend fun pluginSessions(id: String, body: JsonObject): ApiResult<JsonElement> = http.call("PUT", "/plugins/$id/sessions", body = body.toString())

    suspend fun integrationInstances(pluginId: String): ApiResult<JsonElement> = http.call("GET", "/integration/plugins/$pluginId/instances")
    suspend fun createIntegrationInstance(pluginId: String, body: JsonObject): ApiResult<JsonElement> =
        http.call("POST", "/integration/plugins/$pluginId/instances", body = body.toString())
    suspend fun getIntegrationInstance(pluginId: String, instanceId: String): ApiResult<JsonElement> =
        http.call("GET", "/integration/plugins/$pluginId/instances/$instanceId")
    suspend fun updateIntegrationInstance(pluginId: String, instanceId: String, body: JsonObject): ApiResult<JsonElement> =
        http.call("PATCH", "/integration/plugins/$pluginId/instances/$instanceId", body = body.toString())
    suspend fun deleteIntegrationInstance(pluginId: String, instanceId: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/integration/plugins/$pluginId/instances/$instanceId")
    suspend fun regenerateIntegrationSecret(pluginId: String, instanceId: String): ApiResult<JsonElement> =
        http.call("POST", "/integration/plugins/$pluginId/instances/$instanceId/regenerate-secret")
    suspend fun redriveIntegration(pluginId: String, instanceId: String): ApiResult<JsonElement> =
        http.call("POST", "/integration/instances/$pluginId/$instanceId/redrive")

    suspend fun ingressGet(pluginId: String, instanceId: String, path: String): ApiResult<ApiOutcome> =
        http.execute("GET", "/ingress/$pluginId/$instanceId/$path")
    suspend fun ingressPost(pluginId: String, instanceId: String, path: String, body: String): ApiResult<ApiOutcome> =
        http.execute("POST", "/ingress/$pluginId/$instanceId/$path", body = body)
    suspend fun ingressPut(pluginId: String, instanceId: String, path: String, body: String): ApiResult<ApiOutcome> =
        http.execute("PUT", "/ingress/$pluginId/$instanceId/$path", body = body)
    suspend fun ingressPatch(pluginId: String, instanceId: String, path: String, body: String): ApiResult<ApiOutcome> =
        http.execute("PATCH", "/ingress/$pluginId/$instanceId/$path", body = body)
    suspend fun ingressDelete(pluginId: String, instanceId: String, path: String): ApiResult<ApiOutcome> =
        http.execute("DELETE", "/ingress/$pluginId/$instanceId/$path")

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
