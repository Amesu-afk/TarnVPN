package io.nekohasekai.sfa.tarn.data

import io.nekohasekai.sfa.database.Settings

/**
 * A DoH resolver the user can pick on the protection screen.
 *
 * [server] is deliberately an address rather than a hostname, so sing-box never has to make
 * a plaintext bootstrap lookup to reach the resolver. [tlsServerName] keeps TLS certificate
 * validation and the HTTP Host header bound to the resolver's canonical DNS name.
 */
data class DnsOption(
    val id: String,
    val title: String,
    val server: String,
    val tlsServerName: String?,
    /** Short line under the title — what picking this one actually buys you. */
    val note: String,
)

object TarnDns {

    val OPTIONS = listOf(
        DnsOption("cloudflare", "Cloudflare", "1.1.1.1", "cloudflare-dns.com", "1.1.1.1"),
        DnsOption("google", "Google", "8.8.8.8", "dns.google", "8.8.8.8"),
        DnsOption("quad9", "Quad9", "9.9.9.9", "dns.quad9.net", "9.9.9.9"),
        DnsOption("adguard", "AdGuard", "94.140.14.14", "dns.adguard-dns.com", "94.140.14.14"),
    )

    val DEFAULT = OPTIONS.first()

    const val CUSTOM_ID = "custom"

    private val IPV4_OCTET = "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"
    private val IPV4_REGEX = Regex("^$IPV4_OCTET(\\.$IPV4_OCTET){3}$")

    /**
     * True for a bare IPv4 address only — the same constraint every preset already meets.
     * A custom server must present a certificate valid for its literal IP address because a
     * user-provided IP has no trustworthy hostname from which to derive SNI.
     */
    fun isValidCustomServer(address: String): Boolean = IPV4_REGEX.matches(address.trim())

    /**
     * [CUSTOM_ID] resolves to whatever address the user last saved in
     * [Settings.tarnDnsCustomServer] — everything downstream (config generation, latency
     * probing) treats it exactly like any preset. Falls back to [DEFAULT] if that id is
     * selected but no valid address was ever saved, so a stale selection can't produce a
     * config with an empty resolver address.
     */
    fun byId(id: String?): DnsOption {
        if (id == CUSTOM_ID) {
            val server = Settings.tarnDnsCustomServer
            if (isValidCustomServer(server)) {
                return DnsOption(CUSTOM_ID, "Custom", server, null, server)
            }
        }
        return OPTIONS.firstOrNull { it.id == id } ?: DEFAULT
    }

    /** TCP connect time to the resolver's DoH port (443), or null when it does not answer. */
    suspend fun probe(option: DnsOption, timeoutMs: Int = 3000): Int? =
        tcpConnectLatencyMs(option.server, 443, timeoutMs)
}
