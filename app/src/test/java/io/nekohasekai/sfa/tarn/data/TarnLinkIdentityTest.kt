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
}
