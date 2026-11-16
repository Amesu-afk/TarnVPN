package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The connected profile is whatever the importer generated for the selected share link.
 * These tests pin the sniff split, the QUIC policy and the TUN stack in that generated
 * config: UDP keeps the dialed address (the "send IPv6 packet to IPv4 connection" failure),
 * QUIC stays available for the YouTube client, and the TUN runs on gVisor вЂ” the default
 * `mixed` stack hands TCP to a system-stack NAT that does not deliver under VpnService.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VlessImporterGeneratedConfigTest {

    private fun generated(uri: String): JSONObject {
        val batch = VlessImporter.toSingBoxImportBatch(uri) { "" }
        val config = batch.servers.single().config
        File(System.getProperty("java.io.tmpdir"), "generated-config.json").writeText(config)
        return JSONObject(config)
    }

    private fun sampleUri(): String =
        "vless://11111111-2222-3333-4444-555555555555@185.28.175.193:443" +
            "?type=xhttp&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&fp=chrome&sni=example.com&path=%2Fx#TestMiami-Test"

    private fun sniffRules(config: JSONObject): List<JSONObject> {
        val rules = config.getJSONObject("route").getJSONArray("rules")
        return (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
            .filter { it.optString("action") == "sniff" }
    }

    @Test
    fun `sniffing keeps the hostname for TCP and the address for UDP`() {
        val rules = sniffRules(generated(sampleUri()))
        val tcp = rules.firstOrNull { it.optString("network") == "tcp" }
        val udp = rules.firstOrNull { it.optString("network") == "udp" }
        assertTrue("TCP sniff rule is missing", tcp != null && tcp.optBoolean("override_destination"))
        assertTrue("UDP sniff rule is missing", udp != null)
        assertFalse(
            "UDP must keep the dialed address, otherwise an IPv6 reply cannot be written back",
            udp!!.optBoolean("override_destination"),
        )
    }

    @Test
    fun `an imported profile keeps QUIC available by default`() {
        val config = generated(sampleUri())
        val rejects = config.getJSONObject("route").getJSONArray("rules")
            .let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            .filter { it.optString("action") == "reject" && it.optInt("port") == 443 }
        assertTrue("auto must leave QUIC alone for the YouTube client", rejects.isEmpty())
    }

    @Test
    fun `an imported profile runs the tun on the selected stack`() {
        val previous = Settings.tarnTunStack
        try {
            Settings.tarnTunStack = Settings.TUN_STACK_GVISOR
            val gvisorTun = generated(sampleUri()).getJSONArray("inbounds").getJSONObject(0)
            assertTrue("expected a tun inbound", gvisorTun.optString("type") == "tun")
            assertTrue("gVisor is not the default stack", gvisorTun.optString("stack") == "gvisor")

            Settings.tarnTunStack = Settings.TUN_STACK_MIXED
            val mixedTun = generated(sampleUri()).getJSONArray("inbounds").getJSONObject(0)
            assertTrue("mixed selection was not written", mixedTun.optString("stack") == "mixed")
        } finally {
            Settings.tarnTunStack = previous
        }
    }

    @Test
    fun `block policy rejects UDP 443 before sniffing`() {
        val previous = Settings.tarnQuicPolicy
        try {
            Settings.tarnQuicPolicy = Settings.QUIC_POLICY_BLOCK
            val rules = generated(sampleUri()).getJSONObject("route").getJSONArray("rules")
                .let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            val udpRejectIndex = rules.indexOfFirst {
                it.optString("action") == "reject" && it.optInt("port") == 443 && it.optString("network") == "udp"
            }
            val udpSniffIndex = rules.indexOfFirst {
                it.optString("action") == "sniff" && it.optString("network") == "udp"
            }
            assertTrue("UDP/443 reject is missing", udpRejectIndex >= 0)
            assertTrue(
                "UDP/443 reject must precede UDP sniff or PreMatch never reaches it",
                udpSniffIndex < 0 || udpRejectIndex < udpSniffIndex,
            )
            assertTrue(
                "reject must keep no_drop under Shorts scroll",
                rules[udpRejectIndex].optBoolean("no_drop"),
            )
        } finally {
            Settings.tarnQuicPolicy = previous
        }
    }
}
