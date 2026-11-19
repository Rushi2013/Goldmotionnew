package ai.jarvis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_message WHERE conversationId = :chatId ORDER BY timestampEpochMs ASC")
    fun observe(chatId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatMessageEntity): Long

    @Query("DELETE FROM chat_message WHERE conversationId = :chatId")
    suspend fun clear(chatId: String)

    @Query("DELETE FROM chat_message")
    suspend fun clearAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation ORDER BY pinned DESC, lastMessageAtEpochMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity): Long

    @Query("DELETE FROM conversation")
    suspend fun clearAll()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM resolved_contact ORDER BY displayName")
    fun observeAll(): Flow<List<ResolvedContactEntity>>

    @Insert
    suspend fun insert(entity: ResolvedContactEntity): Long

    @Query("DELETE FROM resolved_contact")
    suspend fun clearAll()
}

@Dao
interface AutoReplyDao {
    @Query("SELECT * FROM auto_reply_config WHERE id = 1")
    fun observeConfig(): Flow<AutoReplyConfigEntity?>

    @Query("SELECT * FROM auto_reply_config WHERE id = 1")
    suspend fun getConfig(): AutoReplyConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(entity: AutoReplyConfigEntity)

    @Query("SELECT * FROM auto_reply_allow ORDER BY addedAt DESC")
    fun observeAllowlist(): Flow<List<AutoReplyAllowEntity>>

    @Query("SELECT * FROM auto_reply_allow")
    suspend fun allowlist(): List<AutoReplyAllowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAllow(entity: AutoReplyAllowEntity): Long

    @Query("DELETE FROM auto_reply_allow WHERE apiChatId = :chatId")
    suspend fun removeAllow(chatId: String)

    @Query("SELECT COUNT(*) FROM auto_reply_allow")
    suspend fun allowCount(): Int
}

@Dao
interface AutopilotLogDao {
    @Query("SELECT * FROM autopilot_log ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AutopilotLogEntity>>

    @Query("SELECT * FROM autopilot_log ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<AutopilotLogEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AutopilotLogEntity): Long

    @Query("SELECT COUNT(*) FROM autopilot_log WHERE inboundMessageId = :id")
    suspend fun seen(id: String): Int

    @Query("SELECT COUNT(*) FROM autopilot_log WHERE chatId = :chatId AND decision = 'AUTO_SEND' AND createdAtEpochMs > :since")
    suspend fun autoSentSince(chatId: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM autopilot_log WHERE decision = 'AUTO_SEND' AND createdAtEpochMs > :since")
    suspend fun autoSentGlobalSince(since: Long): Int

    @Query("SELECT * FROM autopilot_log WHERE chatId = :chatId ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun forChat(chatId: String, limit: Int = 20): List<AutopilotLogEntity>

    @Query("SELECT COUNT(*) FROM autopilot_log WHERE decision = 'AUTO_SEND' AND createdAtEpochMs > :since")
    fun observeAutoSentSince(since: Long): Flow<Int>

    @Query("DELETE FROM autopilot_log")
    suspend fun clearAll()
}

@Dao
interface EscalationDao {
    @Query("SELECT * FROM escalation WHERE state = 'PENDING' ORDER BY createdAtEpochMs DESC")
    fun observePending(): Flow<List<EscalationEntity>>

    @Query("SELECT * FROM escalation WHERE state = 'PENDING' ORDER BY createdAtEpochMs DESC")
    suspend fun pending(): List<EscalationEntity>

    @Query("SELECT COUNT(*) FROM escalation WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM escalation WHERE chatId = :chatId AND state = 'PENDING' ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun pendingForChat(chatId: String): EscalationEntity?

    @Query("SELECT * FROM escalation WHERE inboundMessageId = :messageId LIMIT 1")
    suspend fun byInbound(messageId: String): EscalationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EscalationEntity): Long

    @Update
    suspend fun update(entity: EscalationEntity)

    @Query("UPDATE escalation SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("UPDATE escalation SET state = 'EXPIRED' WHERE state = 'PENDING' AND expiresAtEpochMs < :now")
    suspend fun expireOld(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM escalation")
    suspend fun clearAll()
}

@Dao
interface ChatMemoryDao {
    @Query("SELECT * FROM chat_memory WHERE chatId = :chatId")
    suspend fun get(chatId: String): ChatMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatMemoryEntity)

    @Query("SELECT * FROM chat_memory")
    fun observeAll(): Flow<List<ChatMemoryEntity>>
}

@Dao
interface BulkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBatch(entity: BulkBatchEntity)

    @Query("SELECT * FROM bulk_batch ORDER BY createdAtEpochMs DESC")
    fun observeBatches(): Flow<List<BulkBatchEntity>>

    @Query("SELECT * FROM bulk_batch WHERE batchId = :id")
    suspend fun batch(id: String): BulkBatchEntity?

    @Query("SELECT * FROM bulk_batch WHERE state = 'RUNNING' OR state = 'PAUSED' ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun resumableBatch(): BulkBatchEntity?

    @Query("SELECT * FROM bulk_batch WHERE batchId = :id")
    fun observeBatch(id: String): Flow<BulkBatchEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRecipients(items: List<BulkRecipientEntity>)

    @Query("SELECT * FROM bulk_recipient WHERE batchId = :batchId ORDER BY id")
    fun observeRecipients(batchId: String): Flow<List<BulkRecipientEntity>>

    @Query("SELECT * FROM bulk_recipient WHERE batchId = :batchId ORDER BY id")
    suspend fun recipients(batchId: String): List<BulkRecipientEntity>

    @Query("SELECT * FROM bulk_recipient WHERE batchId = :batchId AND state = 'PENDING' ORDER BY id")
    suspend fun pendingRecipients(batchId: String): List<BulkRecipientEntity>

    @Query("UPDATE bulk_recipient SET state = :state, messageId = :messageId, errorText = :error, attemptCount = attemptCount + 1, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun markRecipient(id: Long, state: String, messageId: String?, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE bulk_batch SET sent = :sent, failed = :failed, state = :state WHERE batchId = :id")
    suspend fun updateProgress(id: String, sent: Int, failed: Int, state: String)

    @Query("SELECT COUNT(*) FROM bulk_recipient WHERE chatId = :chatId AND state = 'SENT' AND updatedAtEpochMs > :since")
    suspend fun sentToSince(chatId: String, since: Long): Int

    @Query("DELETE FROM bulk_recipient WHERE batchId = :batchId")
    suspend fun clearRecipients(batchId: String)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE state = 'PENDING' ORDER BY triggerAtEpochMs")
    fun observePending(): Flow<List<ReminderEntity>>

    @Insert
    suspend fun insert(entity: ReminderEntity): Long

    @Query("UPDATE reminder SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?
}

@Dao
interface LogDao {
    @Query("SELECT * FROM api_call_log ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<ApiCallLogEntity>>

    @Insert
    suspend fun insert(entity: ApiCallLogEntity)

    @Query("DELETE FROM api_call_log")
    suspend fun clearAll()

    @Query("DELETE FROM api_call_log WHERE timestampEpochMs < :before")
    suspend fun prune(before: Long)
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: KeyValueEntity)

    @Query("DELETE FROM kv")
    suspend fun clearAll()
}

@Dao
interface SeenMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SeenMessageEntity): Long

    @Query("DELETE FROM seen_message WHERE createdAtEpochMs < :before")
    suspend fun prune(before: Long)
}
