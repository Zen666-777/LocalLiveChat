package jp.mel.chat

import jp.mel.chat.local.NpuKernels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NpuKernelsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = "synthetic-test-kernel".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun manifest(name: String = "libggml-htp-v81.so", hash: String = sha) = JSONArray().put(JSONObject().put("name", name).put("sha256", hash)).toString()
    private fun installer(directory: File, manifest: String) = NpuKernels(directory) { path ->
        ByteArrayInputStream(if (path == "npu/manifest.json") manifest.toByteArray() else content)
    }
    @Test fun verifiesAssetsAndRepairsAStalePrivateCopy() {
        val dir = temporary.newFolder()
        val kernel = File(dir, "libggml-htp-v81.so")
        kernel.writeText("stale")
        val install = installer(dir, manifest())
        assertEquals(dir.absolutePath, install.prepare())
        assertArrayEquals(content, kernel.readBytes())
        assertEquals(dir.absolutePath, install.prepare())
    }
    @Test fun rejectsEscapingNamesBeforeOpeningAFile() {
        val dir = temporary.newFolder()
        try { installer(dir, manifest("../outside.so")).prepare(); fail("path escaped") }
        catch (_: IllegalArgumentException) { assertTrue(dir.listFiles()!!.isEmpty()) }
    }
    @Test fun checksumMismatchLeavesNoExecutableKernelOrPartialFile() {
        val dir = temporary.newFolder()
        try { installer(dir, manifest(hash = "0".repeat(64))).prepare(); fail("bad checksum accepted") }
        catch (_: IllegalStateException) { assertTrue(dir.listFiles()!!.isEmpty()) }
    }
}
