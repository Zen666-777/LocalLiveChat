package jp.mel.chat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val subtitle: String = "",
    val persona: String = "",
    @ColumnInfo(defaultValue = "''") val details: String = "",
    val greeting: String = "",
    val model: String = "openrouter/auto",
    val contextTokens: Int = 16384,
    val temperature: Float = 0.85f,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "plots", foreignKeys = [ForeignKey(
    entity = CharacterEntity::class, parentColumns = ["id"], childColumns = ["characterId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("characterId"), Index(value = ["id", "characterId"], unique = true)])
data class PlotEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val title: String = "",
    val setup: String = "",
    val greeting: String = "",
    @ColumnInfo(defaultValue = "'roleplay'") val replyStyle: String = "roleplay",
    @ColumnInfo(defaultValue = "0") val replyTokens: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(
    entity = PlotEntity::class, parentColumns = ["id", "characterId"], childColumns = ["plotId", "characterId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["plotId", "id"]), Index(value = ["plotId", "characterId"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val role: String,
    val content: String,
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val plotId: String = characterId
)

@Entity(tableName = "memories", foreignKeys = [ForeignKey(
    entity = PlotEntity::class, parentColumns = ["id", "characterId"], childColumns = ["plotId", "characterId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["plotId", "characterId"])])
data class MemoryEntity(
    val characterId: String,
    val summary: String = "",
    val throughMessageId: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    @PrimaryKey val plotId: String = characterId
)

@Entity(tableName = "lore_entries", foreignKeys = [ForeignKey(
    entity = CharacterEntity::class, parentColumns = ["id"], childColumns = ["characterId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("characterId"), Index(value = ["id", "characterId"], unique = true)])
data class LoreEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val title: String = "",
    val category: String = "人物詳細",
    val content: String = "",
    val keywords: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "plot_lore", primaryKeys = ["plotId", "loreId"], foreignKeys = [
    ForeignKey(entity = PlotEntity::class, parentColumns = ["id", "characterId"], childColumns = ["plotId", "characterId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LoreEntryEntity::class, parentColumns = ["id", "characterId"], childColumns = ["loreId", "characterId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["plotId", "characterId"]), Index(value = ["loreId", "characterId"])])
data class PlotLoreEntity(val plotId: String, val loreId: String, val characterId: String)

/** Curated story facts are independent of the lossy rolling summary. */
@Entity(tableName = "memory_notes", foreignKeys = [ForeignKey(
    entity = PlotEntity::class, parentColumns = ["id", "characterId"], childColumns = ["plotId", "characterId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["plotId", "characterId"])])
data class MemoryNoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val plotId: String,
    val characterId: String,
    val title: String = "",
    val category: String = "出来事",
    val content: String = "",
    val keywords: String = "",
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val sourceMessageId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Encoded Japanese character n-grams; originals remain in messages, never in a second log. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "message_search")
data class MessageSearchEntity(@PrimaryKey @ColumnInfo(name = "rowid") val messageId: Long, val terms: String)

@Dao
interface ChatDao {
    @Update suspend fun updateMessage(message: MessageEntity)
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") fun observeCharacters(): Flow<List<CharacterEntity>>
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") suspend fun characters(): List<CharacterEntity>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun character(id: String): CharacterEntity?
    @Upsert suspend fun saveCharacter(character: CharacterEntity)
    @Query("DELETE FROM characters WHERE id = :id") suspend fun deleteCharacterRow(id: String)
    @Query("DELETE FROM message_search WHERE rowid IN (SELECT id FROM messages WHERE characterId = :id)") suspend fun deleteCharacterSearch(id: String)
    @Transaction suspend fun deleteCharacter(id: String) { deleteCharacterSearch(id); deleteCharacterRow(id) }
    @Query("SELECT * FROM plots WHERE characterId = :id ORDER BY updatedAt DESC, id") fun observePlots(id: String): Flow<List<PlotEntity>>
    @Query("SELECT * FROM plots WHERE characterId = :id ORDER BY updatedAt DESC, id") suspend fun plots(id: String): List<PlotEntity>
    @Query("SELECT * FROM plots WHERE id = :id") suspend fun plot(id: String): PlotEntity?
    @Upsert suspend fun savePlot(plot: PlotEntity)
    @Query("DELETE FROM plots WHERE id = :id") suspend fun deletePlotRow(id: String)
    @Query("DELETE FROM message_search WHERE rowid IN (SELECT id FROM messages WHERE plotId = :id)") suspend fun deletePlotSearch(id: String)
    @Transaction suspend fun deletePlot(id: String) { deletePlotSearch(id); deletePlotRow(id) }
    @Query("SELECT * FROM messages WHERE plotId = :id ORDER BY id") fun observeMessages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE plotId = :id ORDER BY id") suspend fun messages(id: String): List<MessageEntity>
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Query("SELECT * FROM memories WHERE plotId = :id") fun observeMemory(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memories WHERE plotId = :id") suspend fun memory(id: String): MemoryEntity?
    @Upsert suspend fun saveMemory(memory: MemoryEntity)
    @Query("SELECT * FROM lore_entries WHERE characterId = :id ORDER BY category, title, id") fun observeLore(id: String): Flow<List<LoreEntryEntity>>
    @Query("SELECT * FROM lore_entries WHERE id = :id") suspend fun lore(id: String): LoreEntryEntity?
    @Upsert suspend fun saveLore(entry: LoreEntryEntity)
    @Query("DELETE FROM lore_entries WHERE id = :id") suspend fun deleteLore(id: String)
    @Query("SELECT e.* FROM lore_entries e INNER JOIN plot_lore p ON p.loreId = e.id AND p.characterId = e.characterId WHERE p.plotId = :plotId ORDER BY e.category, e.title, e.id")
    suspend fun selectedLore(plotId: String): List<LoreEntryEntity>
    @Query("SELECT loreId FROM plot_lore WHERE plotId = :plotId") fun observeLoreIds(plotId: String): Flow<List<String>>
    @Upsert suspend fun savePlotLore(link: PlotLoreEntity)
    @Query("DELETE FROM plot_lore WHERE plotId = :plotId") suspend fun clearPlotLore(plotId: String)
    @Query("DELETE FROM plot_lore WHERE plotId = :plotId AND loreId = :loreId") suspend fun removePlotLore(plotId: String, loreId: String)
    @Query("SELECT * FROM memory_notes WHERE plotId = :plotId ORDER BY pinned DESC, updatedAt DESC, id") fun observeMemoryNotes(plotId: String): Flow<List<MemoryNoteEntity>>
    @Query("SELECT * FROM memory_notes WHERE plotId = :plotId ORDER BY pinned DESC, updatedAt DESC, id") suspend fun memoryNotes(plotId: String): List<MemoryNoteEntity>
    @Query("SELECT * FROM memory_notes WHERE id = :id") suspend fun memoryNote(id: String): MemoryNoteEntity?
    @Upsert suspend fun saveMemoryNote(note: MemoryNoteEntity)
    @Query("DELETE FROM memory_notes WHERE id = :id") suspend fun deleteMemoryNote(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun indexMessages(entries: List<MessageSearchEntity>)
    @Query("SELECT m.* FROM messages m LEFT JOIN message_search s ON s.rowid = m.id WHERE m.plotId = :plotId AND s.rowid IS NULL ORDER BY m.id LIMIT 100")
    suspend fun unindexedMessages(plotId: String): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE plotId = :plotId AND id <= :through") suspend fun messageCount(plotId: String, through: Long): Int
    @Query("SELECT COUNT(*) FROM message_search JOIN messages m ON m.id = message_search.rowid WHERE message_search MATCH :query AND m.plotId = :plotId AND m.id <= :through")
    suspend fun matchingCount(plotId: String, through: Long, query: String): Int
    @Query("SELECT m.* FROM message_search JOIN messages m ON m.id = message_search.rowid WHERE message_search MATCH :query AND m.plotId = :plotId AND m.id <= :through ORDER BY m.id DESC LIMIT :limit")
    suspend fun searchMessages(plotId: String, through: Long, query: String, limit: Int): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE plotId = :plotId AND id = :messageId") suspend fun message(plotId: String, messageId: Long): MessageEntity?
}

@Database(entities = [CharacterEntity::class, PlotEntity::class, MessageEntity::class, MemoryEntity::class, LoreEntryEntity::class, PlotLoreEntity::class, MemoryNoteEntity::class, MessageSearchEntity::class], version = 3, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plots ADD COLUMN replyStyle TEXT NOT NULL DEFAULT 'roleplay'")
                db.execSQL("ALTER TABLE plots ADD COLUMN replyTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_notes (id TEXT NOT NULL, plotId TEXT NOT NULL, characterId TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, keywords TEXT NOT NULL, pinned INTEGER NOT NULL, enabled INTEGER NOT NULL, sourceMessageId INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(plotId, characterId) REFERENCES plots(id, characterId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_notes_plotId_characterId ON memory_notes(plotId, characterId)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING FTS4(terms TEXT NOT NULL)")
                // Original messages are indexed lazily, in cancellable batches, by MemoryRecall.
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN details TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS plots (id TEXT NOT NULL, characterId TEXT NOT NULL, title TEXT NOT NULL, setup TEXT NOT NULL, greeting TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(characterId) REFERENCES characters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plots_characterId ON plots(characterId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_plots_id_characterId ON plots(id, characterId)")
                db.execSQL("INSERT INTO plots SELECT id, id, 'メインストーリー', '', greeting, updatedAt FROM characters")
                db.execSQL("CREATE TABLE messages_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, characterId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, plotId TEXT NOT NULL, FOREIGN KEY(plotId, characterId) REFERENCES plots(id, characterId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO messages_new SELECT id, characterId, role, content, model, createdAt, characterId FROM messages")
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
                db.execSQL("CREATE INDEX index_messages_plotId_id ON messages(plotId, id)")
                db.execSQL("CREATE INDEX index_messages_plotId_characterId ON messages(plotId, characterId)")
                db.execSQL("CREATE TABLE memories_new (characterId TEXT NOT NULL, summary TEXT NOT NULL, throughMessageId INTEGER NOT NULL, updatedAt INTEGER NOT NULL, plotId TEXT NOT NULL, PRIMARY KEY(plotId), FOREIGN KEY(plotId, characterId) REFERENCES plots(id, characterId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO memories_new SELECT characterId, summary, throughMessageId, updatedAt, characterId FROM memories")
                db.execSQL("DROP TABLE memories")
                db.execSQL("ALTER TABLE memories_new RENAME TO memories")
                db.execSQL("CREATE INDEX index_memories_plotId_characterId ON memories(plotId, characterId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS lore_entries (id TEXT NOT NULL, characterId TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, keywords TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(characterId) REFERENCES characters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lore_entries_characterId ON lore_entries(characterId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lore_entries_id_characterId ON lore_entries(id, characterId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS plot_lore (plotId TEXT NOT NULL, loreId TEXT NOT NULL, characterId TEXT NOT NULL, PRIMARY KEY(plotId, loreId), FOREIGN KEY(plotId, characterId) REFERENCES plots(id, characterId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(loreId, characterId) REFERENCES lore_entries(id, characterId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plot_lore_plotId_characterId ON plot_lore(plotId, characterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plot_lore_loreId_characterId ON plot_lore(loreId, characterId)")
            }
        }
    }
}
