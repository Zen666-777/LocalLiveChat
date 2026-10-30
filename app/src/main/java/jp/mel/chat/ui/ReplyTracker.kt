package jp.mel.chat.ui

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

data class ReplyState(val characterId: String? = null, val running: Boolean = false,
    val stage: String = "", val startedAt: Long = 0, val error: String? = null)

/** Persists diagnostics, never prompts or generated text. Survives an OS process kill. */
class ReplyTracker(private val prefs: SharedPreferences) {
    val state = MutableStateFlow(restore())
    private fun restore(): ReplyState {
        val interrupted = prefs.getString("reply_inflight", null)
        if (interrupted != null) {
            val phase = prefs.getString("reply_stage", "処理中")
            val message = "前回の返信処理が中断されました（最後の処理：$phase）。アプリ終了やメモリ不足などが考えられます。再試行してください。投稿は保存されています。"
            prefs.edit().remove("reply_inflight").remove("reply_stage")
                .putString("reply_error_id", interrupted).putString("reply_error", message).commit()
            return ReplyState(characterId = interrupted, error = message)
        }
        return ReplyState(characterId = prefs.getString("reply_error_id", null), error = prefs.getString("reply_error", null))
    }
    @Synchronized fun start(id: String) {
        check(prefs.edit().putString("reply_inflight", id).putString("reply_stage", "返信の準備中")
            .remove("reply_error_id").remove("reply_error").commit()) { "返信の状態を保存できませんでした。空き容量を確認してください。" }
        state.value = ReplyState(id, true, "返信の準備中", SystemClock.elapsedRealtime())
    }
    @Synchronized fun stage(value: String) {
        val current = state.value
        if (!current.running || value.isBlank() || current.stage == value) return
        prefs.edit().putString("reply_stage", value).apply()
        state.value = current.copy(stage = value)
    }
    @Synchronized fun finish(error: String? = null) {
        val current = state.value
        prefs.edit().remove("reply_inflight").remove("reply_stage")
            .putString("reply_error_id", if (error == null) null else current.characterId)
            .putString("reply_error", error).commit()
        state.value = current.copy(running = false, error = error)
    }
}
