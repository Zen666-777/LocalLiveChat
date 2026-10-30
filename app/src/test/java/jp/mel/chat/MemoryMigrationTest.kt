package jp.mel.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MemoryMigrationTest {
    @Test fun realVersionTwoUpgradePreservesPlotsLoreAndSearchableOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "memory-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("jp.mel.chat.data.ChatDatabase/2.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO characters VALUES ('mel', 'メル', '', '静かな人', '詳細', '', 'old/model', 16384, 0.8, 100)")
            old.execSQL("INSERT INTO plots VALUES ('story', 'mel', '灯台', '雨の港', '', 101)")
            old.execSQL("INSERT INTO messages VALUES (7, 'mel', 'user', '紺色の傘を灯台で返す約束', '', 102, 'story')")
            old.execSQL("INSERT INTO memories VALUES ('mel', '二人は港にいる', 7, 103, 'story')")
            old.execSQL("INSERT INTO lore_entries VALUES ('lore', 'mel', '港', '場所', '古い港', '', 104)")
            old.execSQL("INSERT INTO plot_lore VALUES ('story', 'lore', 'mel')")
            old.version = 2
        }
        fun open() = Room.databaseBuilder(context, ChatDatabase::class.java, name).addMigrations(ChatDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        val db = open()
        try {
            val dao = db.chatDao()
            assertEquals("roleplay", dao.plot("story")!!.replyStyle)
            assertEquals(0, dao.plot("story")!!.replyTokens)
            assertEquals(7L, dao.memory("story")!!.throughMessageId)
            assertEquals("古い港", dao.selectedLore("story").single().content)
            assertEquals(7L, MemoryRecall(db).find("story", "傘を返す場所").first().message.id)
            dao.saveMemoryNote(MemoryNoteEntity(id = "kept", plotId = "story", characterId = "mel", title = "約束", content = "傘を返す", sourceMessageId = 7))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
        val reopened = open()
        try {
            assertEquals(7L, reopened.chatDao().memoryNote("kept")!!.sourceMessageId)
            assertEquals("紺色の傘を灯台で返す約束", MemoryRecall(reopened).find("story", "傘").single().message.content)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
