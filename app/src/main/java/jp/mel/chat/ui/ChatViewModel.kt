package jp.mel.chat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.mel.chat.MelApplication
import jp.mel.chat.data.*
import jp.mel.chat.network.RemoteModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val offline = app.backend.offline
    val deviceModels = app.deviceModels.state
    private val replies = ReplyTracker(preferences)
    val replyState = replies.state
    val localProgress = app.device.progress
    private var work: Job? = null
    private val operations = Mutex()

    init {
        viewModelScope.launch { localProgress.collect { replies.stage(it.stage) } }
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
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!busy.value) {
                    try { operations.withLock { if (!busy.value) app.deviceModels.pollDownload() } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice.value = "モデルの取得状況を確認できません。設定から再試行してください。" }
                }
                delay(1500)
            }
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
            try { withContext(Dispatchers.IO) { operations.withLock { action() } } }
            catch (e: CancellationException) { notice.value = "処理を停止しました。保存済みのメッセージは再試行できます。"; throw e }
            catch (e: Exception) { notice.value = e.message ?: "処理に失敗しました。再試行してください。" }
            finally { busy.value = false }
        }
    }

    private fun requireKey(): String {
        if (offline.value) {
            check(app.deviceModels.active() != null) { "設定からローカルモデルを取得・選択してください。" }
            return ""
        }
        return app.keys.load() ?: error("設定でOpenRouter APIキーを登録してください。")
    }
    fun setOffline(value: Boolean) = perform {
        app.device.release()
        app.backend.setOffline(value)
        models.value = emptyList()
    }
    fun downloadModel(allowMobile: Boolean = false) = perform { app.deviceModels.download(allowMobile) }
    fun restartModelWithMobile() = perform { app.deviceModels.restartWithMobile() }
    fun cancelModelDownload() = perform { app.deviceModels.cancelDownload() }
    fun importModel(uri: Uri) = perform {
        app.device.release()
        app.deviceModels.import(uri)
        notice.value = "モデルを保存しました。端末内で会話・要約できます。"
    }
    fun selectModel(id: String) = perform { app.device.release(); app.deviceModels.select(id) }
    fun deleteModel(id: String) = perform { app.device.release(); app.deviceModels.delete(id) }
    fun releaseModel() = perform { app.device.release(); notice.value = "モデルのメモリを解放しました。次の会話で読み込みます。" }
    fun send(id: String, text: String?, onAccepted: () -> Unit = {}) {
        if (busy.value) return
        perform {
            localProgress.value = jp.mel.chat.local.LocalProgress()
            replies.start(id)
            try {
                val key = requireKey()
                val warning = repository.send(id, text, key) { withContext(Dispatchers.Main) { onAccepted() } }
                replies.finish()
                if (warning != null) notice.value = warning
            } catch (e: CancellationException) {
                replies.finish("返信の生成を停止しました。送信済みの投稿は再試行できます。")
                throw e
            } catch (e: Exception) {
                replies.finish(e.message ?: "返信できませんでした。再試行してください。")
                throw e
            }
        }
    }
    fun cancel() { work?.cancel() }
    fun editPending(id: String, text: String, done: () -> Unit) = perform {
        repository.editPending(id, text)
        withContext(Dispatchers.Main) { done() }
    }
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
        notice.value = "記憶を更新しました。直近の会話と元の履歴も保持しています。"
    }
    fun saveKey(value: String, done: () -> Unit) = perform {
        app.keys.save(value)
        hasKey.value = true
        withContext(Dispatchers.Main) { done() }
        notice.value = "APIキーを暗号化して保存しました。"
    }
    fun clearKey() = perform { app.keys.clear(); hasKey.value = false; notice.value = "APIキーを削除しました。" }
    fun refreshModels() {
        if (offline.value || loadingModels.value) return
        loadingModels.value = true
        viewModelScope.launch {
            try { models.value = app.api.models() }
            catch (e: Exception) { notice.value = e.message ?: "モデル一覧を取得できませんでした。IDを直接入力できます。" }
            finally { loadingModels.value = false }
        }
    }
}
