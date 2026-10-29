package jp.mel.chat

import jp.mel.chat.local.*
import jp.mel.chat.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalRoutingTest {
    private class Probe(override val local: Boolean) : ChatService {
        var calls = 0
        var key = ""
        var fail = false
        override val contextLimit get() = if (local) 4096 else null
        override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
            calls++; this.key = key
            check(!fail) { "model unavailable" }
            return if (local) "device" else "cloud"
        }
        override suspend fun models(): List<RemoteModel> { calls++; return emptyList() }
    }
    @Test fun offlineChatAndSummaryNeverCallCloudOrPassAnApiKey() = runBlocking {
        val device = Probe(true)
        val cloud = Probe(false)
        val routed = RoutedChatService({ true }, device, cloud)
        repeat(2) { routed.complete("secret-key", "remote-id", listOf(Turn("user", "private")), .2f, 512) }
        assertEquals(2, device.calls)
        assertEquals("", device.key)
        assertEquals(0, cloud.calls)
        assertEquals(4096, routed.contextLimit)
    }
    @Test fun localFailureDoesNotFallBackToCloud() = runBlocking {
        val device = Probe(true).apply { fail = true }
        val cloud = Probe(false)
        val routed = RoutedChatService({ true }, device, cloud)
        assertTrue(runCatching { routed.complete("secret", "id", emptyList(), 1f, 512) }.isFailure)
        assertEquals(0, cloud.calls)
    }
    @Test fun cloudRequiresExplicitBackendSelection() = runBlocking {
        var offline = true
        val device = Probe(true)
        val cloud = Probe(false)
        val routed = RoutedChatService({ offline }, device, cloud)
        offline = false
        assertEquals("cloud", routed.complete("key", "id", emptyList(), 1f, 512))
        assertEquals("key", cloud.key)
        assertEquals(0, device.calls)
    }
    @Test fun systemMemoryIsMergedWithoutFlatteningTheDialogue() {
        val result = mergeSystem(listOf(Turn("system", "persona"), Turn("system", "memory"), Turn("user", "hi"), Turn("assistant", "hello"), Turn("user", "again")))
        assertEquals(listOf("system", "user", "assistant", "user"), result.map { it.role })
        assertEquals("persona\n\nmemory", result.first().content)
    }
}
