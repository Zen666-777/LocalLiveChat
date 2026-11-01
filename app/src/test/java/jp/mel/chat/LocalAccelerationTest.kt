package jp.mel.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.local.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LocalAccelerationTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mel_acceleration", 0).edit().clear().commit()
    }

    @Test fun defaultsToGpuAndPersistsAnExplicitCpuChoice() {
        val acceleration = LocalAcceleration(context)
        assertTrue(acceleration.preferGpu.value)
        acceleration.selectGpu(false)
        assertFalse(LocalAcceleration(context).run { it })
    }

    @Test fun processDeathDuringGpuWorkUsesCpuUntilUserRetries() {
        val prefs = context.getSharedPreferences("mel_acceleration", 0)
        prefs.edit().putBoolean("gpu_running", true).commit()
        val recovered = LocalAcceleration(context)
        assertTrue(recovered.preferGpu.value)
        assertTrue(recovered.recovery.value)
        assertFalse(recovered.run { it })
        assertTrue(LocalAcceleration(context).recovery.value)
        recovered.selectGpu(true)
        assertTrue(recovered.run { it })
        assertFalse(LocalAcceleration(context).recovery.value)
    }

    @Test fun gpuMarkerIsPresentWhileWorkerRunsAndClearedEvenOnFailure() {
        val acceleration = LocalAcceleration(context)
        val prefs = context.getSharedPreferences("mel_acceleration", 0)
        try {
            acceleration.run {
                assertTrue(it)
                assertTrue(prefs.getBoolean("gpu_running", false))
                throw IllegalStateException("native failure")
            }
            fail("failure was swallowed")
        } catch (expected: IllegalStateException) {
            assertEquals("native failure", expected.message)
        }
        assertFalse(prefs.getBoolean("gpu_running", true))
        assertFalse(LocalAcceleration(context).recovery.value)
    }

    @Test fun cpuModeDoesNotMarkGpuWorkAndStatusUsesTheActualNativeSelection() {
        val acceleration = LocalAcceleration(context)
        acceleration.selectGpu(false)
        acceleration.run {
            assertFalse(it)
            assertFalse(context.getSharedPreferences("mel_acceleration", 0).getBoolean("gpu_running", false))
        }
        acceleration.loaded("cpu\nunavailable")
        assertEquals("cpu", acceleration.device.value.kind)
        assertTrue(acceleration.device.value.detail.contains("利用できない"))
        acceleration.loaded("gpu\nAdreno test")
        assertEquals("gpu", acceleration.device.value.kind)
        assertEquals("Adreno test", acceleration.device.value.detail)
        acceleration.loaded("cpu\ninitialization_failed")
        assertEquals("cpu", acceleration.device.value.kind)
    }
}
