package ai.jarvis.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs mirror OpenWA 0.30.3 (verified against the live openapi.json that ships in this repo
 * as docs/openwa-openapi.json). Fields OpenWA may omit are nullable with defaults so that a
 * gateway upgrade can never crash the app.
 */
@Serializable
data class SessionDto(
    val id: String,
    val name: String = "",
    val status: String = "created",
    val phone: String? = null,
    val pushName: String? = null,
    val connectedAt: String? = null,
    val lastActive: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastError: String? = null,
    val restriction: JsonElement? = null,
    val engineLoaded: Boolean = false,
) {
    val isReady: Boolean get() = status == "ready"
    val isQrPhase: Boolean get() = status == "qr_ready" || status == "authenticating"
}

@Serializable
data class ChatSummaryDto(
    val id: String,
    val name: String = "",
    val phone: String? = null,
    val isGroup: Boolean = false,
    val kind: String = "individual",
    val unreadCount: Int = 0,
    val timestamp: Long? = null,
    val lastMessage: String? = null,
    val lastMessageType: String? = null,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val muteExpiration: Long? = null,
)

@Serializable
data class MessageDto(
    val id: String,
    val sessionId: String? = null,
    val waMessageId: String? = null,
    val chatId: String = "",
    val chatName: String? = null,
    val author: String? = null,
    val from: String = "",
    val to: String = "",
    val body: String? = null,
    val type: String = "text",
    val direction: String = "incoming",
    val timestamp: Long? = null,
    val metadata: JsonElement? = null,
    val mediaPath: String? = null,
    val mediaMimetype: String? = null,
    val status: String = "sent",
    val createdAt: String? = null,
) {
    val isOutgoing: Boolean get() = direction == "outgoing"
    val hasMedia: Boolean get() = mediaPath != null || type in setOf("image", "video", "audio", "document", "sticker", "voice")
    val timestampMs: Long get() = timestamp?.let { if (it < 10_000_000_000L) it * 1000 else it } ?: 0L
    val apiMessageId: String get() = waMessageId ?: id
}

@Serializable
data class MessageListDto(
    val messages: List<MessageDto> = emptyList(),
    val total: Int = 0,
    val unknownTimestampTotal: Int = 0,
)

@Serializable
data class SendResultDto(
    val messageId: String = "",
    val timestamp: Long = 0,
)

@Serializable
data class BulkSendResponseDto(
    val batchId: String = "",
    val status: String = "pending",
    val totalMessages: Int = 0,
    val estimatedCompletionTime: String? = null,
    val statusUrl: String? = null,
)

@Serializable
data class BatchProgressDto(
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val cancelled: Int = 0,
)

@Serializable
data class BatchMessageResultDto(
    val chatId: String = "",
    val status: String = "pending",
    val messageId: String? = null,
    val error: JsonElement? = null,
    val sentAt: String? = null,
)

@Serializable
data class BatchStatusDto(
    val batchId: String = "",
    val status: String = "pending",
    val progress: BatchProgressDto = BatchProgressDto(),
    val results: List<BatchMessageResultDto> = emptyList(),
    val startedAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class ContactDto(
    val id: String,
    val name: String? = null,
    val pushName: String? = null,
    val number: String = "",
    val isMyContact: Boolean = false,
    val isBlocked: Boolean = false,
    val profilePicUrl: String? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: pushName?.takeIf { it.isNotBlank() } ?: number
}

@Serializable
data class NumberCheckDto(
    val number: String = "",
    val exists: Boolean = false,
    val whatsappId: String = "",
)

@Serializable
data class GroupSummaryDto(
    val id: String,
    val name: String = "",
    val linkedParentJID: String? = null,
)

@Serializable
data class GroupParticipantDto(
    val id: String = "",
    val number: String = "",
    val name: String? = null,
    val isAdmin: Boolean = false,
    val isSuperAdmin: Boolean = false,
)

@Serializable
data class GroupInfoDto(
    val id: String = "",
    val name: String = "",
    val linkedParentJID: String? = null,
    val description: String? = null,
    val owner: String? = null,
    val createdAt: Long? = null,
    val participants: List<GroupParticipantDto> = emptyList(),
    val isReadOnly: Boolean? = null,
    val isAnnounce: Boolean? = null,
    val announce: Boolean? = null,
    val locked: Boolean? = null,
    val ephemeralSeconds: Long? = null,
    val memberAddMode: String? = null,
)

@Serializable
data class GroupSettingsDto(
    val announce: Boolean? = null,
    val locked: Boolean? = null,
    val ephemeralSeconds: Long? = null,
    val memberAddMode: String? = null,
    val isAnnounce: Boolean? = null,
    val isReadOnly: Boolean? = null,
    @SerialName("membershipApprovalMode") val membershipApprovalMode: Boolean? = null,
    val raw: JsonElement? = null,
)

@Serializable
data class LabelDto(
    val id: String,
    val name: String = "",
    val hexColor: String = "#00E5FF",
)

@Serializable
data class TemplateDto(
    val id: String,
    val sessionId: String? = null,
    val name: String = "",
    val body: String = "",
    val header: String? = null,
    val footer: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class WebhookDto(
    val id: String,
    val sessionId: String? = null,
    val url: String = "",
    val events: List<String> = emptyList(),
    val filters: JsonElement? = null,
    val active: Boolean = true,
    val retryCount: Int = 3,
    val lastTriggeredAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AutomationRuleDto(
    val id: String,
    val sessionId: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val conditions: JsonElement? = null,
    val replyText: String = "",
    val cooldownSeconds: Int = 60,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class StatusDto(
    val id: String,
    val contact: JsonElement? = null,
    val type: String = "text",
    val caption: String? = null,
    val mediaUrl: String? = null,
    val backgroundColor: String? = null,
    val font: Int? = null,
    val timestamp: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class StatusListDto(val statuses: List<StatusDto> = emptyList())

@Serializable
data class ChannelDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val inviteCode: String? = null,
    val subscriberCount: Int? = null,
    val picture: String? = null,
    val verified: Boolean? = null,
    val createdAt: Long? = null,
)

@Serializable
data class CatalogDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val productCount: Int = 0,
    val url: String = "",
)

@Serializable
data class ProductDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val priceFormatted: String? = null,
    val imageUrl: String? = null,
    val url: String = "",
    val isAvailable: Boolean = true,
    val retailerId: String? = null,
)

@Serializable
data class ProductListDto(val products: List<ProductDto> = emptyList(), val pagination: JsonElement? = null)

@Serializable
data class SearchHitDto(
    val messageId: String = "",
    val waMessageId: String? = null,
    val sessionId: String? = null,
    val chatId: String = "",
    val body: String? = null,
    val snippet: String? = null,
    val timestamp: Long? = null,
    val type: String? = null,
    val direction: String? = null,
    val from: String? = null,
    val score: Double? = null,
)

@Serializable
data class SearchResultsDto(
    val hits: List<SearchHitDto> = emptyList(),
    val total: Int = 0,
    val tookMs: Long = 0,
    val provider: String = "",
)

@Serializable
data class ValidateApiKeyDto(
    val valid: Boolean = false,
    val role: String? = null,
    val engineType: String? = null,
    val scoped: Boolean = false,
)

@Serializable
data class ApiKeyDto(
    val id: String,
    val name: String = "",
    val keyPrefix: String? = null,
    val role: String = "operator",
    val allowedSessions: List<String> = emptyList(),
    val allowedChats: List<String>? = null,
    val isActive: Boolean = true,
    val usageCount: Int = 0,
    val createdAt: String? = null,
    val apiKey: String? = null,
)

@Serializable
data class HealthDto(
    val status: String = "unknown",
    val details: JsonElement? = null,
)

@Serializable
data class OverviewStatsDto(
    val sessions: JsonElement? = null,
    val messages: JsonElement? = null,
)

@Serializable
data class SessionStatsDto(
    val session: JsonElement? = null,
    val messages: JsonElement? = null,
    val topChats: JsonElement? = null,
    val uptimeSeconds: Long? = null,
)

@Serializable
data class PairingCodeDto(
    val pairingCode: String? = null,
    val code: String? = null,
)

@Serializable
data class QrDto(val qrCode: String = "")

@Serializable
data class MessageActionDto(
    val success: Boolean = true,
    val messageId: String? = null,
    val message: String? = null,
    val raw: JsonElement? = null,
)

/** 27 webhook event names exposed by OpenWA 0.30.3. */
object WebhookEvents {
    val ALL = listOf(
        "message.received", "message.sent", "message.ack", "message.receipt", "message.failed",
        "message.revoked", "message.reaction", "message.edited", "status.received",
        "session.status", "session.qr", "session.authenticated", "session.disconnected",
        "session.reconnect_loop", "session.restriction", "presence.update", "event.response",
        "poll.vote", "group.join", "group.leave", "group.update", "group.join_request",
        "call.received", "call.accepted", "call.rejected", "call.missed", "*",
    )
}

/** Android-side record of what we actually sent to the gateway (never a delivery claim). */
@Serializable
data class SendOutcome(
    val messageId: String = "",
    val timestamp: Long = 0,
    val state: String = "ACCEPTED",
    val httpStatus: Int = 0,
    val error: String? = null,
)
