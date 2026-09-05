package io.nekohasekai.sfa.tarn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One row on the servers screen. Backed 1:1 by a [io.nekohasekai.sfa.database.Profile] —
 * see the project decision that a profile *is* a server.
 */
data class ServerEntry(
    val profileId: Long,
    /** Profile name as the user imported it, e.g. "Германия, Берлин". */
    val displayName: String,
    /** Outbound tag from the config, e.g. "de-ber-01"; falls back to the endpoint host. */
    val tag: String,
    /** ISO-3166 alpha-2, uppercase, when we could work it out. */
    val countryCode: String?,
    /** Host:port of the first real outbound, used for latency probing. */
    val host: String?,
    val port: Int,
    /** sing-box outbound type: "vless", "trojan", … */
    val protocol: String?,
    /**
     * False when nothing the user changes in the app reaches this profile's config —
     * [TarnServerRepository.repatchSettings] can neither regenerate it from a source link nor
     * patch it in place, so it silently keeps whatever JSON it was created with. Worth showing:
     * such a row looks identical to a working one while every toggle is a no-op on it.
     */
    val managed: Boolean = true,
    /**
     * True when the link asked for `insecure` / `allowInsecure` and the config therefore skips
     * certificate validation on the tunnel's own TLS. That is not a detail to keep quiet: it
     * means anyone able to intercept the connection to this server can read everything inside
     * the tunnel. Self-signed hysteria2/tuic setups do legitimately need it, so it is honoured
     * — but shown.
     */
    val insecureTls: Boolean = false,
) {
    /** Regional-indicator flag; blank when the country is unknown. */
    val flag: String get() = countryCode?.let(::regionalFlag) ?: ""
}

/**
 * The regional-indicator emoji for an ISO-3166 alpha-2 code (e.g. "DE" → 🇩🇪), or "" if the code
 * isn't two ASCII letters. Top-level so the country filter can build a flag from a bare code, not
 * only from a [ServerEntry].
 */
fun regionalFlag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6
    val a = code[0].uppercaseChar()
    val b = code[1].uppercaseChar()
    if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
    return String(Character.toChars(base + (a - 'A'))) + String(Character.toChars(base + (b - 'A')))
}

/** Latency probe outcome, kept separate from [ServerEntry] so the list can refresh in place. */
sealed interface Latency {
    data object Unknown : Latency
    data object Probing : Latency
    data class Ok(val millis: Int) : Latency
    data object Unreachable : Latency
}

/**
 * Plain TCP connect time to `host:port`, in milliseconds, or null when it refuses/times out.
 * Used both for server endpoints and for DNS resolvers ([io.nekohasekai.sfa.tarn.data.TarnDns])
 * so the two sets of numbers on screen are measured the same way and stay comparable.
 */
suspend fun tcpConnectLatencyMs(host: String, port: Int, timeoutMs: Int = 3000): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
    }.getOrNull()
}
