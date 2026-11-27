package io.nekohasekai.sfa.utils

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric supplies android.net.Uri, android.util.Base64 and org.json, which the parser is
 * built on — none of them exist in a plain JVM test.
 *
 * These pin the shape of the outbound handed to the core. A share link that parses into a
 * subtly wrong outbound is the worst failure mode here: the profile imports, the tunnel comes
 * up, and only the traffic is broken, with nothing on screen to say why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyUriParserTest {

    private fun parse(uri: String, fragment: Boolean = false): JSONObject = ProxyUriParser.parse(uri, fragment).json

    @Test
    fun `record fragmentation requires the main fragmentation switch`() {
        val tls = JSONObject()
        ProxyUriParser.applyFragment(tls, enabled = true, recordFragment = true)
        assertTrue(tls.optBoolean("fragment"))
        assertTrue(tls.optBoolean("record_fragment"))
        ProxyUriParser.applyFragment(tls, enabled = false, recordFragment = true)
        assertFalse(tls.has("fragment"))
        assertFalse(tls.has("record_fragment"))
    }

    // ---- scheme recognition -----------------------------------------------------------

    @Test
    fun `recognises every scheme it can parse`() {
        ProxyUriParser.schemePrefixes().forEach { prefix ->
            assertTrue(prefix, ProxyUriParser.isSupportedUri("${prefix}whatever"))
        }
    }

    @Test
    fun `keeps an ip edge server while tls and host stay on the tunnel name`() {
        val host = "tract-weapon-lightning-infectious.trycloudflare.com"
        val link = "vless://7fdcf627-36c8-4e07-b66c-bda98ca7e10d@104.16.230.132:443" +
            "?type=ws&security=tls&sni=$host&host=$host&path=%2Fws#CF-WS-IP"
        val outbound = ProxyUriParser.parse(link, false).json
        assertEquals("104.16.230.132", outbound.getString("server"))
        assertEquals(host, outbound.getJSONObject("tls").getString("server_name"))
        assertEquals(host, outbound.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        assertEquals("/ws", outbound.getJSONObject("transport").getString("path"))
    }

    @Test
    fun `rejects unrelated schemes`() {
        assertFalse(ProxyUriParser.isSupportedUri("https://example.com"))
        assertFalse(ProxyUriParser.isSupportedUri("not a uri"))
    }

    /**
     * Every advertised scheme must actually have a parser branch. The set and the `when` are
     * two separate lists, and a scheme present in one but not the other would be reported as
     * supported and then fail at import.
     */
    @Test
    fun `every advertised scheme has a parser`() {
        val samples = mapOf(
            "vless://uuid@example.com:443" to "vless",
            "trojan://secret@example.com:443" to "trojan",
            "ss://${b64("aes-256-gcm:secret")}@example.com:8388" to "shadowsocks",
            "vmess://${b64("""{"add":"example.com","port":"443","id":"uuid"}""")}" to "vmess",
            "hysteria2://secret@example.com:443" to "hysteria2",
            "hy2://secret@example.com:443" to "hysteria2",
            "tuic://uuid:pass@example.com:443" to "tuic",
            "anytls://secret@example.com:443" to "anytls",
        )
        samples.forEach { (uri, expectedType) ->
            assertTrue(uri, ProxyUriParser.isSupportedUri(uri))
            assertEquals(uri, expectedType, parse(uri).optString("type"))
        }
    }

    @Test
    fun `accepts uppercase schemes all the way through parsing`() {
        val ss = parse("SS://${b64("aes-256-gcm:secret")}@example.com:8388")
        assertEquals("shadowsocks", ss.optString("type"))

        val vmess = parse("VMESS://${b64("""{"add":"example.com","port":"443","id":"uuid"}""")}")
        assertEquals("vmess", vmess.optString("type"))
    }

    // ---- shadowsocks: legacy plain form (issue #14) -----------------------------------

    @Test
    fun `parses legacy plain ss with padded password`() {
        val out = parse("ss://aes-256-gcm:ZRpgaBeGxtENIXgvhpkZBKz9GnWRS2dYcx9O+a2iwN4=@172.99.189.142:19999#DE")
        assertEquals("shadowsocks", out.optString("type"))
        assertEquals("aes-256-gcm", out.optString("method"))
        assertEquals("ZRpgaBeGxtENIXgvhpkZBKz9GnWRS2dYcx9O+a2iwN4=", out.optString("password"))
        assertEquals("172.99.189.142", out.optString("server"))
        assertEquals(19999, out.optInt("server_port"))
    }

    @Test
    fun `parses legacy plain ss whose unpadded credentials survive a lenient base64 decode`() {
        // The exact link from issue #14: a lenient decoder turns the userinfo into binary
        // noise, which used to land in `method` and made the core fail the whole profile
        // with "unknown method" plus unreadable characters.
        val out = parse("ss://chacha20-ietf-poly1305:NcAXc0UZI6yQJE1p8QmOv8@57.129.113.16:8388#DE")
        assertEquals("shadowsocks", out.optString("type"))
        assertEquals("chacha20-ietf-poly1305", out.optString("method"))
        assertEquals("NcAXc0UZI6yQJE1p8QmOv8", out.optString("password"))
        assertEquals("57.129.113.16", out.optString("server"))
    }

    @Test
    fun `parses ss 2022 cipher in plain form`() {
        val out = parse("ss://2022-blake3-chacha20-poly1305:8yO3ANWefuuGs1lRKXECQA1FEhxlWFV2ppQMcBaZo5Y=@8.211.38.49:443#DE")
        assertEquals("2022-blake3-chacha20-poly1305", out.optString("method"))
        assertEquals("8.211.38.49", out.optString("server"))
    }

    @Test
    fun `still decodes sip002 base64 credentials`() {
        val out = parse("ss://${b64("aes-256-gcm:secret")}@example.com:8388")
        assertEquals("aes-256-gcm", out.optString("method"))
        assertEquals("secret", out.optString("password"))
    }

    // ---- vmess: standard base64 alphabet (issue #14) -----------------------------------

    @Test
    fun `decodes vmess whose payload carries the standard plus sign`() {
        // The real link from the issue-14 subscription: the URL-safe decoder silently
        // corrupts a '+'-bearing standard base64 payload, and decodeBase64 used to try
        // URL-safe first — the profile then died with "vmess payload is not JSON".
        val uri = "vmess://eyJhZGQiOiI2Mi45My4xNjYuMTg0IiwiYWlkIjowLCJmcCI6IiIsImlkIjoiNWMwNDBmMWQtNDVkYi00M2JkLThkNDYtZTQ5NTBlNDMzNzM1IiwibmV0IjoidGNwIiwicG9ydCI6MTI3MCwicHMiOiLwn4e48J+HqiDQqNCy0LXRhtC40Y8gIzQgfCAxMDdtcyB8IDE5MyBNYnBzIiwic2N5IjoiYXV0byIsInNuaSI6InhtZmhscHkuY29tIiwidGxzIjoidGxzIiwidHlwZSI6Im5vbmUiLCJ2IjoiMiJ9"
        val out = parse(uri)
        assertEquals("vmess", out.optString("type"))
        assertEquals("62.93.166.184", out.optString("server"))
        assertEquals(1270, out.optInt("server_port"))
        assertTrue(out.optString("tag").contains("#4"))
    }

    @Test
    fun `keeps the plain password when it itself looks like base64 with padding`() {
        // A password that ends in '=' must not let the endpoint split lose it.
        val out = parse("ss://aes-256-gcm:plain==pass@example.com:8388")
        assertEquals("aes-256-gcm", out.optString("method"))
        assertEquals("plain==pass", out.optString("password"))
    }

    @Test
    fun `drops packet_encoding none from vless links`() {
        // Issue #14: subscription links carry `packetEncoding=none`; the core only accepts
        // "", "xudp" and "packetaddr" and failed the whole profile with "unknown packet
        // encoding: none". Absence is what "no override" means to it.
        val withNone = parse("vless://uuid@example.com:443?security=tls&packetEncoding=none")
        assertFalse(withNone.has("packet_encoding"))

        val withXudp = parse("vless://uuid@example.com:443?security=tls&packet_encoding=xudp")
        assertEquals("xudp", withXudp.optString("packet_encoding"))
    }

    // ---- vless ------------------------------------------------------------------------

    @Test
    fun `parses a plain vless link`() {
        val out = parse("vless://uuid-1234@example.com:8443?security=none#Berlin")
        assertEquals("vless", out.optString("type"))
        assertEquals("example.com", out.optString("server"))
        assertEquals(8443, out.optInt("server_port"))
        assertEquals("uuid-1234", out.optString("uuid"))
        assertEquals("Berlin", out.optString("tag"))
        assertNull(out.optJSONObject("tls"))
    }

    @Test
    fun `defaults the port to 443`() {
        assertEquals(443, parse("vless://uuid@example.com").optInt("server_port"))
    }

    @Test
    fun `builds reality tls from pbk and sid`() {
        val out = parse(
            "vless://uuid@example.com:443?security=reality&pbk=PUBKEY&sid=ab12&sni=www.microsoft.com&fp=firefox",
        )
        val tls = out.getJSONObject("tls")
        assertTrue(tls.optBoolean("enabled"))
        assertEquals("www.microsoft.com", tls.optString("server_name"))
        assertEquals("firefox", tls.getJSONObject("utls").optString("fingerprint"))
        val reality = tls.getJSONObject("reality")
        assertTrue(reality.optBoolean("enabled"))
        assertEquals("PUBKEY", reality.optString("public_key"))
        assertEquals("ab12", reality.optString("short_id"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reality without a public key is rejected`() {
        parse("vless://uuid@example.com:443?security=reality")
    }

    /** Ordinary stream transports must clear Vision, while Xray's encrypted-XHTTP extension
     * requires it to survive import together with the VLESS encryption spec. */
    @Test
    fun `keeps vision only for transports that support it`() {
        val withTransport = parse("vless://uuid@example.com:443?type=xhttp&flow=xtls-rprx-vision")
        assertEquals("", withTransport.optString("flow"))

        val encryption = "mlkem768x25519plus.native.0rtt.PUBLIC_KEY"
        val encryptedXhttp = parse(
            "vless://uuid@example.com:443?type=xhttp&flow=xtls-rprx-vision&encryption=$encryption",
        )
        assertEquals("xtls-rprx-vision", encryptedXhttp.optString("flow"))
        assertEquals(encryption, encryptedXhttp.optString("encryption"))

        val rawTcp = parse("vless://uuid@example.com:443?type=tcp&flow=xtls-rprx-vision&security=reality&pbk=K")
        assertEquals("xtls-rprx-vision", rawTcp.optString("flow"))
    }

    @Test
    fun `applies tls fragmentation only when asked`() {
        val on = parse("vless://uuid@example.com:443?security=tls", fragment = true)
            .getJSONObject("tls")
        assertTrue(on.optBoolean("fragment"))
        // The record-level half is a separate setting now: it re-frames the ClientHello, which
        // some Reality servers fail to reassemble, so it must not ride along with the safe half.
        assertFalse(on.has("record_fragment"))

        val off = parse("vless://uuid@example.com:443?security=tls", fragment = false)
            .getJSONObject("tls")
        assertFalse(off.has("fragment"))
        assertFalse(off.has("record_fragment"))
    }

    @Test
    fun `carries the insecure flag through`() {
        val tls = parse("vless://uuid@example.com:443?security=tls&allowInsecure=1")
            .getJSONObject("tls")
        assertTrue(tls.optBoolean("insecure"))
    }

    @Test
    fun `preserves vless encryption and packet encoding`() {
        val encryption = "mlkem768x25519plus.native.0rtt.PUBLIC_KEY"
        val out = parse(
            "vless://uuid@example.com:443?TYPE=TCP&ENCRYPTION=$encryption&packetEncoding=xudp&flow=xtls-rprx-vision",
        )
        assertEquals(encryption, out.optString("encryption"))
        assertEquals("xudp", out.optString("packet_encoding"))
        assertEquals("xtls-rprx-vision", out.optString("flow"))
    }

    @Test
    fun `maps h2 and http link transports instead of emitting invalid types`() {
        val h2 = parse(
            "vless://uuid@example.com:443?type=h2&host=one.example.com,two.example.com&path=/h2&method=GET",
        ).getJSONObject("transport")
        assertEquals("http", h2.optString("type"))
        assertEquals("/h2", h2.optString("path"))
        assertEquals("GET", h2.optString("method"))
        assertEquals(2, h2.getJSONArray("host").length())

        val vmessPayload = """{"add":"example.com","port":"443","id":"uuid","net":"h2","host":"cdn.example.com","path":"/vmess"}"""
        val vmess = parse("vmess://${b64(vmessPayload)}").getJSONObject("transport")
        assertEquals("http", vmess.optString("type"))
        assertEquals("cdn.example.com", vmess.optString("host"))
        assertEquals("/vmess", vmess.optString("path"))
    }

    @Test
    fun `normalises xhttp extra ranges and strips path query`() {
        val extra = Uri.encode(
            """{"scMaxEachPostBytes":1000000,"scMinPostsIntervalMs":30.0,"xPaddingBytes":"100-1000","noGRPCHeader":false,"headers":{"X-Test":"value"}}""",
        )
        val transport = parse(
            "vless://uuid@example.com:443?type=xhttp&path=%2Fedge%3Fed%3D2048&extra=$extra",
        ).getJSONObject("transport")
        assertEquals("/edge", transport.optString("path"))
        assertEquals("1000000-1000000", transport.optString("sc_max_each_post_bytes"))
        assertEquals("30-30", transport.optString("sc_min_posts_interval_ms"))
        assertEquals("100-1000", transport.optString("x_padding_bytes"))
        assertTrue(transport.has("no_grpc_header"))
        assertFalse(transport.optBoolean("no_grpc_header"))
        assertEquals("value", transport.getJSONObject("headers").optString("X-Test"))
    }

    @Test
    fun `maps canonical xray session id fields from xhttp extra`() {
        val extra = Uri.encode(
            """{"sessionIDPlacement":"header","sessionIDKey":"X-New-Session","sessionIDTable":"Base62","sessionIDLength":12.0}""",
        )
        val transport = parse(
            "vless://uuid@example.com:443?type=xhttp&mode=packet-up&extra=$extra",
        ).getJSONObject("transport")
        assertEquals("header", transport.optString("session_placement"))
        assertEquals("X-New-Session", transport.optString("session_key"))
        assertEquals("Base62", transport.optString("session_id_table"))
        assertEquals("12-12", transport.optString("session_id_length"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown transports before a broken profile is written`() {
        parse("vless://uuid@example.com:443?type=not-a-transport")
    }

    // ---- trojan -----------------------------------------------------------------------

    @Test
    fun `trojan defaults to tls without being told`() {
        val out = parse("trojan://p%40ssword@example.com:443#Node")
        assertEquals("trojan", out.optString("type"))
        assertEquals("p@ssword", out.optString("password"))
        assertTrue(out.getJSONObject("tls").optBoolean("enabled"))
    }

    // ---- shadowsocks ------------------------------------------------------------------

    @Test
    fun `parses SIP002 shadowsocks`() {
        val out = parse("ss://${b64("aes-256-gcm:secret")}@example.com:8388#SS")
        assertEquals("shadowsocks", out.optString("type"))
        assertEquals("aes-256-gcm", out.optString("method"))
        assertEquals("secret", out.optString("password"))
        assertEquals("example.com", out.optString("server"))
        assertEquals(8388, out.optInt("server_port"))
        assertEquals("SS", out.optString("tag"))
    }

    @Test
    fun `parses an IPv6 shadowsocks endpoint`() {
        val out = parse("ss://${b64("aes-256-gcm:secret")}@[2001:db8::1]:8388#IPv6")
        assertEquals("2001:db8::1", out.optString("server"))
        assertEquals(8388, out.optInt("server_port"))
    }

    @Test
    fun `parses the legacy fully-encoded shadowsocks form`() {
        val out = parse("ss://${b64("aes-256-gcm:secret@example.com:8388")}#Legacy")
        assertEquals("aes-256-gcm", out.optString("method"))
        assertEquals("secret", out.optString("password"))
        assertEquals("example.com", out.optString("server"))
        assertEquals(8388, out.optInt("server_port"))
    }

    @Test
    fun `splits a SIP003 plugin from its options`() {
        val out = parse("ss://${b64("aes-256-gcm:secret")}@example.com:8388?plugin=obfs-local;obfs=http")
        assertEquals("obfs-local", out.optString("plugin"))
        assertEquals("obfs=http", out.optString("plugin_opts"))
    }

    // ---- vmess ------------------------------------------------------------------------

    @Test
    fun `parses the v2rayN vmess payload`() {
        val payload = """
            {"v":"2","ps":"Tokyo","add":"example.com","port":"443","id":"uuid-9",
             "aid":"2","scy":"auto","net":"ws","path":"/ray","host":"cdn.example.com","tls":"tls"}
        """.trimIndent()
        val out = parse("vmess://${b64(payload)}")
        assertEquals("vmess", out.optString("type"))
        assertEquals("Tokyo", out.optString("tag"))
        assertEquals("example.com", out.optString("server"))
        assertEquals(443, out.optInt("server_port"))
        assertEquals("uuid-9", out.optString("uuid"))
        assertEquals(2, out.optInt("alter_id"))
        assertTrue(out.getJSONObject("tls").optBoolean("enabled"))
    }

    @Test
    fun `omits alter_id when it is zero`() {
        val out = parse("vmess://${b64("""{"add":"example.com","port":"443","id":"u","aid":"0"}""")}")
        assertFalse(out.has("alter_id"))
    }

    // ---- quic-based -------------------------------------------------------------------

    @Test
    fun `hysteria2 always gets tls and picks up obfs`() {
        val out = parse("hysteria2://secret@example.com:8443?obfs=salamander&obfs-password=pw&sni=cdn.example.com")
        assertEquals("hysteria2", out.optString("type"))
        assertEquals("secret", out.optString("password"))
        val tls = out.getJSONObject("tls")
        assertTrue(tls.optBoolean("enabled"))
        assertEquals("cdn.example.com", tls.optString("server_name"))
        val obfs = out.getJSONObject("obfs")
        assertEquals("salamander", obfs.optString("type"))
        assertEquals("pw", obfs.optString("password"))
    }

    @Test
    fun `preserves available QUIC outbound tuning`() {
        val hy2 = parse("hy2://secret@example.com:443?upMbps=25&down_mbps=120")
        assertEquals(25, hy2.optInt("up_mbps"))
        assertEquals(120, hy2.optInt("down_mbps"))

        val tuic = parse("tuic://uuid:pass@example.com:443?zeroRttHandshake=true&udpOverStream=1&heartbeat=10s")
        assertTrue(tuic.optBoolean("zero_rtt_handshake"))
        assertTrue(tuic.optBoolean("udp_over_stream"))
        assertEquals("10s", tuic.optString("heartbeat"))

        val anytls = parse(
            "anytls://secret@example.com:443?idleSessionCheckInterval=30s&idle_session_timeout=60s&minIdleSession=2&clientMetadata=phone",
        )
        assertEquals("30s", anytls.optString("idle_session_check_interval"))
        assertEquals("60s", anytls.optString("idle_session_timeout"))
        assertEquals(2, anytls.optInt("min_idle_session"))
        assertEquals("phone", anytls.optString("client_metadata"))
    }

    @Test
    fun `tuic splits uuid and password out of the userinfo`() {
        val out = parse("tuic://uuid-7:pa%3Ass@example.com:443?congestion_control=bbr&udp_relay_mode=quic")
        assertEquals("uuid-7", out.optString("uuid"))
        assertEquals("pa:ss", out.optString("password"))
        assertEquals("bbr", out.optString("congestion_control"))
        assertEquals("quic", out.optString("udp_relay_mode"))
        assertTrue(out.getJSONObject("tls").optBoolean("enabled"))
    }

    @Test
    fun `anytls carries its password and tls`() {
        val out = parse("anytls://secret@example.com:443#Any")
        assertEquals("anytls", out.optString("type"))
        assertEquals("secret", out.optString("password"))
        assertEquals("Any", out.optString("tag"))
        assertTrue(out.getJSONObject("tls").optBoolean("enabled"))
    }

    // ---- failures ---------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unsupported scheme`() {
        parse("socks5://example.com:1080")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects vless without a uuid`() {
        parse("vless://example.com:443")
    }

    private fun b64(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
}
