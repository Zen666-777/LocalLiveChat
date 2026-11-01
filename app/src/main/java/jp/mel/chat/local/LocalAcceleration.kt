package jp.mel.chat.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class InferenceDevice(val kind: String = "idle", val detail: String = "") {
    val label get() = when (kind) {
        "gpu" -> "GPU割り当て · $detail（CPU併用）"
        "cpu" -> "CPU · $detail"
        else -> "次の会話で処理先を確認します"
    }
    companion object {
        internal fun fromNative(value: String) = when {
            value.startsWith("gpu\n") -> InferenceDevice("gpu", value.substringAfter('\n').take(160))
            value == "cpu\nunavailable" -> InferenceDevice("cpu", "対応GPUを利用できないため")
            value == "cpu\ninitialization_failed" -> InferenceDevice("cpu", "GPUの読み込みに失敗したため")
            else -> InferenceDevice("cpu", "CPUのみを使用")
        }
    }
}

/** Persistent recovery from a driver crash/process death, without changing cloud settings. */
class LocalAcceleration(context: Context) {
    private val prefs = context.getSharedPreferences("mel_acceleration", Context.MODE_PRIVATE)
    val preferGpu = MutableStateFlow(prefs.getBoolean("prefer_gpu", true))
    val recovery = MutableStateFlow(prefs.getBoolean("gpu_interrupted", false) || prefs.getBoolean("gpu_running", false))
    val device = MutableStateFlow(InferenceDevice())

    init {
        if (prefs.getBoolean("gpu_running", false)) {
            check(prefs.edit().putBoolean("gpu_interrupted", true).putBoolean("gpu_running", false).commit())
        }
    }

    fun selectGpu(value: Boolean) {
        // Selecting GPU again is an explicit retry after an interrupted operation.
        check(prefs.edit().putBoolean("prefer_gpu", value).putBoolean("gpu_interrupted", false).commit())
        preferGpu.value = value
        recovery.value = false
        device.value = InferenceDevice()
    }

    internal val useGpu get() = preferGpu.value && !recovery.value

    // Called inside the native worker, so a caller's timeout cannot clear the
    // crash marker while that worker is still running a GPU driver operation.
    internal fun <T> run(action: (Boolean) -> T): T {
        val gpu = useGpu
        if (gpu) check(prefs.edit().putBoolean("gpu_running", true).commit())
        return try { action(gpu) }
        finally { if (gpu) check(prefs.edit().putBoolean("gpu_running", false).commit()) }
    }

    internal fun loaded(value: String) {
        device.value = if (recovery.value) InferenceDevice("cpu", "前回の処理中断後の復旧モード")
            else InferenceDevice.fromNative(value)
    }
}
