package io.nekohasekai.sfa.tarn.screen

import android.content.Context
import android.net.Uri
import io.nekohasekai.sfa.tarn.data.TarnServerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file picker hands the shell a content URI; [TarnServerRepository.readDocumentText] is
 * the piece between the picker and the import pipeline. A plain JVM application is enough —
 * file:// URIs need no provider, and a large file must fail the read, not the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TarnServersViewModelFileImportTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `reads a picked config file as utf-8 text`() {
        val file = File(context().getDir("imports", Context.MODE_PRIVATE), "links.txt")
        file.writeText("vless://uuid@example.com:443?security=tls#узел-1\n")

        val text = TarnServerRepository.readDocumentText(context().contentResolver, Uri.fromFile(file))

        assertEquals("vless://uuid@example.com:443?security=tls#узел-1\n", text)
    }

    @Test
    fun `rejects a document larger than the import budget`() {
        val file = File(context().getDir("imports", Context.MODE_PRIVATE), "huge.txt")
        file.writeText("x".repeat(8 * 1024 * 1024 + 1))

        assertThrows(IllegalArgumentException::class.java) {
            TarnServerRepository.readDocumentText(context().contentResolver, Uri.fromFile(file))
        }
    }

    @Test
    fun `throws a clear error for an unopenable document`() {
        val missing = Uri.fromFile(File(context().getDir("imports", Context.MODE_PRIVATE), "nope.txt"))

        assertThrows(Exception::class.java) {
            TarnServerRepository.readDocumentText(context().contentResolver, missing)
        }
    }
}
