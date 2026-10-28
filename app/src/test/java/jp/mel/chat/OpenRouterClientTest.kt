package jp.mel.chat

import jp.mel.chat.network.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenRouterClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OpenRouterClient
    @Before fun setup() { server = MockWebServer(); server.start(); api = OpenRouterClient(baseUrl = server.url("/api/v1/").toString()) }
    @After fun cleanup() { server.shutdown() }
    @Test fun sendsChosenModelAndAuthentication() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"こんにちは"}}]}"""))
        assertEquals("こんにちは", api.complete("test-key", "my/model", listOf(Turn("user", "hello")), 0.8f, 1024))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("my/model", body.getString("model"))
        assertEquals("hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(body.getBoolean("stream"))
    }
    @Test fun errorsDoNotEchoProviderPayloadOrKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-provider-details"))
        val error = runCatching { api.complete("secret-key", "m", emptyList(), 0.5f, 10) }.exceptionOrNull()!!
        assertTrue(error is ApiException)
        assertFalse(error.message!!.contains("secret"))
        assertFalse(error.message!!.contains("sensitive"))
    }
    @Test fun emptyResponseIsNotStoredAsAssistantContent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        assertTrue(runCatching { api.complete("key", "m", emptyList(), 0.5f, 10) }.exceptionOrNull() is ApiException)
    }
    @Test fun catalogFiltersModelsWithoutTextOutput() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"text/model","name":"Text","context_length":32000,"architecture":{"output_modalities":["text"]},"pricing":{"prompt":"0.000001","completion":"0.000002"}},{"id":"image/model","architecture":{"output_modalities":["image"]}}]}"""))
        val models = api.models()
        assertEquals(1, models.size)
        assertEquals("text/model", models.single().id)
        assertEquals(32000, models.single().contextTokens)
    }
    @Test fun cancellationStopsWaitingForNetwork() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val job = launch { api.complete("key", "m", emptyList(), 0.5f, 10) }
        delay(100)
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
}
