package io.nekohasekai.sfa.tarn.component

import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TarnQrImportTest {
    @Test
    fun `accepts trimmed subscription and olcrtc text`() {
        assertEquals(
            "https://example.com/sub",
            QRScanResult.RawText("  https://example.com/sub  ").tarnImportText(),
        )
        assertEquals(
            "olcrtc://jitsi?datachannel@room#key",
            QRScanResult.RawText("olcrtc://jitsi?datachannel@room#key").tarnImportText(),
        )
    }

    @Test
    fun `rejects empty text and qrs bundle`() {
        assertNull(QRScanResult.RawText("   ").tarnImportText())
        assertNull(QRScanResult.QRSData(byteArrayOf(1, 2, 3)).tarnImportText())
    }
}
