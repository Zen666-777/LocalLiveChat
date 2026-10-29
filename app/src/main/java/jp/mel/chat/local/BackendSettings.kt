package jp.mel.chat.local

import android.content.Context
import jp.mel.chat.network.*
import kotlinx.coroutines.flow.MutableStateFlow

class BackendSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mel_backend", Context.MODE_PRIVATE)
    // Upgrades also start offline. Cloud access always needs an explicit selection.
    val offline = MutableStateFlow(prefs.getBoolean("offline", true))
    fun setOffline(value: Boolean) {
        check(prefs.edit().putBoolean("offline", value).commit())
        offline.value = value
    }
}

class RoutedChatService(private val isOffline: () -> Boolean, private val device: ChatService, private val cloud: ChatService) : ChatService {
    private val selected get() = if (isOffline()) device else cloud
    override val local get() = selected.local
    override val contextLimit get() = selected.contextLimit
    override val outputLimit get() = selected.outputLimit
    override val summaryLimit get() = selected.summaryLimit
    override fun responseModel(requested: String) = selected.responseModel(requested)
    override suspend fun tokenCount(messages: List<Turn>) = selected.tokenCount(messages)
    override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int) =
        selected.complete(if (local) "" else key, model, messages, temperature, maxTokens)
    override suspend fun models() = selected.models()
}
