package io.nekohasekai.sfa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the ownership check only — the part of [VlessImporter] that does not read
 * [io.nekohasekai.sfa.database.Settings]. The rest of the importer does, and Settings is
 * backed by a Room-backed data store that wants a real Application, so exercising it here
 * would be an integration test wearing a unit test's clothes.
 *
 * This one is worth having on its own: `isManagedConfig` decides whether
 * `TarnServerRepository.repatchSettings()` is allowed to rewrite a file. A false positive
 * silently overwrites a config somebody hand-wrote in the advanced UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessImporterManagedConfigTest {

    private fun config(
        tunAddresses: String = """["172.19.0.1/30", "fdfe:dcba:9876::1/126"]""",
        resolver: String = "doh",
        outbounds: String = """
            [{"type": "vless", "tag": "berlin", "server": "example.com"},
             {"type": "direct", "tag": "direct"}]
        """,
        dnsServers: String = """[{"tag": "doh"}]""",
    ) = """
        {
          "dns": {"servers": $dnsServers},
          "inbounds": [{"type": "tun", "tag": "tun-in", "address": $tunAddresses}],
          "outbounds": $outbounds,
          "route": {"default_domain_resolver": "$resolver"}
        }
    """.trimIndent()

    @Test
    fun `recognises a config it generated`() {
        assertTrue(VlessImporter.isManagedConfig(config()))
    }

    @Test
    fun `accepts the legacy ipv4-only tun shape`() {
        assertTrue(VlessImporter.isManagedConfig(config(tunAddresses = """["172.19.0.1/30"]""")))
    }

    @Test
    fun `accepts the bootstrap resolver`() {
        assertTrue(VlessImporter.isManagedConfig(config(resolver = "bootstrap")))
    }

    /**
     * Every importable protocol has to count. Recognising only vless would have frozen
     * profiles imported from every other share link the moment those became importable.
     */
    @Test
    fun `recognises every protocol the importer can emit`() {
        listOf("vless", "trojan", "shadowsocks", "vmess", "hysteria2", "tuic", "anytls").forEach { type ->
            val json = config(
                outbounds = """
                    [{"type": "$type", "tag": "node", "server": "example.com"},
                     {"type": "direct", "tag": "direct"}]
                """,
            )
            assertTrue(type, VlessImporter.isManagedConfig(json))
        }
    }

    // ---- everything below must NOT be claimed as ours --------------------------------

    @Test
    fun `refuses a foreign tun address`() {
        assertFalse(VlessImporter.isManagedConfig(config(tunAddresses = """["10.0.0.1/30"]""")))
    }

    @Test
    fun `refuses an unfamiliar resolver`() {
        assertFalse(VlessImporter.isManagedConfig(config(resolver = "local")))
    }

    @Test
    fun `refuses a config with no direct outbound`() {
        val json = config(outbounds = """[{"type": "vless", "tag": "n", "server": "example.com"}]""")
        assertFalse(VlessImporter.isManagedConfig(json))
    }

    @Test
    fun `refuses a config with no server outbound`() {
        assertFalse(VlessImporter.isManagedConfig(config(outbounds = """[{"type": "direct", "tag": "direct"}]""")))
    }

    /** The whole point of the structural signature: a bare `doh` tag is not ownership. */
    @Test
    fun `refuses a hand-written config that merely uses the doh tag`() {
        val handWritten = """
            {
              "dns": {"servers": [{"tag": "doh", "address": "https://dns.google/dns-query"}]},
              "inbounds": [{"type": "tun", "tag": "my-tun", "address": ["10.10.0.1/30"]}],
              "outbounds": [{"type": "vless", "tag": "n", "server": "example.com"},
                            {"type": "direct", "tag": "direct"}],
              "route": {"default_domain_resolver": "doh"}
            }
        """.trimIndent()
        assertFalse(VlessImporter.isManagedConfig(handWritten))
    }

    @Test
    fun `refuses a config without the doh dns server`() {
        assertFalse(VlessImporter.isManagedConfig(config(dnsServers = """[{"tag": "local"}]""")))
    }

    @Test
    fun `refuses input that is not json at all`() {
        assertFalse(VlessImporter.isManagedConfig("not json"))
        assertFalse(VlessImporter.isManagedConfig(""))
    }
}
