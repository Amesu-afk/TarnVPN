package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.DnsOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
          "outbounds": [{"type": "vless", "tag": "berlin", "server": "example.com"},
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
    ): JSONObject {
        val patched = VlessImporter.applySettings(
            configJson = managedConfig(),
            option = cloudflare,
            dnsProtection = dnsProtection,
            ipv6Enabled = ipv6Enabled,
            fragmentEnabled = false,
            quicPolicy = Settings.QUIC_POLICY_AUTO,
            tunMtu = 0,
            ipStrategy = Settings.IP_STRATEGY_AUTO,
            dnsRoute = dnsRoute,
            logLevel = Settings.LOG_LEVEL_WARN,
            testUrl = Settings.DEFAULT_TARN_TEST_URL,
            sendHostname = true,
        )
        assertNotNull("applySettings refused a config it owns", patched)
        return JSONObject(patched!!)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

    private fun dnsRules(config: JSONObject): List<JSONObject> =
        config.getJSONObject("dns").optJSONArray("rules")?.objects() ?: emptyList()

    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { arr -> (0 until arr.length()).map(arr::optString) } ?: emptyList()

    private fun aaaaRule(config: JSONObject) =
        dnsRules(config).firstOrNull { it.strings("query_type") == listOf("AAAA") }

    private fun mediaRule(config: JSONObject) =
        dnsRules(config).firstOrNull { it.optString("server") == "bootstrap" }

    @Test
    fun `suppresses AAAA for the phone-resolved hosts`() {
        val rule = aaaaRule(patch())
        assertNotNull("no AAAA suppression rule", rule)
        assertEquals("predefined", rule!!.optString("action"))
        assertEquals("NOERROR", rule.optString("rcode"))
        // The list has to match the route table's resolve rule: those are the hosts whose
        // destination is replaced with a v4-first phone-resolved address, which is what makes
        // the AAAA answer dead weight rather than a lost address family.
        val resolveRule = patch().getJSONObject("route").getJSONArray("rules").objects()
            .first { it.optString("action") == "resolve" }
        assertEquals(resolveRule.strings("domain_suffix"), rule.strings("domain_suffix"))
        assertTrue("googlevideo.com missing", "googlevideo.com" in rule.strings("domain_suffix"))
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
        assertEquals(listOf("googlevideo.com"), rule!!.strings("domain_suffix"))
        // Answering AAAA locally has to win over routing it to bootstrap, or the AAAA for a
        // googlevideo host would still leave the device.
        val rules = dnsRules(patch())
        assertTrue(
            "AAAA rule must precede the media rule",
            rules.indexOfFirst { it.strings("query_type") == listOf("AAAA") } <
                rules.indexOfFirst { it.optString("server") == "bootstrap" },
        )
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
