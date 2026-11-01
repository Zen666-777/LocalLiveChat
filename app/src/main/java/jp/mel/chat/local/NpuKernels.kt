package jp.mel.chat.local

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** DSP binaries are APK assets, never user-supplied native code or downloads. */
internal class NpuKernels(private val directory: File, private val open: (String) -> InputStream) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "npu-kernels"), { context.assets.open(it) })
    fun prepare(): String {
        val manifest = open("npu/manifest.json").bufferedReader().use { it.readText() }
        require(manifest.length <= 8192)
        val entries = JSONArray(manifest)
        require(entries.length() in 1..4)
        check(directory.mkdirs() || directory.isDirectory)
        val names = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val name = item.getString("name")
            val sha = item.getString("sha256")
            require(name.matches(Regex("libggml-htp-v(73|75|79|81)\\.so")) && names.add(name))
            require(sha.matches(Regex("[a-f0-9]{64}")))
            val file = File(directory, name)
            if (file.isFile && digest(file) == sha) continue
            val temporary = File(directory, "$name.tmp")
            try {
                // A stale read-only temp from an interrupted copy can be removed
                // within this private directory before writing a new APK asset.
                check(!temporary.exists() || temporary.delete())
                open("npu/$name").use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 32L * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(digest(temporary) == sha) { "NPU用部品の検証に失敗しました。" }
                check(temporary.setReadOnly())
                check(!file.exists() || file.delete())
                check(temporary.renameTo(file))
            } finally { temporary.delete() }
        }
        return directory.absolutePath
    }

    private fun digest(file: File): String {
        check(file.length() <= 32L * 1024 * 1024)
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
