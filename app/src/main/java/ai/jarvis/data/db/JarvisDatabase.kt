package ai.jarvis.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        ConversationEntity::class,
        ResolvedContactEntity::class,
        AutoReplyConfigEntity::class,
        AutoReplyAllowEntity::class,
        AutopilotLogEntity::class,
        EscalationEntity::class,
        ChatMemoryEntity::class,
        BulkBatchEntity::class,
        BulkRecipientEntity::class,
        ReminderEntity::class,
        ApiCallLogEntity::class,
        KeyValueEntity::class,
        SeenMessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun autoReplyDao(): AutoReplyDao
    abstract fun autopilotLogDao(): AutopilotLogDao
    abstract fun escalationDao(): EscalationDao
    abstract fun chatMemoryDao(): ChatMemoryDao
    abstract fun bulkDao(): BulkDao
    abstract fun reminderDao(): ReminderDao
    abstract fun logDao(): LogDao
    abstract fun kvDao(): KvDao
    abstract fun seenMessageDao(): SeenMessageDao

    companion object {
        @Volatile private var instance: JarvisDatabase? = null

        /** Explicit migration registry for future versions; v1 is the baseline. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun get(context: Context): JarvisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis.db",
                )
                    .addMigrations(*MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
