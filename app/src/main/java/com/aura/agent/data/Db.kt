package com.aura.agent.data

import androidx.room.*
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
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao
}
