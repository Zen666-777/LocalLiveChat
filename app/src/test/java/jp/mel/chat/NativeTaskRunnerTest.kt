package jp.mel.chat

import jp.mel.chat.local.NativeTaskRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch

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
    @Test fun unresponsiveNativeWorkerDoesNotHoldTimeoutErrorOrPermitAbortFlagReset() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val begins = AtomicInteger()
        val cleaned = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        try {
            val runner = NativeTaskRunner({ begins.incrementAndGet() }, {}, worker, timeoutMs = 500, cleanupWaitMs = 30)
            // Warm the executor/coroutine classes before the intentionally short
            // timeout, so this exercises an entered native call, not JVM startup.
            assertEquals("warm", runner.run { "warm" })
            val failure = withTimeout(5000) { runCatching { runner.run { entered.complete(Unit); release.await(); cleaned.set(true); "late" } }.exceptionOrNull() }
            assertTrue(failure!!.message!!.contains("3分"))
            assertTrue(entered.isCompleted)
            assertFalse(cleaned.get())
            val blocked = runCatching { runner.run { error("must not run") } }.exceptionOrNull()
            assertTrue(blocked!!.message!!.contains("停止処理"))
            assertEquals(2, begins.get())
            release.countDown()
            withTimeout(2000) { while (!cleaned.get()) delay(1) }
            assertEquals("recovered", runner.run { "recovered" })
            assertEquals(3, begins.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun userCancellationReturnsEvenWhenNativeCleanupIsStalled() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        try {
            val runner = NativeTaskRunner({}, {}, worker, cleanupWaitMs = 30)
            val job = launch { runner.run { entered.complete(Unit); release.await() } }
            entered.await()
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(runCatching { runner.run { "must wait" } }.exceptionOrNull()!!.message!!.contains("停止処理"))
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
