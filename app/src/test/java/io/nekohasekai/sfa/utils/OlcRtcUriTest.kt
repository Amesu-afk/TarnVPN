package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OlcRtcUriTest {
    private val key = "a".repeat(64)

    @Test
    fun `parses jitsi datachannel uri`() {
        val profile = OlcRtcUri.parse("olcrtc://jitsi?datachannel@https://meet.example/room#$key\$Emergency")
        assertEquals("jitsi", profile.provider)
        assertEquals("datachannel", profile.transport)
        assertEquals("https://meet.example/room", profile.room)
        assertEquals("Emergency", profile.name)
        assertEquals("socks", profile.outbound().json.getString("type"))
        assertEquals(OlcRtcUri.SOCKS_PORT, profile.outbound().json.getInt("server_port"))
    }

    @Test
    fun `rejects transports missing from the mobile runtime`() {
        assertInvalid {
            OlcRtcUri.parse(
                "olcrtc://telemost?seichannel<fps=60&batch=64&frag=900&ack-ms=2000>@room#$key\$RU-1",
            )
        }
    }

    @Test
    fun `parses manager jitsi qr uri and preserves runtime parameters`() {
        val source =
            "olcrtc://jitsi@room:1/https://meet.example/MiamiRoom" +
                "?client_id=4e4042ff-5385-4bda-9492-c5163a7a9cbd" +
                "&dns=8.8.8.8%3A53&keepalive=30&key=$key#Miami-jitsu"

        val profile = OlcRtcUri.parse(source)

        assertEquals("jitsi", profile.provider)
        assertEquals("datachannel", profile.transport)
        assertEquals("https://meet.example/MiamiRoom", profile.room)
        assertEquals("4e4042ff-5385-4bda-9492-c5163a7a9cbd", profile.clientId)
        assertEquals("8.8.8.8:53", profile.dnsServer)
        assertEquals(30, profile.keepaliveSeconds)
        assertEquals("Miami-jitsu", profile.name)
        assertEquals(source, profile.outbound().sourceUri)
    }

    @Test
    fun `parses compact manager aliases and vp8 parameters`() {
        val profile = OlcRtcUri.parse(
            "olcrtc://telemost@r/room%2Fone?k=$key&t=vp8channel" +
                "&f=60&b=8&c=android%20client&d=1.1.1.1%3A53&ka=15#Main+instance",
        )

        assertEquals("room/one", profile.room)
        assertEquals("android client", profile.clientId)
        assertEquals("60", profile.options["vp8-fps"])
        assertEquals("8", profile.options["vp8-batch"])
        assertEquals("Main instance", profile.name)
    }

    @Test
    fun `rejects unsafe manager qr parameters`() {
        assertInvalid {
            OlcRtcUri.parse("olcrtc://jitsi@r/room?k=$key&c=client&security=none")
        }
        assertInvalid {
            OlcRtcUri.parse("olcrtc://jitsi@r/room?k=$key&key=$key&c=client")
        }
        assertInvalid {
            OlcRtcUri.parse("olcrtc://jitsi@r/room?k=$key")
        }
    }

    @Test
    fun `rejects invalid key and parameter`() {
        assertInvalid { OlcRtcUri.parse("olcrtc://jitsi?datachannel@room#bad\$x") }
        assertInvalid {
            OlcRtcUri.parse("olcrtc://jitsi?datachannel<fps=60>@room#$key\$x")
        }
    }

    @Test
    fun `rejects wbstream until its engine is included in the mobile runtime`() {
        assertInvalid { OlcRtcUri.parse("olcrtc://wbstream?vp8channel@room#$key\$x") }
    }

    @Test
    fun `rejects unavailable video transport`() {
        assertInvalid {
            OlcRtcUri.parse(
                "olcrtc://telemost?videochannel<video-codec=tile&video-w=1920&video-h=1080>@room#$key\$x",
            )
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
