package jp.mel.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.mel.chat.MelApplication
import jp.mel.chat.data.*
import jp.mel.chat.network.RemoteModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MelApplication
    private val repository = app.repository
    private val preferences = application.getSharedPreferences("mel_ui", Application.MODE_PRIVATE)
    val characters = repository.dao.observeCharacters().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selected = MutableStateFlow(preferences.getString("selected", null))
    val messages = selected.flatMapLatest { if (it == null) flowOf(emptyList()) else repository.dao.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memory = selected.flatMapLatest { if (it == null) flowOf(null) else repository.dao.observeMemory(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val busy = MutableStateFlow(false)
    val loadingModels = MutableStateFlow(false)
    val models = MutableStateFlow<List<RemoteModel>>(emptyList())
    val notice = MutableStateFlow<String?>(null)
    val hasKey = MutableStateFlow(false)
    private var work: Job? = null

    init {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) {
                if (!preferences.getBoolean("initialized", false)) {
                    repository.initialize()
                    check(preferences.edit().putBoolean("initialized", true).commit())
                }
            } }
            catch (_: Exception) { notice.value = "データベースを開けませんでした。アプリを再起動してください。" }
            characters.collect { list ->
                if (list.none { it.id == selected.value }) select(list.firstOrNull()?.id)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { hasKey.value = app.keys.load() != null }
            catch (e: Exception) { notice.value = e.message }
        }
    }

    fun select(id: String?) {
        selected.value = id
        preferences.edit().putString("selected", id).apply()
    }

    private fun perform(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { notice.value = "通信を停止しました。履歴から再試行できます。"; throw e }
            catch (e: Exception) { notice.value = e.message ?: "処理に失敗しました。再試行してください。" }
            finally { busy.value = false }
        }
    }

    private fun requireKey(): String = app.keys.load() ?: error("設定でOpenRouter APIキーを登録してください。")
    fun send(id: String, text: String?, onAccepted: () -> Unit = {}) {
        if (busy.value) return
        perform {
            val key = requireKey()
            val warning = repository.send(id, text, key) { withContext(Dispatchers.Main) { onAccepted() } }
            if (warning != null) notice.value = warning
        }
    }
    fun cancel() { work?.cancel() }
    fun save(character: CharacterEntity, done: () -> Unit) = perform {
        repository.save(character)
        withContext(Dispatchers.Main) { select(character.id); done() }
    }
    fun delete(id: String, done: () -> Unit) = perform {
        repository.delete(id)
        withContext(Dispatchers.Main) { done() }
    }
    fun saveMemory(id: String, text: String, done: () -> Unit) = perform {
        repository.editMemory(id, text)
        withContext(Dispatchers.Main) { done() }
    }
    fun summarize(id: String) = perform {
        repository.summarize(id, requireKey())
        notice.value = "記憶を更新しました。直近8件は会話の文脈として保持します。"
    }
    fun saveKey(value: String, done: () -> Unit) = perform {
        app.keys.save(value)
        hasKey.value = true
        withContext(Dispatchers.Main) { done() }
        notice.value = "APIキーを暗号化して保存しました。"
    }
    fun clearKey() = perform { app.keys.clear(); hasKey.value = false; notice.value = "APIキーを削除しました。" }
    fun refreshModels() {
        if (loadingModels.value) return
        loadingModels.value = true
        viewModelScope.launch {
            try { models.value = app.api.models() }
            catch (e: Exception) { notice.value = e.message ?: "モデル一覧を取得できませんでした。IDを直接入力できます。" }
            finally { loadingModels.value = false }
        }
    }
}
