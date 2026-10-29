package jp.mel.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LocalModelStoreTest {
    private lateinit var context: Context
    private lateinit var source: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mel_models", 0).edit().clear().commit()
        source = File(context.cacheDir, "test-model.gguf")
        source.outputStream().use { output ->
            output.write(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0))
            output.write(ByteArray(1024 * 1024))
        }
    }
    @After fun cleanup() = runBlocking {
        val store = LocalModelStore(context)
        store.state.value.models.forEach { store.delete(it.id) }
        source.delete()
        Unit
    }
    @Test fun importsAndRestoresAnAppOwnedModelWithoutDependingOnTheSourceUri() = runBlocking {
        val store = LocalModelStore(context)
        store.import(Uri.fromFile(source))
        val imported = store.active()!!
        assertEquals(source.length(), imported.bytes)
        assertEquals(64, imported.sha256.length)
        source.delete()
        val restored = LocalModelStore(context)
        assertEquals(imported, restored.active())
        assertTrue(restored.file(imported).isFile)
    }
    @Test fun importingSameWeightsDoesNotDuplicateStorage() = runBlocking {
        val store = LocalModelStore(context)
        repeat(2) { store.import(Uri.fromFile(source)) }
        assertEquals(1, store.state.value.models.size)
    }
    @Test fun truncatedOrWrongFormatDoesNotReplaceTheActiveModel() = runBlocking {
        val store = LocalModelStore(context)
        store.import(Uri.fromFile(source))
        val before = store.active()
        source.writeText("not gguf")
        assertTrue(runCatching { store.import(Uri.fromFile(source)) }.isFailure)
        assertEquals(before, store.active())
    }
    @Test fun deletingWeightsDoesNotDeleteTheImportedSource() = runBlocking {
        val store = LocalModelStore(context)
        store.import(Uri.fromFile(source))
        val model = store.active()!!
        store.delete(model.id)
        assertNull(store.active())
        assertFalse(store.file(model).exists())
        assertTrue(source.exists())
    }
}
