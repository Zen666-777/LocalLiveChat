package jp.mel.chat

import jp.mel.chat.data.*
import jp.mel.chat.network.Turn
import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    private val character = CharacterEntity(id = "a", name = "メル")
    @Test fun cursorExcludesOnlySummarizedHistory() {
        val history = (1L..30L).map { MessageEntity(it, "a", if (it % 2L == 0L) "assistant" else "user", "message-$it") }
        val result = ConversationPolicy.prompt(character, MemoryEntity("a", "remember me", 20), history)
        assertEquals(12, result.size)
        assertTrue(result[1].content.contains("remember me"))
        assertEquals("message-21", result[2].content)
        assertEquals("message-30", result.last().content)
    }
    @Test fun summaryRetainsEightRecentMessages() {
        val history = (1L..30L).map { MessageEntity(it, "a", "user", "hello") }
        val batch = ConversationPolicy.summaryBatch(character, null, history)
        assertEquals(22, batch.size)
        assertEquals(22L, batch.last().id)
    }
    @Test fun summaryBatchNeverSkipsOversizedFirstMessage() {
        val history = (1L..20L).map { MessageEntity(it, "a", "user", if (it == 1L) "あ".repeat(5000) else "hi") }
        assertTrue(ConversationPolicy.summaryBatch(character.copy(contextTokens = 4096), null, history).isEmpty())
    }
    @Test fun byteBudgetAccountsForJapanese() {
        assertTrue(ConversationPolicy.cost(listOf(Turn("user", "あああ"))) > ConversationPolicy.cost(listOf(Turn("user", "aaa"))))
    }
    @Test fun personaMustFitChosenContext() {
        assertThrows(IllegalArgumentException::class.java) { ConversationPolicy.validate(character.copy(contextTokens = 4096, persona = "あ".repeat(2000))) }
    }
    @Test fun invalidModelIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConversationPolicy.validate(character.copy(model = "bad id")) }
    }
}
