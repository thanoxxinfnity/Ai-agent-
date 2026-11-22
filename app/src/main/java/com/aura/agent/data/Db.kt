package com.aura.agent.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object Ids {
    private val last = AtomicLong(0)
    fun id(): String = UUID.randomUUID().toString()
    /** Strictly increasing timestamp so message ordering is stable. */
    fun now(): Long {
        val t = System.currentTimeMillis()
        return last.updateAndGet { if (t > it) t else it + 1 }
    }
}

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titled: Boolean = false,
    val running: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val toolCalls: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    /** True once the message has been folded into a summary; it stays visible but is no longer sent to the model. */
    @ColumnInfo(defaultValue = "0") val compacted: Boolean = false,
)

@Entity(tableName = "checkpoints", indices = [Index("chatId"), Index("turnId")])
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val turnId: String,
    val createdAt: Long,
    /** "github" (branch head sha) or "cloud" (git snapshot inside the codespace) */
    val kind: String,
    /** github: owner/repo@branch   cloud: absolute repo path */
    val target: String,
    val ref: String,
    val label: String,
    @ColumnInfo(defaultValue = "0") val restored: Boolean = false,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun chats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE running = 1")
    suspend fun runningChats(): List<ChatEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun messages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    suspend fun messagesOnce(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(c: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(m: MessageEntity)

    @Query("UPDATE chats SET title = :title, titled = :titled WHERE id = :id")
    suspend fun setTitle(id: String, title: String, titled: Boolean)

    @Query("UPDATE chats SET running = :running, updatedAt = :t WHERE id = :id")
    suspend fun setRunning(id: String, running: Boolean, t: Long)

    @Query("UPDATE chats SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: String, t: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE chatId = :id")
    suspend fun deleteMessages(id: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String)

    @Query("UPDATE messages SET compacted = 1 WHERE id IN (:ids)")
    suspend fun markCompacted(ids: List<String>)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND role = 'tool' AND content = ''")
    suspend fun deleteBlankTools(chatId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(c: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE turnId = :turnId ORDER BY createdAt ASC")
    suspend fun checkpointsForTurn(turnId: String): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun checkpoints(chatId: String): Flow<List<CheckpointEntity>>

    @Query("UPDATE checkpoints SET restored = 1 WHERE turnId = :turnId")
    suspend fun markRestored(turnId: String)

    @Query("DELETE FROM checkpoints WHERE chatId = :chatId")
    suspend fun deleteCheckpoints(chatId: String)
}

@Database(entities = [ChatEntity::class, MessageEntity::class, CheckpointEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        /** Keeps every existing chat: adds the compacted flag and the checkpoints table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN compacted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS checkpoints (id TEXT NOT NULL, chatId TEXT NOT NULL, turnId TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, target TEXT NOT NULL, ref TEXT NOT NULL, label TEXT NOT NULL, restored INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_checkpoints_chatId ON checkpoints (chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_checkpoints_turnId ON checkpoints (turnId)")
            }
        }
    }
}
