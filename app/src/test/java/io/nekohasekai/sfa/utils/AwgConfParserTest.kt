package io.nekohasekai.sfa.utils

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgConfParserTest {

    private val conf = """
        [Interface]
        PrivateKey = WD18EchEsl5d-BKihACcdjkPjoa6gcTM4p7bVaqKoGQ
        Address = 10.8.1.2/32
        DNS = 1.1.1.1, 8.8.8.8
        MTU = 1380
        Jc = 4
        Jmin = 65
        Jmax = 220
        S1 = 87
        S2 = 44
        S3 = 21
        S4 = 16
        H1 = 463065432
        H2 = 912345678
        H3 = 1345678901
        H4 = 1987654321
        I1 = <r 148>
        HeaderProtectionKey = BbSPxBds1LOL+3QDpXM3b5Z64oQceL4/SqYiEfdLbEs=
        ContentPaddingAddition = 17-49
        RekeyAfterTime = 111-139
        RekeyTimeout = 4-7
        RejectAfterTime = 187-251
        KeepaliveTimeout = 9-14
        MaxHandshakeAttempts = 19-36
        RandomTrailers = on
        DisableCookies = on

        # AWG-Tele2 - awg-phone
        [Peer]
        PublicKey = TAcJhi55Wt9A4zvMmyQ85iS0LZkLvkC7ManSQDHukwQ
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.28.175.193:56100
        PersistentKeepalive = 25
    """.trimIndent()

    private fun parse(text: String): JSONObject = AwgConfParser.parse(text).single().json

    @Test
    fun `accepts a utf8 BOM and CRLF conf`() {
        assertTrue(AwgConfParser.isAwgInput("\uFEFF${conf.replace("\n", "\r\n")}"))
        val endpoint = parse("\uFEFF${conf.replace("\n", "\r\n")}")
        assertEquals("wireguard", endpoint.getString("type"))
    }

    @Test
    fun `parses an AmneziaWG 3_1 conf into a wireguard endpoint`() {
        val endpoint = parse(conf)

        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals(1280, endpoint.getInt("mtu"))
        assertEquals("10.8.1.2/32", endpoint.getJSONArray("address").getString(0))
        assertEquals("WD18EchEsl5d+BKihACcdjkPjoa6gcTM4p7bVaqKoGQ=", endpoint.getString("private_key"))

        val peer = endpoint.getJSONArray("peers").getJSONObject(0)
        assertEquals("185.28.175.193", peer.getString("address"))
        assertEquals(56100, peer.getInt("port"))
        assertEquals("TAcJhi55Wt9A4zvMmyQ85iS0LZkLvkC7ManSQDHukwQ=", peer.getString("public_key"))
        assertEquals(25, peer.getInt("persistent_keepalive_interval"))
        assertEquals(2, peer.getJSONArray("allowed_ips").length())

        assertEquals(4, endpoint.getInt("jc"))
        assertEquals(87, endpoint.getInt("s1"))
        assertEquals(21, endpoint.getInt("s3"))
        assertEquals(463065432L, endpoint.getLong("h1"))
        assertEquals("<r 148>", endpoint.getString("i1"))
        assertEquals("17-49", endpoint.getString("content_padding_addition"))
        assertEquals("111-139", endpoint.getString("rekey_after_time"))
        assertEquals("19-36", endpoint.getString("max_handshake_attempts"))
        assertTrue(endpoint.getBoolean("random_trailers"))
        assertTrue(endpoint.getBoolean("disable_cookies"))
        assertEquals(
            "BbSPxBds1LOL+3QDpXM3b5Z64oQceL4/SqYiEfdLbEs=",
            endpoint.getString("header_protection_key"),
        )
        assertEquals("AWG-Tele2 - awg-phone", endpoint.getString("tag"))
    }

    @Test
    fun `parses a plain WireGuard conf such as Cloudflare WARP`() {
        val warp = """
            [Interface]
            PrivateKey = GHAZoJNEUWu8oxvxB9mtfg5C/JdrhDuGOCHudyh0mlQ=
            Address = 172.16.0.2/32, 2606:4700:110:866d:99a:8c64:14a:3c6a/128
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            # WARP 2408
            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 162.159.192.1:2408
            PersistentKeepalive = 25
        """.trimIndent()
        val endpoint = parse(warp)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals(2, endpoint.getJSONArray("address").length())
        assertEquals(1280, endpoint.getInt("mtu"))
        assertEquals("162.159.192.1", endpoint.getJSONArray("peers").getJSONObject(0).getString("address"))
        assertEquals(2408, endpoint.getJSONArray("peers").getJSONObject(0).getInt("port"))
        assertFalse(endpoint.has("jc"))
        assertFalse(endpoint.has("header_protection_key"))
    }

    @Test
    fun `parses a qCompress vpn link and extracts the wrapped conf`() {
        val lastConfig = JSONObject()
            .put("config", conf)
            .put("mtu", "1376")
        val root = JSONObject()
            .put("description", "Tele2 AWG")
            .put(
                "containers",
                JSONArray().put(
                    JSONObject()
                        .put("container", "amnezia-awg2")
                        .put("awg", JSONObject().put("last_config", lastConfig.toString())),
                ),
            )
        val payload = root.toString().toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { out ->
            val deflater = Deflater(8)
            try {
                deflater.setInput(payload)
                deflater.finish()
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buffer)
                    if (n > 0) out.write(buffer, 0, n)
                }
            } finally {
                deflater.end()
            }
        }.toByteArray()
        val body = ByteArrayOutputStream().also { out ->
            out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            out.write(compressed)
        }.toByteArray()
        val link = "vpn://" + Base64.encodeToString(
            body,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val servers = AwgConfParser.parse(link)
        assertEquals(1, servers.size)
        val endpoint = servers.single().json
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("Tele2 AWG", endpoint.getString("tag"))
        assertEquals(1280, endpoint.getInt("mtu"))
        assertTrue(servers.single().sourceUri.contains("[Interface]"))
        assertTrue(servers.single().sourceUri.contains("[Peer]"))
    }

    @Test
    fun `accepts a bare conf wrapped in vpn`() {
        val link = "vpn://" + Base64.encodeToString(
            conf.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val endpoint = AwgConfParser.parse(link).single().json
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("AWG-Tele2 - awg-phone", endpoint.getString("tag"))
    }

    @Test
    fun `normalises url safe keys to the standard base64 the core decodes`() {
        val urlSafe = conf
            .replace(
                "WD18EchEsl5d-BKihACcdjkPjoa6gcTM4p7bVaqKoGQ",
                "eMtcHMARco0mWBJXi1x84RDinHZNYZpD9xeaoqq_90A",
            )
        val endpoint = parse(urlSafe)
        assertEquals("eMtcHMARco0mWBJXi1x84RDinHZNYZpD9xeaoqq/90A=", endpoint.getString("private_key"))
        assertEquals(
            "TAcJhi55Wt9A4zvMmyQ85iS0LZkLvkC7ManSQDHukwQ=",
            endpoint.getJSONArray("peers").getJSONObject(0).getString("public_key"),
        )
    }

    @Test
    fun `header protection requires every padding to carry the nonce`() {
        val broken = conf.replace("S4 = 16", "S4 = 4")
        val error = runCatching { AwgConfParser.parse(broken) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("S4"))
    }

    @Test
    fun `detects conf and vpn inputs but not ordinary links`() {
        assertTrue(AwgConfParser.isAwgInput(conf))
        assertTrue(AwgConfParser.isAwgInput("vpn://AAAA"))
        assertFalse(AwgConfParser.isAwgInput("vless://uuid@example.com:443"))
        assertFalse(AwgConfParser.isAwgInput("https://example.com/sub"))
    }
}
