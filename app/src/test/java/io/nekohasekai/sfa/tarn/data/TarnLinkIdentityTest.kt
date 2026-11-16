package io.nekohasekai.sfa.tarn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TarnLinkIdentityTest {

    @Test
    fun `keeps identity when a provider only reorders link fields or renames it`() {
        val original = "vless://uuid@example.com:443?type=ws&security=tls&host=cdn.example.com&path=%2Fray#Old%20name"
        val refreshed = "VLESS://uuid@example.com:443?path=%2Fray&HOST=cdn.example.com&SECURITY=tls&TYPE=ws#New%20name"

        assertEquals(
            TarnLinkIdentity.connectionKey(original),
            TarnLinkIdentity.connectionKey(refreshed),
        )
    }

    @Test
    fun `does not merge servers with different credentials`() {
        val first = "vless://uuid-one@example.com:443?type=ws&security=tls&host=cdn.example.com"
        val second = "vless://uuid-two@example.com:443?type=ws&security=tls&host=cdn.example.com"

        assertNotEquals(
            TarnLinkIdentity.connectionKey(first),
            TarnLinkIdentity.connectionKey(second),
        )
    }

    @Test
    fun `uses the awg endpoint body as identity and ignores the comment name`() {
        val first = """
            [Interface]
            PrivateKey = WD18EchEsl5d-BKihACcdjkPjoa6gcTM4p7bVaqKoGQ
            Address = 10.8.1.2/32
            Jc = 4
            S1 = 87
            S2 = 44

            # First name
            [Peer]
            PublicKey = TAcJhi55Wt9A4zvMmyQ85iS0LZkLvkC7ManSQDHukwQ
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 185.28.175.193:56100
        """.trimIndent()
        val renamed = first.replace("# First name", "# Renamed by the panel")
        val otherKey = first.replace(
            "TAcJhi55Wt9A4zvMmyQ85iS0LZkLvkC7ManSQDHukwQ",
            "JHXkBz2tA9Mx8mk21_bbZjTniwj2yiMQuLUhWoj6oG8",
        )

        assertEquals(TarnLinkIdentity.connectionKey(first), TarnLinkIdentity.connectionKey(renamed))
        assertNotEquals(TarnLinkIdentity.connectionKey(first), TarnLinkIdentity.connectionKey(otherKey))
    }

    @Test
    fun `keeps distinct olcrtc rooms and ignores only their display names`() {
        val first = "olcrtc://jitsi?datachannel@https://meet.example/alpha#${"a".repeat(64)}\$Alpha"
        val renamed = "olcrtc://jitsi?datachannel@https://meet.example/alpha#${"a".repeat(64)}\$Renamed"
        val second = "olcrtc://jitsi?datachannel@https://meet.example/bravo#${"b".repeat(64)}\$Bravo"

        assertEquals(
            TarnLinkIdentity.connectionKey(first),
            TarnLinkIdentity.connectionKey(renamed),
        )
        assertNotEquals(
            TarnLinkIdentity.connectionKey(first),
            TarnLinkIdentity.connectionKey(second),
        )
    }
}
