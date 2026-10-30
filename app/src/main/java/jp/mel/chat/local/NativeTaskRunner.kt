package jp.mel.chat.local

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A stalled native worker must not hold the UI forever or have its abort flag reset. */
internal class NativeTaskRunner(
    private val begin: () -> Unit,
    private val requestAbort: () -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "MEL-local-LLM") },
    private val timeoutMs: Long = 180_000,
    private val cleanupWaitMs: Long = 2_000
) {
    private val mutex = Mutex()
    // Guarded by mutex. A timed-out action may still be unwinding in C++.
    private var previous: CompletableDeferred<Unit>? = null
    suspend fun <T> run(action: () -> T): T = mutex.withLock {
        previous?.takeUnless { it.isCompleted }?.let { pending ->
            val ready = withTimeoutOrNull(cleanupWaitMs) { pending.await(); true } ?: false
            check(ready) { "前回の推論エンジンの停止処理が終わっていません。少し待って再試行してください。続く場合はAndroidのアプリ情報からMEL Chatを強制停止して開き直してください。保存済みの投稿は残っています。" }
        }
        try { begin() } catch (failure: Throwable) { throw readableFailure(failure) }
        val finished = CompletableDeferred<Unit>()
        previous = finished
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { requestAbort() }
                    try {
                        worker.execute {
                            try {
                                if (continuation.isActive) continuation.resume(action())
                            } catch (failure: Throwable) {
                                // A native linkage/memory Error must resume the caller too.
                                if (continuation.isActive) continuation.resumeWithException(readableFailure(failure))
                            } finally { finished.complete(Unit) }
                        }
                    } catch (failure: Throwable) {
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resumeWithException(readableFailure(failure))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error("端末での処理が3分を超えたため返信を中断しました。少し待って再試行してください。停止処理が終わらない場合はAndroidのアプリ情報からMEL Chatを強制停止して開き直してください。投稿は保存されています。")
        } finally {
            // Cooperative native cancellation normally finishes immediately. If
            // not, return control to the caller, retaining the barrier above.
            withContext(NonCancellable) { withTimeoutOrNull(cleanupWaitMs) { finished.await() } }
        }
    }
}

private fun readableFailure(failure: Throwable): Throwable = when (failure) {
    is OutOfMemoryError -> IllegalStateException("推論中に空きメモリが不足しました。他のアプリを閉じて再試行してください。", failure)
    is LinkageError -> IllegalStateException("推論エンジンを起動できませんでした。最新版のAPKへ更新してください。", failure)
    is Error -> IllegalStateException("推論エンジンが停止しました（${failure.javaClass.simpleName}）。投稿は保存されています。", failure)
    else -> failure
}
