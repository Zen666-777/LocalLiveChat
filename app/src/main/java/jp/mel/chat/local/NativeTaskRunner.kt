package jp.mel.chat.local

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns one native worker. Cancellation must finish before another call resets its flag. */
internal class NativeTaskRunner(
    private val begin: () -> Unit,
    private val requestAbort: () -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "MEL-local-LLM") },
    private val timeoutMs: Long = 180_000
) {
    private val mutex = Mutex()
    suspend fun <T> run(action: () -> T): T = mutex.withLock {
        try { begin() } catch (failure: Throwable) { throw readableFailure(failure) }
        val finished = CompletableDeferred<Unit>()
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
            error("端末での処理が3分を超えたため停止しました。他のアプリを閉じて再試行するか、小さいモデルを選んでください。投稿は保存されています。")
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }
}

private fun readableFailure(failure: Throwable): Throwable = when (failure) {
    is OutOfMemoryError -> IllegalStateException("推論中に空きメモリが不足しました。他のアプリを閉じて再試行してください。", failure)
    is LinkageError -> IllegalStateException("推論エンジンを起動できませんでした。最新版のAPKへ更新してください。", failure)
    is Error -> IllegalStateException("推論エンジンが停止しました（${failure.javaClass.simpleName}）。投稿は保存されています。", failure)
    else -> failure
}
