package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.DnsOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The DNS rules that decide what a new hostname costs. Short-video feeds mint a new CDN host
 * per clip, so every clip starts on a cold lookup — and a cold lookup through the tunnel is a
 * full proxy round-trip, doubled while AAAA is also asked for. These rules are what keeps that
 * off the video path; a silent regression here shows up as feed stutter and nothing else.
 *
 * Shapes are asserted against `sing-box check` separately — the core rejects a DNS rule that
 * carries `strategy` outright (legacy DNS mode), which is why suppression is expressed as a
 * `predefined` answer on a `query_type` item instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessImporterDnsRulesTest {

    private val cloudflare = DnsOption("cloudflare", "Cloudflare", "1.1.1.1", "cloudflare-dns.com", "1.1.1.1")

    /** Minimal config that passes the ownership check, so `applySettings` will rewrite it. */
    private fun managedConfig() = """
        {
          "dns": {"servers": [{"tag": "doh", "type": "https", "server": "1.1.1.1"}], "final": "doh"},
          "inbounds": [{"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"]}],
          "outbounds": [{"type": "vless", "tag": "berlin", "server": "example.com",
                         "tls": {"enabled": true, "server_name": "example.com",
                                 "utls": {"enabled": true, "fingerprint": "chrome"}}},
                        {"type": "direct", "tag": "direct"}],
          "route": {"rules": [{"ip_is_private": true, "outbound": "direct"}],
                    "final": "berlin",
                    "default_domain_resolver": "doh"}
        }
    """.trimIndent()

    private fun patch(
        dnsRoute: String = Settings.DNS_ROUTE_AUTO,
        ipv6Enabled: Boolean = true,
        dnsProtection: Boolean = true,
        ruDirect: Boolean = true,
        remoteDirectDomains: Set<String> = emptySet(),
        directDomains: Set<String> = emptySet(),
        fragmentEnabled: Boolean = false,
        recordFragment: Boolean = false,
        tlsFingerprint: String = Settings.TLS_FINGERPRINT_AUTO,
    ): JSONObject {
        val patched = VlessImporter.applySettings(
            configJson = managedConfig(),
            option = cloudflare,
            dnsProtection = dnsProtection,
            ipv6Enabled = ipv6Enabled,
            fragmentEnabled = fragmentEnabled,
            quicPolicy = Settings.QUIC_POLICY_AUTO,
            tunMtu = 0,
            ipStrategy = Settings.IP_STRATEGY_AUTO,
            dnsRoute = dnsRoute,
            logLevel = Settings.LOG_LEVEL_WARN,
            testUrl = Settings.DEFAULT_TARN_TEST_URL,
            sendHostname = true,
            ruDirect = ruDirect,
            remoteDirectDomains = remoteDirectDomains,
            directDomains = directDomains,
            recordFragment = recordFragment,
            tlsFingerprint = tlsFingerprint,
        )
        assertNotNull("applySettings refused a config it owns", patched)
        return JSONObject(patched!!)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

    private fun dnsRules(config: JSONObject): List<JSONObject> = config.getJSONObject("dns").optJSONArray("rules")?.objects() ?: emptyList()

    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { arr -> (0 until arr.length()).map(arr::optString) } ?: emptyList()

    private fun aaaaRule(config: JSONObject) = dnsRules(config).firstOrNull { it.strings("query_type") == listOf("AAAA") }

    private fun bootstrapRules(config: JSONObject) = dnsRules(config).filter { it.optString("server") == "bootstrap" }

    // Both off-tunnel rules name the same server, so they are told apart by what they carry
    // rather than by position — otherwise adding one silently reassigns the other's assertions.
    private fun mediaRule(config: JSONObject) = bootstrapRules(config).firstOrNull { "googlevideo.com" in it.strings("domain_suffix") }

    // The other off-tunnel rule: identified by not being the media one, so it is still found
    // when the Russian list is off and only the user's own domains are in it.
    private fun directDnsRule(config: JSONObject) = bootstrapRules(config).firstOrNull { "googlevideo.com" !in it.strings("domain_suffix") }

    private fun routeRules(config: JSONObject) = config.getJSONObject("route").getJSONArray("rules").objects()

    private fun directRouteRule(config: JSONObject) = routeRules(config).firstOrNull {
        it.has("domain_suffix") && it.optString("outbound") == "direct"
    }

    @Test
    fun `suppresses AAAA for the phone-resolved hosts and the media CDNs`() {
        val rule = aaaaRule(patch())
        assertNotNull("no AAAA suppression rule", rule)
        assertEquals("predefined", rule!!.optString("action"))
        assertEquals("NOERROR", rule.optString("rcode"))
        val suppressed = rule.strings("domain_suffix")
        // Superset of the route table's resolve rule: for those hosts the destination becomes a
        // v4-first phone-resolved address, so an AAAA answer cannot reach the wire at all.
        val phoneResolved = patch().getJSONObject("route").getJSONArray("rules").objects()
            .first { it.optString("action") == "resolve" }
            .strings("domain_suffix")
        assertTrue("phone-resolved hosts must be covered", suppressed.containsAll(phoneResolved))
        // Plus every media CDN, whose destination leaves as a name instead — there the phone's
        // AAAA answer only picks a local socket family and costs an upstream lookup to do it.
        assertTrue(
            "media CDNs must be covered",
            suppressed.containsAll(mediaRule(patch())!!.strings("domain_suffix")),
        )
        assertEquals("no duplicate suffixes", suppressed.distinct(), suppressed)
    }

    /**
     * The saving has to survive the IPv6 switch being off too: `ipv4_only` already answers AAAA
     * locally, so the rule is redundant then — but emitting it either way keeps one config shape
     * per DNS route instead of one per (route × switch), and repatch idempotent.
     */
    @Test
    fun `suppresses AAAA with the ipv6 switch off as well`() {
        assertNotNull(aaaaRule(patch(ipv6Enabled = false)))
        assertEquals("ipv4_only", patch(ipv6Enabled = false).getJSONObject("dns").optString("strategy"))
        assertEquals("prefer_ipv4", patch().getJSONObject("dns").optString("strategy"))
    }

    @Test
    fun `sends media lookups off the tunnel on auto`() {
        val rule = mediaRule(patch())
        assertNotNull("no off-tunnel media rule", rule)
        // Every short-video feed, not only the one the symptom was reported on: they all mint a
        // new host per clip, so they all pay the cold through-tunnel lookup.
        assertTrue(
            "expected the media CDN set, got ${rule!!.strings("domain_suffix")}",
            rule.strings("domain_suffix").containsAll(
                listOf("googlevideo.com", "cdninstagram.com", "fbcdn.net", "ttvnw.net"),
            ),
        )
        // Answering AAAA locally has to win over routing it to bootstrap, or the AAAA for a
        // googlevideo host would still leave the device.
        val rules = dnsRules(patch())
        assertTrue(
            "AAAA rule must precede the media rule",
            rules.indexOfFirst { it.strings("query_type") == listOf("AAAA") } <
                rules.indexOfFirst {
                    it.optString("server") == "bootstrap" &&
                        "googlevideo.com" in it.strings("domain_suffix")
                },
        )
    }

    /**
     * A service routed direct has to be *named* from here too. Resolving it through the exit
     * hands it a foreign edge address — or one it refuses outright — and then dials that
     * address directly, which is worse than either choice made consistently.
     */
    @Test
    fun `resolves russian services off the tunnel and routes them direct`() {
        val config = patch()
        val dnsRule = directDnsRule(config)
        assertNotNull("no off-tunnel rule for Russian services", dnsRule)
        assertTrue(
            "expected the country TLDs, got ${dnsRule!!.strings("domain_suffix")}",
            dnsRule.strings("domain_suffix").containsAll(listOf(".ru", ".su", ".xn--p1ai")),
        )

        val routeRule = directRouteRule(config)
        assertNotNull("no direct route for Russian services", routeRule)
        assertEquals("direct", routeRule!!.optString("outbound"))
        assertEquals(
            "the two halves must cover exactly the same names",
            dnsRule.strings("domain_suffix"),
            routeRule.strings("domain_suffix"),
        )
    }

    /**
     * Both rules match on `domain_suffix` and the first hit wins. A name on both lists must
     * reach its terminal outbound here rather than be resolved for a tunnel it will not use.
     */
    @Test
    fun `routes russian services before the phone-resolve rule`() {
        val rules = routeRules(patch())
        val ruIndex = rules.indexOfFirst { ".ru" in it.strings("domain_suffix") }
        val resolveIndex = rules.indexOfFirst { it.optString("action") == "resolve" }
        assertTrue("no phone-resolve rule to order against", resolveIndex >= 0)
        assertTrue("the direct rule must come first", ruIndex in 0 until resolveIndex)
    }

    /**
     * Unlike the media rule, this one is not a latency optimisation that an explicit Tunnel
     * overrides: as long as the traffic itself goes direct, its lookups have to as well.
     */
    @Test
    fun `keeps russian lookups off the tunnel even when tunnel is forced`() {
        assertNotNull(directDnsRule(patch(dnsRoute = Settings.DNS_ROUTE_TUNNEL)))
    }

    @Test
    fun `adds refreshed russian services to both direct halves`() {
        val config = patch(remoteDirectDomains = setOf("gosuslugi.ru", "yandex.net"))
        assertTrue(
            directDnsRule(config)!!.strings("domain_suffix").containsAll(
                listOf("gosuslugi.ru", "yandex.net"),
            ),
        )
        assertTrue(
            directRouteRule(config)!!.strings("domain_suffix").containsAll(
                listOf("gosuslugi.ru", "yandex.net"),
            ),
        )
    }

    private fun outbounds(config: JSONObject) = config.getJSONArray("outbounds").objects()

    private fun proxyOutbound(config: JSONObject) = outbounds(config).first { it.optString("type") == "vless" }

    /**
     * The core probes only after 5 minutes idle, and carrier NAT commonly drops an idle mapping
     * sooner — which is how a pool of quiet connections turns into one that is dead without
     * either end knowing. `direct` is left alone on purpose: the core gives an options-less
     * direct outbound special meaning.
     */
    @Test
    fun `keeps the proxy connection alive but leaves direct untouched`() {
        val config = patch()
        val proxy = proxyOutbound(config)
        assertEquals("90s", proxy.optString("tcp_keep_alive"))
        assertEquals("45s", proxy.optString("tcp_keep_alive_interval"))

        val direct = outbounds(config).first { it.optString("type") == "direct" }
        assertFalse("direct must stay options-less", direct.has("tcp_keep_alive"))
    }

    /**
     * The two halves of fragmentation are independent. Bundled, the aggressive one — which
     * re-frames the ClientHello and can break a Reality server — rode along with the safe one,
     * so the fix for a blocked handshake was also the thing that broke it.
     */
    @Test
    fun `splits record fragmentation off the plain fragmentation toggle`() {
        val safe = proxyOutbound(patch(fragmentEnabled = true)).getJSONObject("tls")
        assertTrue("plain fragmentation must be on", safe.optBoolean("fragment"))
        assertFalse("the aggressive half must not ride along", safe.has("record_fragment"))

        val both = proxyOutbound(patch(fragmentEnabled = true, recordFragment = true))
            .getJSONObject("tls")
        assertTrue(both.optBoolean("fragment"))
        assertTrue(both.optBoolean("record_fragment"))

        val off = proxyOutbound(patch()).getJSONObject("tls")
        assertFalse(off.has("fragment"))
        assertFalse(off.has("record_fragment"))
    }

    /**
     * The link's own `fp=` has to survive `auto`, because the person who wrote the link usually
     * knows what their server expects to see. An explicit choice overrides it — that is the whole
     * point of the setting: when filtering starts matching one ClientHello, swapping it is the
     * cheapest thing a user can try, and it needs nothing from the server.
     */
    @Test
    fun `overrides the TLS fingerprint only when one is chosen`() {
        fun fingerprintOf(config: JSONObject) = proxyOutbound(config)
            .getJSONObject("tls").getJSONObject("utls").optString("fingerprint")

        assertEquals("chrome", fingerprintOf(patch()))
        assertEquals("firefox", fingerprintOf(patch(tlsFingerprint = "firefox")))
        assertEquals(
            "random must reach the config verbatim — the core resolves it, not us",
            "random",
            fingerprintOf(patch(tlsFingerprint = Settings.TLS_FINGERPRINT_RANDOM)),
        )
        assertTrue(
            "uTLS must stay enabled whichever fingerprint is picked",
            proxyOutbound(patch(tlsFingerprint = "safari"))
                .getJSONObject("tls").getJSONObject("utls").optBoolean("enabled"),
        )
    }

    /**
     * The user's own names are their own list: the built-in one can never be complete, and
     * before this the only answer to "my bank is missing" was to turn the whole feature off.
     * So they have to survive the Russian toggle being off — and they have to reach both halves,
     * or the name goes direct while its lookup still comes back from the exit.
     */
    @Test
    fun `carries the user's own domains independently of the russian list`() {
        val both = patch(directDomains = setOf("mybank.example", "work.example"))
        assertTrue(
            "custom domains must join the built-in list, not replace it",
            directRouteRule(both)!!.strings("domain_suffix")
                .containsAll(listOf(".ru", "mybank.example", "work.example")),
        )

        val customOnly = patch(ruDirect = false, directDomains = setOf("mybank.example"))
        val routeRule = directRouteRule(customOnly)
        assertNotNull("custom domains must work with the Russian list off", routeRule)
        assertEquals(listOf("mybank.example"), routeRule!!.strings("domain_suffix"))
        assertEquals(
            "both halves must cover the same names",
            listOf("mybank.example"),
            directDnsRule(customOnly)!!.strings("domain_suffix"),
        )
    }

    @Test
    fun `emits neither russian rule when the toggle is off`() {
        val config = patch(
            ruDirect = false,
            remoteDirectDomains = setOf("gosuslugi.ru"),
        )
        assertNull(directDnsRule(config))
        assertNull(directRouteRule(config))
        assertNotNull("unrelated rules must survive", mediaRule(config))
    }

    /** An explicit Tunnel means no DNS off the tunnel at all — including media. */
    @Test
    fun `keeps media lookups on the tunnel when tunnel is forced`() {
        val config = patch(dnsRoute = Settings.DNS_ROUTE_TUNNEL)
        assertNull(mediaRule(config))
        assertNotNull("AAAA suppression is not a routing choice", aaaaRule(config))
        assertTrue(
            "bootstrap server should still exist",
            config.getJSONObject("dns").getJSONArray("servers").objects()
                .any { it.optString("tag") == "bootstrap" },
        )
    }

    /**
     * With DNS off the tunnel there is no `bootstrap` server to point at — the rule would name a
     * transport that does not exist, which the core rejects at load.
     */
    @Test
    fun `emits no media rule when dns is already direct`() {
        listOf(patch(dnsRoute = Settings.DNS_ROUTE_DIRECT), patch(dnsProtection = false)).forEach { config ->
            assertNull(mediaRule(config))
            assertTrue(
                "no bootstrap server expected",
                config.getJSONObject("dns").getJSONArray("servers").objects()
                    .none { it.optString("tag") == "bootstrap" },
            )
        }
    }
}
