package jp.mel.chat.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Turn(val role: String, val content: String)
data class RemoteModel(val id: String, val name: String, val contextTokens: Int, val promptPrice: String, val completionPrice: String)

interface ChatService {
    val local: Boolean get() = false
    val contextLimit: Int? get() = null
    val outputLimit: Int get() = 1024
    val summaryLimit: Int get() = 768
    fun responseModel(requested: String): String = requested
    suspend fun tokenCount(messages: List<Turn>): Int = jp.mel.chat.data.ConversationPolicy.cost(messages)
    suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String
    suspend fun models(): List<RemoteModel>
}

class ApiException(message: String) : IOException(message)

class OpenRouterClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
    private val baseUrl: String = "https://openrouter.ai/api/v1/"
) : ChatService {
    override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
        val body = JSONObject().put("model", model).put("stream", false)
            .put("temperature", temperature.toDouble()).put("max_tokens", maxTokens)
            .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
        val request = Request.Builder().url(baseUrl + "chat/completions")
            .header("Authorization", "Bearer $key").header("X-Title", "MEL Chat")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val json = execute(request)
        if (json.has("error")) throw ApiException("モデルがエラーを返しました。別のモデルで再試行してください。")
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw ApiException("モデルの応答が空です。別のモデルで再試行してください。")
    }

    override suspend fun models(): List<RemoteModel> {
        val array = execute(Request.Builder().url(baseUrl + "models").build()).optJSONArray("data")
            ?: throw ApiException("モデル一覧を読み取れませんでした。")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val architecture = item.optJSONObject("architecture")
            val outputs = architecture?.optJSONArray("output_modalities")
            val inputs = architecture?.optJSONArray("input_modalities")
            fun includesText(values: JSONArray?) = values == null || (0 until values.length()).any { values.optString(it) == "text" }
            if (!includesText(outputs) || !includesText(inputs)) return@mapNotNull null
            RemoteModel(item.getString("id"), item.optString("name", item.getString("id")),
                item.optInt("context_length", 16384).let { if (it > 0) it.coerceAtMost(2000000) else 16384 },
                item.optJSONObject("pricing")?.optString("prompt").orEmpty(),
                item.optJSONObject("pricing")?.optString("completion").orEmpty())
        }.sortedBy { it.name.lowercase() }
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ApiException("接続できませんでした。通信状態を確認して再試行してください。"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw ApiException(when (it.code) {
                            401, 403 -> "APIキーが無効、またはこのモデルへのアクセスが拒否されました。"
                            402 -> "OpenRouterのクレジットが不足しています。"
                            429 -> "利用制限に達しました。少し待って再試行してください。"
                            400, 404 -> "モデルID・コンテキスト上限・モデルの対応条件を確認してください。"
                            else -> "OpenRouterエラー（HTTP ${it.code}）。再試行してください。"
                        })
                        val value = JSONObject(it.body?.string() ?: throw ApiException("応答がありません。"))
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is ApiException) e else ApiException("応答を読み取れませんでした。再試行してください。"))
                }
            }
        })
    }
}
