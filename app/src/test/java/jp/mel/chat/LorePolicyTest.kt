package jp.mel.chat

import jp.mel.chat.data.*
import org.junit.Assert.*
import org.junit.Test

class LorePolicyTest {
    private val always = LoreEntryEntity(id = "always", characterId = "mel", title = "profile", content = "facts")
    private val keyed = LoreEntryEntity(id = "keyed", characterId = "mel", title = "tower", content = "location", keywords = "TOWER、塔\ncastle")
    @Test fun keywordsMatchRecentConversationOrPlotSetupAndAlwaysEntriesStayActive() {
        val plot = PlotEntity(characterId = "mel", title = "test", setup = "castle courtyard")
        assertEquals(listOf(always, keyed), ConversationPolicy.activeLore(listOf(always, keyed), plot, emptyList()))
        assertEquals(listOf(always, keyed), ConversationPolicy.activeLore(listOf(always, keyed), null, listOf(MessageEntity(characterId = "mel", role = "user", content = "tower"))))
    }
    @Test fun anOldKeywordDoesNotActivateUnrelatedLoreForever() {
        val messages = listOf(MessageEntity(characterId = "mel", role = "user", content = "塔")) +
            List(6) { MessageEntity(characterId = "mel", role = "assistant", content = "at home") }
        assertEquals(listOf(always), ConversationPolicy.activeLore(listOf(always, keyed), null, messages))
    }
}
