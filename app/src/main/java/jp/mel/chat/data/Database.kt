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

@Dao
interface ChatDao {
    @Update suspend fun updateMessage(message: MessageEntity)
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") fun observeCharacters(): Flow<List<CharacterEntity>>
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") suspend fun characters(): List<CharacterEntity>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun character(id: String): CharacterEntity?
    @Upsert suspend fun saveCharacter(character: CharacterEntity)
    @Query("DELETE FROM characters WHERE id = :id") suspend fun deleteCharacter(id: String)
    @Query("SELECT * FROM plots WHERE characterId = :id ORDER BY updatedAt DESC, id") fun observePlots(id: String): Flow<List<PlotEntity>>
    @Query("SELECT * FROM plots WHERE characterId = :id ORDER BY updatedAt DESC, id") suspend fun plots(id: String): List<PlotEntity>
    @Query("SELECT * FROM plots WHERE id = :id") suspend fun plot(id: String): PlotEntity?
    @Upsert suspend fun savePlot(plot: PlotEntity)
    @Query("DELETE FROM plots WHERE id = :id") suspend fun deletePlot(id: String)
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
}

@Database(entities = [CharacterEntity::class, PlotEntity::class, MessageEntity::class, MemoryEntity::class, LoreEntryEntity::class, PlotLoreEntity::class], version = 2, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    companion object {
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
