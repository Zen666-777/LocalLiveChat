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
class StoryRepositoryTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    private val model = Probe()
    private val character = CharacterEntity(id = "mel", name = "メル", details = "silver hair", greeting = "welcome")
    private val school = PlotEntity(id = "school", characterId = "mel", title = "学園", setup = "school setting", greeting = "new story")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java).allowMainThreadQueries().build()
        repo = ChatRepository(db, model)
        repo.save(character)
        repo.savePlot(school, emptySet())
    }
    @After fun cleanup() { db.close() }
    @Test fun twoPlotsOfTheSameCharacterHaveSeparateHistoryAndMemory() = runBlocking {
        repo.editMemory("mel", "cafe promise")
        repo.editMemory("school", "castle event")
        repo.send("school", "hi", "")
        assertTrue(model.last.any { it.content.contains("castle event") })
        assertFalse(model.last.any { it.content.contains("cafe promise") })
        assertTrue(model.last.any { it.content.contains("school setting") })
        assertTrue(model.last.any { it.content.contains("silver hair") })
        assertEquals(1, repo.dao.messages("mel").size)
        assertEquals(3, repo.dao.messages("school").size)
        repo.send("mel", "other scene", "")
        assertTrue(model.last.any { it.content.contains("cafe promise") })
        assertFalse(model.last.any { it.content.contains("castle event") || it.content.contains("school setting") })
        assertEquals("mel", repo.dao.memory("school")!!.characterId)
        assertEquals("school", repo.dao.memory("school")!!.plotId)
    }
    @Test fun summarizingOnePlotDoesNotReadOrOverwriteTheOtherPlot() = runBlocking {
        repo.editMemory("mel", "keep this memory")
        repeat(28) { index ->
            repo.dao.insertMessage(MessageEntity(characterId = "mel", plotId = "mel", role = "user", content = "cafe-$index"))
            repo.dao.insertMessage(MessageEntity(characterId = "mel", plotId = "school", role = "user", content = "school-$index"))
        }
        repo.summarize("school", "")
        assertEquals("keep this memory", repo.dao.memory("mel")!!.summary)
        assertEquals(0L, repo.dao.memory("mel")!!.throughMessageId)
        assertFalse(model.last.any { it.content.contains("cafe-") })
        assertTrue(model.last.any { it.content.contains("school-") })
        val cursor = repo.dao.memory("school")!!.throughMessageId
        assertEquals(8, repo.dao.messages("school").count { it.id > cursor })
        assertEquals(29, repo.dao.messages("school").size)
        assertEquals(29, repo.dao.messages("mel").size)
    }
    @Test fun loreOnlyAppliesToSelectedPlotsAndMatchingKeywords() = runBlocking {
        val profile = LoreEntryEntity(id = "profile", characterId = "mel", title = "詳細", content = "blue scarf")
        val location = LoreEntryEntity(id = "tower", characterId = "mel", title = "塔", category = "場所", content = "ancient tower secret", keywords = "tower,塔")
        repo.saveLore(profile, "mel")
        repo.saveLore(location, "mel")
        repo.send("mel", "hello", "")
        assertTrue(model.last.any { it.content.contains("blue scarf") })
        assertFalse(model.last.any { it.content.contains("ancient tower secret") })
        repo.send("mel", "TOWERへ行こう", "")
        assertTrue(model.last.any { it.content.contains("ancient tower secret") })
        repo.send("school", "tower", "")
        assertFalse(model.last.any { it.content.contains("blue scarf") || it.content.contains("ancient tower secret") })
        repo.setLoreEnabled("school", "profile", true)
        repo.saveLore(profile.copy(content = "red scarf"))
        repo.send("school", "next", "")
        assertTrue(model.last.any { it.content.contains("red scarf") })
    }
    @Test fun editingSettingsPreservesExistingMessagesAndMemory() = runBlocking {
        repo.editMemory("school", "old memory")
        val before = repo.dao.messages("school")
        repo.savePlot(school.copy(title = "new title", greeting = "should not reinsert"), emptySet())
        repo.save(character.copy(details = "new profile"))
        assertEquals(before, repo.dao.messages("school"))
        assertEquals("old memory", repo.dao.memory("school")!!.summary)
    }
    @Test fun crossCharacterLinksAreRejectedAndPlotEditsRollBackAtomically() = runBlocking {
        repo.save(CharacterEntity(id = "other", name = "Other"))
        val own = LoreEntryEntity(id = "own", characterId = "mel", title = "own", content = "own facts")
        val foreign = LoreEntryEntity(id = "foreign", characterId = "other", title = "foreign", content = "foreign facts")
        repo.saveLore(own, "school")
        repo.saveLore(foreign)
        assertTrue(runCatching { repo.savePlot(school.copy(setup = "must roll back"), setOf("foreign")) }.isFailure)
        assertEquals(school.setup, repo.dao.plot("school")!!.setup)
        assertEquals(listOf("own"), repo.dao.selectedLore("school").map { it.id })
        assertTrue(runCatching { repo.setLoreEnabled("school", "foreign", true) }.isFailure)
        assertTrue(runCatching { repo.dao.savePlotLore(PlotLoreEntity("school", "foreign", "mel")) }.isFailure)
        assertTrue(runCatching { repo.dao.insertMessage(MessageEntity(characterId = "other", plotId = "school", role = "user", content = "wrong scope")) }.isFailure)
    }
    @Test fun deletingOnePlotOnlyRemovesItsOwnConversationAndMemory() = runBlocking {
        val lore = LoreEntryEntity(id = "shared", characterId = "mel", title = "shared", content = "shared facts")
        repo.saveLore(lore, "mel")
        repo.setLoreEnabled("school", lore.id, true)
        repo.editMemory("mel", "cafe")
        repo.editMemory("school", "school")
        repo.deletePlot("mel")
        assertNull(repo.dao.plot("mel"))
        assertNull(repo.dao.memory("mel"))
        assertTrue(repo.dao.messages("mel").isEmpty())
        assertEquals("school", repo.dao.memory("school")!!.summary)
        assertEquals(1, repo.dao.messages("school").size)
        assertEquals(listOf("shared"), repo.dao.selectedLore("school").map { it.id })
        assertNotNull(repo.dao.lore("shared"))
        assertTrue(runCatching { repo.deletePlot("school") }.isFailure)
        repo.delete("mel")
        assertNull(repo.dao.plot("school"))
        assertNull(repo.dao.memory("school"))
        assertNull(repo.dao.lore("shared"))
    }
    @Test fun oversizedLoreIsNotSilentlyDroppedAndCanBeDisabledForRetry() = runBlocking {
        model.onDevice = true
        repo.saveLore(LoreEntryEntity(id = "large", characterId = "mel", title = "large", content = "あ".repeat(2000)), "mel")
        assertTrue(runCatching { repo.send("mel", "hello", "") }.isFailure)
        assertEquals(0, model.calls)
        assertEquals(2, repo.dao.messages("mel").size)
        repo.setLoreEnabled("mel", "large", false)
        repo.send("mel", null, "")
        assertEquals(3, repo.dao.messages("mel").size)
        assertEquals(1, model.calls)
    }
    private class Probe : ChatService {
        var onDevice = false
        override val local get() = onDevice
        override val contextLimit get() = if (onDevice) 4096 else null
        var last = emptyList<Turn>()
        var calls = 0
        override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
            calls++; last = messages
            return "saved response"
        }
        override suspend fun models() = emptyList<RemoteModel>()
    }
}
