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
    fun `imports the first 128 servers of an oversized subscription instead of refusing it`() {
        // Issue #14: a real-world subscription with thousands of entries used to fail the
        // whole import; now it is truncated to the import limit with a visible notice.
        val body = (1..VlessImporter.MAX_SUBSCRIPTION_SERVERS + 10)
            .joinToString("\n") { i -> "vless://uuid-$i@example.com:443?security=tls#server-$i" }

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(VlessImporter.MAX_SUBSCRIPTION_SERVERS + 10, preview.totalEntries)
        assertEquals(VlessImporter.MAX_SUBSCRIPTION_SERVERS, preview.parsedCount)
        assertEquals(0, preview.issues.size)
        assertEquals(10, preview.truncatedCount)
    }

    @Test
    fun `imports every server of a multi-line link file that starts with vless`() {
        // Issue #14 device report: a picked .txt file starting with `vless://` used to be
        // treated as ONE share link, importing only its first server. Use the full import
        // path here — inspectSubscriptionBody() bypasses the dispatch this test protects.
        val body = """
            vless://uuid-1@example.com:443?security=tls#one
            vless://uuid-2@example.net:443?security=tls#two
            ss://aes-256-gcm:secret@example.org:8388#three
        """.trimIndent()

        val batch = VlessImporter.toSingBoxImportBatch(body) { error("unexpected fetch") }

        assertEquals(3, batch.totalEntries)
        assertEquals(3, batch.servers.size)
        assertTrue(batch.issues.isEmpty())
    }

    @Test
    fun `bare CR separates link-list entries`() {
        val body = "vless://uuid-1@example.com:443#one\rvless://uuid-2@example.net:443#two"
        val batch = VlessImporter.toSingBoxImportBatch(body) { error("unexpected fetch") }
        assertEquals(2, batch.servers.size)
    }

    @Test
    fun `duplicates and invalid entries do not crowd out unique servers at the cap`() {
        val duplicate = "vless://same@example.com:443#same"
        val invalid = "unsupported://example.com#bad"
        val unique = (1..VlessImporter.MAX_SUBSCRIPTION_SERVERS + 1)
            .map { "vless://uuid-$it@example.com:443#server-$it" }
        val body = (List(200) { duplicate } + List(200) { invalid } + unique).joinToString("\n")

        val preview = VlessImporter.inspectSubscriptionBody(body)

        assertEquals(VlessImporter.MAX_SUBSCRIPTION_SERVERS, preview.parsedCount)
        assertEquals(2, preview.truncatedCount)
        assertEquals(200, preview.issues.size)
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
