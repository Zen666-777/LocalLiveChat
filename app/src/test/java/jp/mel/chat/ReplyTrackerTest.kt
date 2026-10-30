package jp.mel.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.ui.ReplyTracker
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReplyTrackerTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("reply-test", 0)
    @Before fun setup() { prefs.edit().clear().commit() }
    @Test fun failureRemainsAvailableAfterRestartUntilExplicitRetry() {
        val tracker = ReplyTracker(prefs)
        tracker.start("mel")
        tracker.finish("空きメモリが足りません")
        val reopened = ReplyTracker(prefs)
        assertEquals("mel", reopened.state.value.characterId)
        assertEquals("空きメモリが足りません", reopened.state.value.error)
        assertFalse(reopened.state.value.running)
        reopened.start("mel")
        assertNull(reopened.state.value.error)
        reopened.finish()
        assertNull(ReplyTracker(prefs).state.value.error)
    }
    @Test fun processDeathReportsTheLastPhaseWithoutClaimingGenerationIsStillRunning() {
        val tracker = ReplyTracker(prefs)
        tracker.start("mel")
        tracker.stage("モデルを読み込み中")
        val reopened = ReplyTracker(prefs)
        assertTrue(reopened.state.value.error!!.contains("モデルを読み込み中"))
        assertFalse(reopened.state.value.running)
        assertFalse(prefs.contains("reply_inflight"))
    }
    @Test fun aLateProgressEventCannotRestartAFinishedRequestOrClearItsError() {
        val tracker = ReplyTracker(prefs)
        tracker.start("mel")
        tracker.finish("error")
        tracker.stage("返信を生成中")
        assertFalse(tracker.state.value.running)
        assertEquals("error", tracker.state.value.error)
    }
}
