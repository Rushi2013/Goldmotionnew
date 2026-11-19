package ai.jarvis.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message", indices = [Index("conversationId", "timestampEpochMs")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,                 // USER | JARVIS | SYSTEM | INBOUND
    val text: String,
    val mediaType: String? = null,
    val mediaLocalUri: String? = null,
    val intentName: String? = null,
    val intentJson: String? = null,
    val confidence: Double? = null,
    val source: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val deliveryState: String? = null,
    val apiMessageId: String? = null,
    val httpStatus: Int? = null,
    val chatIdApi: String? = null,
    val fromMe: Boolean = false,
    val quotedId: String? = null,
)

@Entity(tableName = "conversation", indices = [Index(value = ["apiChatId"], unique = true)])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val apiChatId: String,
    val title: String,
    val kind: String = "individual",
    val lastMessageAtEpochMs: Long = 0,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
)

@Entity(tableName = "resolved_contact")
data class ResolvedContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val phoneE164: String,
    val source: String,
    val notes: String? = null,
)

@Entity(tableName = "auto_reply_config")
data class AutoReplyConfigEntity(
    @PrimaryKey val id: Int = 1,
    val mode: String = "OFF",
    val categoriesJson: String = "{}",
    val quietHours: String = "",
    val capsJson: String = "{}",
    val tone: String = "hinglish",
    val language: String = "auto",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "auto_reply_allow")
data class AutoReplyAllowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val apiChatId: String,
    val contactName: String,
    val addedAt: Long = System.currentTimeMillis(),
    val enabledCategoriesJson: String? = null,
)

@Entity(tableName = "autopilot_log", indices = [Index(value = ["inboundMessageId"], unique = true)])
data class AutopilotLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inboundMessageId: String,
    val chatId: String,
    val chatName: String? = null,
    val inboundText: String? = null,
    val category: String,
    val decision: String,
    val confidence: Double = 0.0,
    val draft: String? = null,
    val sentText: String? = null,
    val resultState: String = "",
    val errorText: String? = null,
    val model: String = "",
    val latencyMs: Long = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "escalation")
data class EscalationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val chatName: String? = null,
    val inboundMessageId: String,
    val inboundText: String? = null,
    val category: String,
    val suggestedJson: String = "[]",
    val reason: String = "",
    val state: String = "PENDING", // PENDING | SENT | DISMISSED | EXPIRED
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 24 * 3600_000L,
)

@Entity(tableName = "chat_memory")
data class ChatMemoryEntity(
    @PrimaryKey val chatId: String,
    val languagePref: String? = null,
    val doNotAutoReply: Boolean = false,
    val familyFlag: Boolean = false,
    val tonePref: String? = null,
    val factsJson: String = "{}",
    val statsJson: String = "{}",
)

@Entity(tableName = "bulk_batch")
data class BulkBatchEntity(
    @PrimaryKey val batchId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val type: String,
    val contentJson: String,
    val optionsJson: String = "{}",
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val state: String = "DRAFT", // DRAFT | RUNNING | PAUSED | DONE | CANCELLED
    val serverBatchId: String? = null,
)

@Entity(tableName = "bulk_recipient", indices = [Index(value = ["batchId", "chatId"], unique = true)])
data class BulkRecipientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val chatId: String,
    val name: String? = null,
    val varJson: String? = null,
    val state: String = "PENDING", // PENDING | SENDING | SENT | FAILED | SKIPPED | UNKNOWN
    val messageId: String? = null,
    val errorText: String? = null,
    val attemptCount: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAtEpochMs: Long,
    val state: String = "PENDING",
)

@Entity(tableName = "api_call_log")
data class ApiCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val method: String,
    val path: String,
    val statusCode: Int,
    val latencyMs: Long,
    val errorCode: String? = null,
    val isDemo: Boolean = false,
    val host: String = "",
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "kv")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "seen_message", indices = [Index(value = ["key"], unique = true)])
data class SeenMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)
