package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pool block belongs to the XHTTP transport options, not the outbound: the core
 * decodes `outbounds[i].transport.xmux` and rejects an unknown `xmux` key on the
 * outbound itself ("json: unknown field \"xmux\""), which is exactly the import error
 * an earlier build produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessImporterXmuxTest {

    private fun generated(uri: String): JSONObject {
        val batch = VlessImporter.toSingBoxImportBatch(uri) { "" }
        return JSONObject(batch.servers.single().config)
    }

    private fun proxyOutbound(config: JSONObject): JSONObject {
        val outbounds = config.getJSONArray("outbounds")
        return (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .first { it.optString("type") == "vless" }
    }

    private fun xhttpUri(): String = "vless://11111111-2222-3333-4444-555555555555@185.28.175.193:443" +
        "?type=xhttp&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "&fp=chrome&sni=example.com&path=%2Fx#TestXhttp"

    private fun tcpUri(): String = "vless://11111111-2222-3333-4444-555555555555@185.28.175.193:443" +
        "?security=tls&sni=example.com#TestTcp"

    @Test
    fun `xmux lives inside the xhttp transport and not on the outbound`() {
        val previous = Settings.tarnXhttpPool
        try {
            Settings.tarnXhttpPool = 4
            val outbound = proxyOutbound(generated(xhttpUri()))
            assertFalse("xmux must not sit on the outbound itself", outbound.has("xmux"))
            val transport = outbound.getJSONObject("transport")
            assertEquals("xhttp", transport.getString("type"))
            assertEquals(
                4,
                transport.getJSONObject("xmux").getInt("max_connections"),
            )
        } finally {
            Settings.tarnXhttpPool = previous
        }
    }

    @Test
    fun `a tcp outbound carries no xmux block`() {
        val outbound = proxyOutbound(generated(tcpUri()))
        assertFalse(outbound.has("xmux"))
        assertFalse(outbound.optJSONObject("transport")?.has("xmux") ?: false)
    }
}
