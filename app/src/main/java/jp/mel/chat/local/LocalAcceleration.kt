package jp.mel.chat.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class InferenceDevice(val kind: String = "idle", val detail: String = "", val fallback: String = "") {
    val label get() = when (kind) {
        "gpu" -> "GPU割り当て · $detail（CPU併用）"
        "npu" -> "NPU割り当て · $detail（CPU併用）"
        "cpu" -> "CPU · $detail"
        else -> "次の会話で処理先を確認します"
    }
    companion object {
        internal fun fromNative(value: String): InferenceDevice {
            val lines = value.split('\n')
            val extra = when (lines.getOrNull(2)) {
                "npu_model_format" -> "このモデルの量子化形式はNPU試験版の対象外です。Q4_0などの対応モデルが必要です。"
                "npu_kernels_missing" -> "NPU用部品を準備できないため、別の処理先を使います。"
                "npu_unavailable" -> "この端末のNPUドライバーを利用できないため、別の処理先を使います。"
                "npu_initialization_failed" -> "NPUでのモデル読み込みに失敗したため、別の処理先を使います。"
                "npu_not_built" -> "このAPKにはNPU用エンジンが含まれていません。"
                else -> ""
            }
            return when (lines.firstOrNull()) {
                "gpu", "npu" -> InferenceDevice(lines[0], lines.getOrElse(1) { "" }.take(160), extra)
                else -> InferenceDevice("cpu", when (lines.getOrNull(1)) {
                    "unavailable" -> "対応GPUを利用できないため"
                    "initialization_failed" -> "GPUの読み込みに失敗したため"
                    else -> "CPUのみを使用"
                }, extra)
            }
        }
    }
}

/** Persistent recovery from a driver crash/process death, without changing cloud settings. */
class LocalAcceleration(context: Context) {
    private val prefs = context.getSharedPreferences("mel_acceleration", Context.MODE_PRIVATE)
    val preferGpu = MutableStateFlow(prefs.getBoolean("prefer_gpu", true))
    val preferNpu = MutableStateFlow(prefs.getBoolean("prefer_npu", false))
    val recovery = MutableStateFlow(prefs.getBoolean("gpu_interrupted", false) || prefs.getBoolean("gpu_running", false))
    val npuRecovery = MutableStateFlow(prefs.getBoolean("npu_interrupted", false) || prefs.getBoolean("npu_running", false))
    val device = MutableStateFlow(InferenceDevice())

    init {
        if (prefs.getBoolean("gpu_running", false)) {
            check(prefs.edit().putBoolean("gpu_interrupted", true).putBoolean("gpu_running", false).commit())
        }
        if (prefs.getBoolean("npu_running", false)) {
            check(prefs.edit().putBoolean("npu_interrupted", true).putBoolean("npu_running", false).commit())
        }
    }

    fun selectGpu(value: Boolean) {
        // Selecting GPU again is an explicit retry after an interrupted operation.
        check(prefs.edit().putBoolean("prefer_gpu", value).putBoolean("prefer_npu", false).putBoolean("gpu_interrupted", false).commit())
        preferGpu.value = value
        preferNpu.value = false
        recovery.value = false
        device.value = InferenceDevice()
    }
    fun selectNpu() {
        check(prefs.edit().putBoolean("prefer_gpu", true).putBoolean("prefer_npu", true)
            .putBoolean("gpu_interrupted", false).putBoolean("npu_interrupted", false).commit())
        preferGpu.value = true
        preferNpu.value = true
        recovery.value = false
        npuRecovery.value = false
        device.value = InferenceDevice()
    }

    internal val useGpu get() = preferGpu.value && !recovery.value

    // Called inside the native worker, so a caller's timeout cannot clear the
    // crash marker while that worker is still running a GPU driver operation.
    internal fun <T> run(action: (Boolean) -> T): T = runWithDevices { gpu, _ -> action(gpu) }
    internal fun <T> runWithDevices(action: (Boolean, Boolean) -> T): T {
        val gpu = useGpu
        val npu = preferNpu.value && !npuRecovery.value && !recovery.value
        if (gpu || npu) check(prefs.edit().putBoolean("gpu_running", gpu).putBoolean("npu_running", npu).commit())
        return try { action(gpu, npu) }
        finally { if (gpu || npu) check(prefs.edit().putBoolean("gpu_running", false).putBoolean("npu_running", false).commit()) }
    }

    internal fun loaded(value: String) {
        device.value = if (recovery.value) InferenceDevice("cpu", "前回の処理中断後の復旧モード")
            else InferenceDevice.fromNative(value).let {
                if (preferNpu.value && npuRecovery.value) it.copy(fallback = "前回のNPU処理中断後の復旧モードです。") else it
            }
    }
}
