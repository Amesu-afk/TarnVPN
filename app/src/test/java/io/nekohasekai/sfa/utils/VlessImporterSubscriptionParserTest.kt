package io.nekohasekai.sfa.utils

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessImporterSubscriptionParserTest {

    @Test
    fun `reports rejected links instead of silently dropping them`() {
        val body = """
            vless://uuid@example.com:443?security=tls#working
            unsupported://example.com:443#new-provider-type
            vless://uuid-two@example.com:443?type=not-a-transport#broken
        """.trimIndent()

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(3, preview.totalEntries)
        assertEquals(1, preview.parsedCount)
        assertEquals(2, preview.issues.size)
        assertTrue(preview.issues.any { it.reason == "Unsupported share-link scheme" })
        assertTrue(preview.issues.any { it.reason.contains("Unsupported transport") })
    }

    @Test
    fun `recognises base64 subscriptions with mixed supported schemes`() {
        val raw = """
            vless://uuid@example.com:443?security=tls#one
            trojan://password@example.net:443#two
        """.trimIndent()
        val encoded = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

        val preview = VlessImporter.inspectSubscriptionBody(encoded)

        assertEquals(2, preview.totalEntries)
        assertEquals(2, preview.parsedCount)
        assertTrue(preview.issues.isEmpty())
    }
}
