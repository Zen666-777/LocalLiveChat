package jp.mel.chat.local

import android.app.ActivityManager
import android.content.Context
import jp.mel.chat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object LlamaBridge {
    init { System.loadLibrary("mel_llm") }
    external fun begin()
    external fun cancel()
    external fun load(path: String, context: Int, threads: Int)
    external fun unload()
    external fun count(roles: Array<String>, contents: Array<String>): Int
    external fun generate(roles: Array<String>, contents: Array<String>, temperature: Float, limit: Int): String
}

class LocalChatService(private val context: Context, private val store: LocalModelStore) : ChatService {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "MEL-local-LLM") }
    private val mutex = Mutex()
    private var loaded: String? = null
    override val local = true
    override val contextLimit = 4096
    override val outputLimit = 512
    override val summaryLimit = 512
    override fun responseModel(requested: String) = "local/${store.active()?.sha256?.take(12) ?: "unloaded"}"
    override suspend fun models() = emptyList<RemoteModel>() // No network catalog in offline mode.

    private suspend fun <T> native(action: () -> T): T = mutex.withLock {
        val finished = CompletableDeferred<Unit>()
        LlamaBridge.begin()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { LlamaBridge.cancel() }
                worker.execute {
                    try {
                        if (continuation.isActive) {
                            val value = action()
                            continuation.resume(value)
                        }
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    finally { finished.complete(Unit) }
                }
            }
        } finally {
            // Do not release the mutex until aborted native decoding has finished.
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun ensureLoaded() {
        val model = store.active() ?: error("設定からローカルモデルを取得・選択してください。")
        val file = store.file(model)
        check(file.isFile && file.length() == model.bytes) { "モデルが見つからないか破損しています。再取得してください。" }
        if (loaded == model.sha256) return
        LlamaBridge.unload()
        loaded = null
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > model.bytes + 768L * 1024 * 1024) {
            "モデル用の空きメモリが足りません。他のアプリを閉じるか、小さいGGUFを選んでください。"
        }
        LlamaBridge.load(file.absolutePath, contextLimit, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        loaded = model.sha256
    }

    override suspend fun tokenCount(messages: List<Turn>): Int = native {
        ensureLoaded()
        val turns = mergeSystem(messages)
        LlamaBridge.count(turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray())
    }
    override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String = native {
        ensureLoaded()
        val turns = mergeSystem(messages)
        LlamaBridge.generate(turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray(), temperature, maxTokens.coerceAtMost(768)).trim()
    }
    suspend fun release() = native { LlamaBridge.unload(); loaded = null }
}

/** Models such as Gemma accept one leading system block rather than successive system turns. */
internal fun mergeSystem(messages: List<Turn>): List<Turn> {
    val systems = messages.takeWhile { it.role == "system" }
    return if (systems.size < 2) messages else listOf(Turn("system", systems.joinToString("\n\n") { it.content })) + messages.drop(systems.size)
}
