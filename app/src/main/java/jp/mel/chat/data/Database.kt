package jp.mel.chat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val subtitle: String = "",
    val persona: String = "",
    val greeting: String = "",
    val model: String = "openrouter/auto",
    val contextTokens: Int = 16384,
    val temperature: Float = 0.85f,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(
    entity = CharacterEntity::class, parentColumns = ["id"], childColumns = ["characterId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["characterId", "id"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val role: String,
    val content: String,
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories", foreignKeys = [ForeignKey(
    entity = CharacterEntity::class, parentColumns = ["id"], childColumns = ["characterId"],
    onDelete = ForeignKey.CASCADE
)])
data class MemoryEntity(
    @PrimaryKey val characterId: String,
    val summary: String = "",
    val throughMessageId: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") fun observeCharacters(): Flow<List<CharacterEntity>>
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") suspend fun characters(): List<CharacterEntity>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun character(id: String): CharacterEntity?
    @Upsert suspend fun saveCharacter(character: CharacterEntity)
    @Query("DELETE FROM characters WHERE id = :id") suspend fun deleteCharacter(id: String)
    @Query("SELECT * FROM messages WHERE characterId = :id ORDER BY id") fun observeMessages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE characterId = :id ORDER BY id") suspend fun messages(id: String): List<MessageEntity>
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Query("SELECT * FROM memories WHERE characterId = :id") fun observeMemory(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memories WHERE characterId = :id") suspend fun memory(id: String): MemoryEntity?
    @Upsert suspend fun saveMemory(memory: MemoryEntity)
}

@Database(entities = [CharacterEntity::class, MessageEntity::class, MemoryEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() { abstract fun chatDao(): ChatDao }
