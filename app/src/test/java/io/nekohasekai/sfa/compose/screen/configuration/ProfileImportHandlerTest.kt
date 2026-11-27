package io.nekohasekai.sfa.compose.screen.configuration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileImportHandlerTest {
    private val handler = ProfileImportHandler(RuntimeEnvironment.getApplication())

    @Test
    fun `direct remote subscriptions require HTTPS`() = runBlocking {
        val rejected = handler.parseQRCode("http://example.com/subscription")
        assertTrue(rejected is ProfileImportHandler.QRCodeParseResult.Error)

        val accepted = handler.parseQRCode("https://example.com/subscription")
        assertEquals(
            ProfileImportHandler.QRCodeParseResult.RemoteProfile(
                name = "subscription",
                host = "example.com",
                url = "https://example.com/subscription",
            ),
            accepted,
        )
    }

    @Test
    fun `profile streams accept the exact limit and reject excess data`() {
        val exact = ByteArray(16) { it.toByte() }
        assertArrayEquals(exact, readProfileBytes(ByteArrayInputStream(exact), maxBytes = 16))
        assertThrows(IllegalArgumentException::class.java) {
            readProfileBytes(ByteArrayInputStream(ByteArray(17)), maxBytes = 16)
        }
    }
}
