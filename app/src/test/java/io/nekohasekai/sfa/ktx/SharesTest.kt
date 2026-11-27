package io.nekohasekai.sfa.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SharesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `share filename uses the profile id inside the canonical cache directory`() {
        val file = createShareFile(temporaryFolder.root, 42, "bpf")
        val directory = File(temporaryFolder.root, "share").canonicalFile

        assertEquals("42.bpf", file.name)
        assertEquals(directory, file.parentFile)
        assertTrue(file.canonicalFile.startsWith(directory))
    }

    @Test
    fun `unsaved profiles get distinct random filenames`() {
        val first = createShareFile(temporaryFolder.root, 0, "json")
        val second = createShareFile(temporaryFolder.root, 0, "json")

        assertNotEquals(first, second)
        assertEquals(UUID.fromString(first.nameWithoutExtension).toString(), first.nameWithoutExtension)
        assertEquals(UUID.fromString(second.nameWithoutExtension).toString(), second.nameWithoutExtension)
    }
}
