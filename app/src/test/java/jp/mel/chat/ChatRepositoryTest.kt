package jp.mel.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.data.*
import jp.mel.chat.network.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ChatRepositoryTest {
    private lateinit var database: ChatDatabase
    private lateinit var repository: ChatRepository
    private val fake = FakeService()
    private val a = CharacterEntity(id = "a", name = "メル", greeting = "hello")
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        repository = ChatRepository(database, fake)
    }
    @After fun cleanup() { database.close() }
    @Test fun failedSendAndRetryNeverDuplicateUserTurn() = runBlocking {
        repository.save(a)
        fake.fail = true
        assertTrue(runCatching { repository.send("a", "hello again", "key") }.isFailure)
        assertEquals(listOf("assistant", "user"), repository.dao.messages("a").map { it.role })
        fake.fail = false
        repository.send("a", null, "key")
        assertEquals(listOf("assistant", "user", "assistant"), repository.dao.messages("a").map { it.role })
    }
    @Test fun summariesAreIsolatedAndHistoryIsPreserved() = runBlocking {
        repository.save(a)
        repository.save(a.copy(id = "b", name = "Other"))
        repeat(30) { index -> repository.dao.insertMessage(MessageEntity(characterId = "a", role = if (index % 2 == 0) "user" else "assistant", content = "turn $index")) }
        repository.summarize("a", "key")
        assertNotNull(repository.dao.memory("a"))
        assertNull(repository.dao.memory("b"))
        assertEquals(31, repository.dao.messages("a").size)
        val cursor = repository.dao.memory("a")!!.throughMessageId
        assertEquals(8, repository.dao.messages("a").count { it.id > cursor })
    }
    @Test fun failedSummaryDoesNotAdvanceCursor() = runBlocking {
        repository.save(a)
        repeat(28) { repository.dao.insertMessage(MessageEntity(characterId = "a", role = "user", content = "turn")) }
        fake.fail = true
        assertTrue(runCatching { repository.summarize("a", "key") }.isFailure)
        assertNull(repository.dao.memory("a"))
        assertEquals(29, repository.dao.messages("a").size)
    }
    @Test fun changingModelKeepsMemoryAndConversation() = runBlocking {
        repository.save(a)
        repository.editMemory("a", "favorite: coffee")
        repository.save(a.copy(model = "another/model"))
        assertEquals("favorite: coffee", repository.dao.memory("a")!!.summary)
        assertEquals(1, repository.dao.messages("a").size)
        repository.send("a", "hi", "key")
        assertEquals("another/model", fake.lastModel)
        assertTrue(fake.lastTurns.any { it.content.contains("favorite: coffee") })
    }
    @Test fun characterDeletionCascadesToHistoryAndMemory() = runBlocking {
        repository.save(a)
        repository.editMemory("a", "remember")
        repository.delete("a")
        assertNull(repository.dao.character("a"))
        assertTrue(repository.dao.messages("a").isEmpty())
        assertNull(repository.dao.memory("a"))
    }
    @Test fun automaticSummaryFailureStillPreservesSuccessfulReply() = runBlocking {
        repository.save(a)
        repeat(22) { index -> repository.dao.insertMessage(MessageEntity(characterId = "a", role = if (index % 2 == 0) "user" else "assistant", content = "turn")) }
        fake.failSummary = true
        val warning = repository.send("a", "latest", "key")
        assertNotNull(warning)
        assertEquals(25, repository.dao.messages("a").size)
        assertEquals("assistant", repository.dao.messages("a").last().role)
        assertNull(repository.dao.memory("a"))
    }
    @Test fun automaticSummaryRunsAndPreservesAllMessages() = runBlocking {
        repository.save(a)
        repeat(22) { index -> repository.dao.insertMessage(MessageEntity(characterId = "a", role = if (index % 2 == 0) "user" else "assistant", content = "turn")) }
        assertNull(repository.send("a", "latest", "key"))
        assertEquals(25, repository.dao.messages("a").size)
        assertNotNull(repository.dao.memory("a"))
    }
    @Test fun dataSurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-${System.nanoTime()}.db"
        val first = Room.databaseBuilder(context, ChatDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            first.chatDao().saveCharacter(a)
            first.chatDao().insertMessage(MessageEntity(characterId = "a", role = "user", content = "stored"))
        } finally { first.close() }
        val second = Room.databaseBuilder(context, ChatDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("stored", second.chatDao().messages("a").single().content)
            assertEquals("メル", second.chatDao().character("a")!!.name)
        } finally { second.close(); context.deleteDatabase(name) }
    }
    @Test fun localContextCompactsMoreThanEightRecentTurnsWithoutDroppingHistory() = runBlocking {
        fake.localMode = true
        repository.save(a)
        repeat(10) { index -> repository.dao.insertMessage(MessageEntity(characterId = "a", role = if (index % 2 == 0) "user" else "assistant", content = "x".repeat(500))) }
        repository.send("a", "latest", "")
        assertNotNull(repository.dao.memory("a"))
        assertEquals(13, repository.dao.messages("a").size)
        assertEquals("local/test", repository.dao.messages("a").last().model)
        assertTrue(fake.lastTurns.first().content.contains("メル"))
        assertTrue(fake.lastTurns.any { it.content.contains("キャラクターとして") })
    }
    @Test fun oversizedLocalMessageRemainsSavedAndDoesNotInventAReply() = runBlocking {
        fake.localMode = true
        repository.save(a)
        assertTrue(runCatching { repository.send("a", "x".repeat(5000), "") }.isFailure)
        assertEquals(2, repository.dao.messages("a").size)
        assertEquals("user", repository.dao.messages("a").last().role)
        assertNull(repository.dao.memory("a"))
    }
    @Test fun editingOversizedPendingTurnEnablesRetryWithoutDuplicatingHistory() = runBlocking {
        fake.localMode = true
        repository.save(a)
        assertTrue(runCatching { repository.send("a", "x".repeat(5000), "") }.isFailure)
        val before = repository.dao.messages("a").last().id
        repository.editPending("a", "shorter message")
        assertEquals(before, repository.dao.messages("a").last().id)
        repository.send("a", null, "")
        assertEquals(3, repository.dao.messages("a").size)
        assertEquals("shorter message", repository.dao.messages("a")[1].content)
        assertTrue(runCatching { repository.editPending("a", "cannot change answered turn") }.isFailure)
    }
    private class FakeService : ChatService {
        var localMode = false
        override val local get() = localMode
        override val contextLimit get() = if (local) 4096 else null
        override val outputLimit get() = if (local) 256 else 1024
        override val summaryLimit get() = if (local) 512 else 768
        override fun responseModel(requested: String) = if (local) "local/test" else requested
        override suspend fun tokenCount(messages: List<Turn>): Int = if (local) 400 + messages.sumOf { it.content.length } else ConversationPolicy.cost(messages)
        var fail = false
        var failSummary = false
        var lastModel = ""
        var lastTurns = emptyList<Turn>()
        override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
            if (local) check(tokenCount(messages) + maxTokens + 8 <= 4096) { "Native context overflow" }
            if (fail || (failSummary && maxTokens == 768)) throw ApiException("offline")
            lastModel = model; lastTurns = messages
            return "saved reply or summary"
        }
        override suspend fun models() = emptyList<RemoteModel>()
    }
}
