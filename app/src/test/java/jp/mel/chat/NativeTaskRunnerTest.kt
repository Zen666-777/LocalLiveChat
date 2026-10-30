package jp.mel.chat

import jp.mel.chat.local.NativeTaskRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NativeTaskRunnerTest {
    @Test fun nativeWorkerErrorsReachTheCallerInsteadOfLeavingItSuspended() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val runner = NativeTaskRunner({}, {}, worker)
            val failure = runCatching { runner.run { throw OutOfMemoryError("test") } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("メモリ"))
            assertEquals("retry", runner.run { "retry" })
        } finally { worker.shutdownNow() }
    }
    @Test fun timeoutAbortsAndJoinsTheWorkerBeforeTheNextRequest() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        val cancel = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(false)
        try {
            val runner = NativeTaskRunner({ cancel.set(false) }, { cancel.set(true) }, worker, timeoutMs = 500)
            val failure = runCatching { runner.run {
                while (!cancel.get()) Thread.sleep(1)
                Thread.sleep(10)
                cleanedUp.set(true)
                "late response"
            } }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("3分"))
            assertTrue(cleanedUp.get())
            assertEquals("next", runner.run { "next" })
        } finally { worker.shutdownNow() }
    }
    @Test fun cancellationWaitsForNativeCleanupAndKeepsCancellationSemantics() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        val cancel = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val cleanedUp = AtomicBoolean(false)
        try {
            val runner = NativeTaskRunner({}, { cancel.set(true) }, worker)
            val job = launch { runner.run {
                entered.complete(Unit)
                while (!cancel.get()) Thread.sleep(1)
                cleanedUp.set(true)
            } }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(cleanedUp.get())
        } finally { worker.shutdownNow() }
    }
    @Test fun rejectedWorkerDoesNotHangWaitingForACompletionThatCannotArrive() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().apply { shutdown() }
        val runner = NativeTaskRunner({}, {}, worker)
        assertTrue(runCatching { runner.run { "never" } }.isFailure)
    }
}
