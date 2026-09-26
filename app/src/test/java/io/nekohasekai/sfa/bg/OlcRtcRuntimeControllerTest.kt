package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.utils.OlcRtcUri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OlcRtcRuntimeControllerTest {
    @Test
    fun `redacts profile data from runtime diagnostics`() {
        val room = "https://meet.example/PrivateRoom"
        val key = "a".repeat(64)
        val clientId = "device-42"
        val source = "olcrtc://jitsi?datachannel@$room#$key\$Private"
        val log = "jitsi: room=$room device=$clientId key=${key.uppercase()} source=$source"

        val redacted = OlcRtcRuntimeController.redactRuntimeLog(
            log,
            listOf(source, room, key, clientId),
        )

        assertFalse(redacted.contains(room))
        assertFalse(redacted.contains(key, ignoreCase = true))
        assertFalse(redacted.contains(clientId))
        assertEquals(
            "jitsi: room=<redacted> device=<redacted> key=<redacted> source=<redacted>",
            redacted,
        )
    }

    @Test
    fun `uses the default liveness interval when URI omits it`() {
        assertEquals(10_000L, OlcRtcRuntimeController.livenessIntervalMillis(null))
        assertEquals(30_000L, OlcRtcRuntimeController.livenessIntervalMillis(30))
    }

    @Test
    fun `keeps a running olcrtc session for a reload of the same source`() {
        val source = "olcrtc://telemost?vp8channel@room#${"a".repeat(64)}"
        assertEquals(true, OlcRtcRuntimeController.shouldReuseRunningProfile(source, source, true))
        assertEquals(false, OlcRtcRuntimeController.shouldReuseRunningProfile(source, source, false))
        assertEquals(false, OlcRtcRuntimeController.shouldReuseRunningProfile(source, "$source-new", true))
        assertEquals(false, OlcRtcRuntimeController.shouldReuseRunningProfile(null, source, true))
    }

    @Test
    fun `points only the olcrtc socks outbound at the per-start endpoint`() {
        val source = "olcrtc://jitsi?datachannel@https://meet.example/room#${"a".repeat(64)}\$Miami"
        val config = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject(OlcRtcUri.parse(source).outbound().json.toString()))
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(
                        JSONObject().put("type", "socks").put("tag", "other")
                            .put("server", "10.0.0.1").put("server_port", OlcRtcUri.SOCKS_PORT),
                    ),
            )
            .toString()
        val endpoint = OlcRtcRuntimeController.LocalEndpoint(41234, "user", "pass")

        val outbounds = JSONObject(OlcRtcRuntimeController.applyLocalEndpoint(config, endpoint))
            .getJSONArray("outbounds")

        val carrier = outbounds.getJSONObject(0)
        assertEquals(41234, carrier.getInt("server_port"))
        assertEquals("user", carrier.getString("username"))
        assertEquals("pass", carrier.getString("password"))
        assertFalse(outbounds.getJSONObject(1).has("username"))
        val other = outbounds.getJSONObject(2)
        assertEquals(OlcRtcUri.SOCKS_PORT, other.getInt("server_port"))
        assertFalse(other.has("password"))
    }

    @Test
    fun `leaves non-olcrtc configs untouched and refuses a profile without the carrier outbound`() {
        val config = """{"outbounds":[{"type":"direct","tag":"direct"}]}"""
        assertEquals(config, OlcRtcRuntimeController.applyLocalEndpoint(config, null))
        assertThrows(IllegalStateException::class.java) {
            OlcRtcRuntimeController.applyLocalEndpoint(
                config,
                OlcRtcRuntimeController.LocalEndpoint(41234, "user", "pass"),
            )
        }
    }

    @Test
    fun `every start gets its own loopback port and credentials`() {
        val first = OlcRtcRuntimeController.LocalEndpoint.random()
        val second = OlcRtcRuntimeController.LocalEndpoint.random()
        assertTrue(first.port in 1..65_535)
        assertEquals(32, first.username.length)
        assertEquals(32, first.password.length)
        assertNotEquals(first.password, second.password)
        assertNotEquals(first.username, first.password)
    }
}
