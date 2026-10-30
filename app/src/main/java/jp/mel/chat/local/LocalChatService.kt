package jp.mel.chat.local

import android.app.ActivityManager
import android.content.Context
import jp.mel.chat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal fun interface NativeProgress {
    fun update(stage: Int, current: Int, total: Int, text: String)
}
data class LocalProgress(val stage: String = "", val current: Int = 0, val total: Int = 0, val text: String = "")

internal object LlamaBridge {
    init { System.loadLibrary("mel_llm") }
    external fun begin()
    external fun cancel()
    external fun load(path: String, context: Int, threads: Int, progress: NativeProgress)
    external fun unload()
    external fun count(roles: Array<String>, contents: Array<String>): Int
    external fun generate(roles: Array<String>, contents: Array<String>, temperature: Float, limit: Int, progress: NativeProgress): String
}

class LocalChatService(private val context: Context, private val store: LocalModelStore) : ChatService {
    private val runner = NativeTaskRunner({ LlamaBridge.begin() }, { LlamaBridge.cancel() })
    val progress = MutableStateFlow(LocalProgress())
    private var loaded: String? = null
    private val progressLock = Any()
    private var progressGeneration = 0L
    override val local = true
    override val contextLimit = 4096
    override val outputLimit = 256
    override val summaryLimit = 512
    override fun responseModel(requested: String) = "local/${store.active()?.sha256?.take(12) ?: "unloaded"}"
    override suspend fun models() = emptyList<RemoteModel>() // No network catalog in offline mode.

    private fun publish(operation: Long, value: LocalProgress) {
        synchronized(progressLock) { if (progressGeneration == operation) progress.value = value }
    }
    private suspend fun <T> native(action: (Long) -> T): T {
        val operation = synchronized(progressLock) { ++progressGeneration }
        return try { runner.run { action(operation) } }
        finally { synchronized(progressLock) { if (progressGeneration == operation) progressGeneration++ } }
    }
    private fun ensureLoaded(operation: Long) {
        val model = store.active() ?: error("設定からローカルモデルを取得・選択してください。")
        val file = store.file(model)
        check(file.isFile && file.length() == model.bytes) { "モデルが見つからないか破損しています。再取得してください。" }
        if (loaded == model.sha256) return
        LlamaBridge.unload()
        loaded = null
        publish(operation, LocalProgress("空きメモリを確認中"))
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > model.bytes + 768L * 1024 * 1024) {
            "モデル用の空きメモリが足りません（空き約${memory.availMem / (1024 * 1024)}MB、目安約${(model.bytes + 768L * 1024 * 1024) / (1024 * 1024)}MB必要）。他のアプリを閉じるか、小さいモデルを選んでください。"
        }
        publish(operation, LocalProgress("モデルを読み込み中"))
        LlamaBridge.load(file.absolutePath, contextLimit, Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            NativeProgress { _, current, total, _ -> publish(operation, LocalProgress("モデルを読み込み中", current, total)) })
        loaded = model.sha256
    }

    override suspend fun tokenCount(messages: List<Turn>): Int = native { operation ->
        ensureLoaded(operation)
        publish(operation, LocalProgress("会話の長さを確認中"))
        val turns = mergeSystem(messages)
        LlamaBridge.count(turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray())
    }
    override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String = native { operation ->
        ensureLoaded(operation)
        val turns = mergeSystem(messages)
        val summary = messages.firstOrNull()?.content?.startsWith("会話の長期記憶を") == true
        LlamaBridge.generate(turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray(), temperature, maxTokens.coerceAtMost(768),
            NativeProgress { stage, current, total, text ->
                publish(operation, LocalProgress(if (summary) "長期記憶を要約中" else if (stage == 1) "会話を読み取り中" else "返信を生成中",
                    current, total, if (summary) "" else text))
            }).trim().also { check(it.isNotBlank()) { "モデルの応答が空でした。再試行するか、別のモデルを選んでください。" } }
    }
    suspend fun release() = native { operation -> LlamaBridge.unload(); loaded = null; publish(operation, LocalProgress()) }
}

/** Models such as Gemma accept one leading system block rather than successive system turns. */
internal fun mergeSystem(messages: List<Turn>): List<Turn> {
    val systems = messages.takeWhile { it.role == "system" }
    return if (systems.size < 2) messages else listOf(Turn("system", systems.joinToString("\n\n") { it.content })) + messages.drop(systems.size)
}
