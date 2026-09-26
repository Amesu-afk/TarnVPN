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

    @Test
    fun `recognises olcrtc subscription and ignores its metadata lines`() {
        val body = """
            # name = Personal olcRTC
            # refresh = 7200
            ## provider = jitsi
            olcrtc://jitsi?datachannel@room-name#${"ab".repeat(32)}${'$'}Office
            ## note = preferred
        """.trimIndent()

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(1, preview.totalEntries)
        assertEquals(1, preview.parsedCount)
        assertTrue(preview.issues.isEmpty())
    }

    @Test
    fun `recognises a mixed subscription with every regular share link scheme`() {
        val ss = Base64.encodeToString("aes-256-gcm:secret".toByteArray(), Base64.NO_WRAP)
        val vmess = Base64.encodeToString(
            """{"add":"example.com","port":"443","id":"uuid"}""".toByteArray(),
            Base64.NO_WRAP,
        )
        val body = """
            vless://uuid@example.com:443
            trojan://secret@example.com:443
            ss://$ss@example.com:8388
            vmess://$vmess
            hysteria2://secret@example.com:443
            hy2://secret@example.com:443
            tuic://uuid:pass@example.com:443
            anytls://secret@example.com:443
        """.trimIndent()

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(8, preview.totalEntries)
        assertEquals(8, preview.parsedCount)
        assertTrue(preview.issues.isEmpty())
    }

    @Test
    fun `imports telemost and reports olcrtc carriers unavailable on Android`() {
        val key = "ab".repeat(32)
        val body = """
            olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/example#$key${'$'}Telemost
            olcrtc://wbstream?vp8channel@room#$key${'$'}WB
            olcrtc://jitsi?seichannel@room#$key${'$'}SEI
        """.trimIndent()

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(3, preview.totalEntries)
        assertEquals(1, preview.parsedCount)
        assertEquals(2, preview.issues.size)
        assertTrue(preview.issues.all { it.reason.contains("not available in this Android build") })
    }
}
