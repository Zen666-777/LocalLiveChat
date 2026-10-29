package jp.mel.chat.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import jp.mel.chat.network.ApiException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

data class DeviceModel(val id: String, val name: String, val sha256: String, val bytes: Long)
data class ModelState(val models: List<DeviceModel> = emptyList(), val activeId: String? = null,
    val downloadId: Long = -1, val progress: Float = 0f, val status: String = "", val error: String? = null) {
    val active get() = models.find { it.id == activeId }
    val downloading get() = downloadId >= 0
}

/** Public model weights only. Conversation text never passes through this downloader. */
class LocalModelStore(private val context: Context) {
    companion object {
        const val NAME = "Qwen3 4B Instruct · Q4_K_M"
        const val SIZE = 2497281120L
        const val SHA = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"
        const val URL = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/18727206c51467496bfba014368bd0a30e97f411/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        private const val MAX_SIZE = 6L * 1024 * 1024 * 1024
    }
    private val prefs = context.getSharedPreferences("mel_models", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val directory get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("モデル保存用のストレージを開けません。"), "models").apply { mkdirs() }
    val state = MutableStateFlow(readState())
    private fun readState(): ModelState {
        val list = runCatching {
            val array = JSONArray(prefs.getString("models", "[]"))
            (0 until array.length()).map { array.getJSONObject(it).let { item ->
                DeviceModel(item.getString("id"), item.getString("name"), item.getString("sha"), item.getLong("bytes"))
            } }.filter { it.id.matches(Regex("[a-zA-Z0-9-]+")) }
        }.getOrDefault(emptyList())
        return ModelState(list, prefs.getString("active", null), prefs.getLong("download", -1))
    }
    private fun save(value: ModelState) {
        val array = JSONArray().apply { value.models.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("sha", it.sha256).put("bytes", it.bytes))
        } }
        check(prefs.edit().putString("models", array.toString()).putString("active", value.activeId)
            .putLong("download", value.downloadId).commit()) { "モデル設定を保存できません。" }
        state.value = value
    }
    fun active() = state.value.active
    fun file(model: DeviceModel) = File(directory, "${model.id}.gguf")
    fun select(id: String) { require(state.value.models.any { it.id == id }); save(state.value.copy(activeId = id)) }

    fun download() {
        check(!state.value.downloading) { "モデルを取得中です。" }
        state.value.models.find { it.sha256 == SHA }?.let { select(it.id); return }
        check(directory.usableSpace > SIZE + 256L * 1024 * 1024) { "空き容量が足りません。約2.8GB以上を確保してください。" }
        val destination = File(directory, "recommended.partial")
        check(!destination.exists() || destination.delete())
        val request = DownloadManager.Request(Uri.parse(URL)).setTitle("MEL Chat ローカルモデル")
            .setDescription("約2.5GB・Wi-Fiで取得。会話データは送信しません。")
            .setAllowedOverMetered(false).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
        val id = manager.enqueue(request)
        save(state.value.copy(downloadId = id, progress = 0f, status = "Wi-Fiで取得を開始しました", error = null))
    }
    fun cancelDownload() {
        if (state.value.downloading) manager.remove(state.value.downloadId)
        File(directory, "recommended.partial").delete()
        save(state.value.copy(downloadId = -1, status = "取得を中止しました", progress = 0f, error = null))
    }
    suspend fun pollDownload() {
        val id = state.value.downloadId
        if (id < 0) return
        val result = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
        if (result == null || result.first == DownloadManager.STATUS_FAILED) {
            save(state.value.copy(downloadId = -1, status = "", error = "取得できませんでした。Wi-Fiと空き容量を確認して再試行してください。"))
            return
        }
        if (result.first != DownloadManager.STATUS_SUCCESSFUL) {
            state.value = state.value.copy(progress = (result.second.toFloat() / SIZE).coerceIn(0f, 1f),
                status = if (result.first == DownloadManager.STATUS_PAUSED) "Wi-Fi接続・ダウンロードの再開を待っています" else "モデルを取得中…")
            return
        }
        state.value = state.value.copy(status = "モデルの整合性を確認中…", progress = 1f)
        val partial = File(directory, "recommended.partial")
        try {
            check(partial.length() == SIZE) { "ダウンロードしたモデルのサイズが一致しません。" }
            val sha = partial.inputStream().use { checksum(it) }
            check(sha == SHA) { "モデルの検証に失敗しました。再取得してください。" }
            validateHeader(partial)
            install(partial, NAME, sha)
            save(state.value.copy(downloadId = -1, status = "モデルを取得しました。機内モードでも会話できます。", error = null))
            manager.remove(id)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            partial.delete()
            manager.remove(id)
            save(state.value.copy(downloadId = -1, status = "", error = e.message ?: "モデルを検証できません。"))
        }
    }
    suspend fun import(uri: Uri) {
        check(!state.value.downloading) { "ダウンロード完了後に読み込んでください。" }
        var name = "ローカルモデル.gguf"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        require(name.endsWith(".gguf", true)) { "テキスト用のGGUFファイルを選んでください。" }
        require(size <= MAX_SIZE) { "6GB以下の量子化モデルを選んでください。" }
        if (size > 0) check(directory.usableSpace > size + 256L * 1024 * 1024) { "モデルを保存する空き容量が足りません。" }
        val partial = File(directory, "import-${UUID.randomUUID()}.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SIZE) { "6GB以下のモデルを選んでください。" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            } } ?: throw ApiException("選択したファイルを読み込めません。")
            check(size < 0 || total == size) { "モデルファイルを最後まで読み込めませんでした。" }
            validateHeader(partial)
            install(partial, name.take(120), digest.digest().hex())
        } finally { partial.delete() }
    }
    private fun install(partial: File, name: String, sha: String) {
        state.value.models.find { it.sha256 == sha }?.let { select(it.id); partial.delete(); return }
        val model = DeviceModel(UUID.randomUUID().toString(), name, sha, partial.length())
        val destination = file(model)
        check(partial.renameTo(destination)) { "モデルを保存できませんでした。" }
        try { save(state.value.copy(models = state.value.models + model, activeId = model.id, error = null)) }
        catch (e: Exception) { destination.delete(); throw e }
    }
    suspend fun delete(id: String) {
        val model = state.value.models.find { it.id == id } ?: return
        check(!file(model).exists() || file(model).delete()) { "モデルを削除できません。" }
        val remaining = state.value.models.filter { it.id != id }
        save(state.value.copy(models = remaining, activeId = if (state.value.activeId == id) remaining.firstOrNull()?.id else state.value.activeId))
    }
    private suspend fun checksum(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun validateHeader(file: File) {
        require(file.length() >= 1024 * 1024) { "GGUFモデルが小さすぎるか、不完全です。" }
        val header = ByteArray(8)
        file.inputStream().use { check(it.read(header) == 8) }
        val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(header.copyOfRange(0, 4).contentEquals("GGUF".toByteArray()) && version in 2..3) { "対応するGGUF v2/v3モデルを選んでください。" }
    }
}
