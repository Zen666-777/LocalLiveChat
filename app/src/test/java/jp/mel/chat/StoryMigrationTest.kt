package jp.mel.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.data.*
import jp.mel.chat.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StoryMigrationTest {
    @Test fun realVersionOneDatabaseUpgradesWithoutLosingHistoryOrSummaryCursors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("jp.mel.chat.data.ChatDatabase/1.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            for (id in listOf("legacy", "second")) old.execSQL("INSERT INTO characters VALUES (?, ?, '', 'old persona', 'welcome', 'old/model', 16384, 0.8, 100)", arrayOf(id, id))
            old.execSQL("INSERT INTO messages VALUES (1, 'legacy', 'assistant', 'welcome', '', 100)")
            old.execSQL("INSERT INTO messages VALUES (2, 'legacy', 'user', 'old pending message', '', 101)")
            old.execSQL("INSERT INTO messages VALUES (10, 'second', 'assistant', 'other story', '', 102)")
            old.execSQL("INSERT INTO memories VALUES ('legacy', 'old important memory', 1, 103)")
            old.execSQL("INSERT INTO memories VALUES ('second', 'separate memory', 10, 104)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).addMigrations(ChatDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val dao = db.chatDao()
            assertEquals("", dao.character("legacy")!!.details)
            assertEquals("old/model", dao.character("legacy")!!.model)
            assertEquals("メインストーリー", dao.plot("legacy")!!.title)
            assertEquals(listOf(1L, 2L), dao.messages("legacy").map { it.id })
            assertEquals("old important memory", dao.memory("legacy")!!.summary)
            assertEquals(1L, dao.memory("legacy")!!.throughMessageId)
            assertEquals("separate memory", dao.memory("second")!!.summary)
            assertEquals("legacy", dao.memory("legacy")!!.plotId)
            val service = object : ChatService {
                override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
                    assertTrue(messages.any { it.content.contains("old important memory") })
                    assertFalse(messages.any { it.content.contains("separate memory") })
                    return "new response"
                }
                override suspend fun models() = emptyList<RemoteModel>()
            }
            ChatRepository(db, service).send("legacy", null, "")
            assertEquals(3, dao.messages("legacy").size)
            assertTrue(dao.messages("legacy").last().id > 10)
            assertEquals(1, dao.messages("second").size)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
