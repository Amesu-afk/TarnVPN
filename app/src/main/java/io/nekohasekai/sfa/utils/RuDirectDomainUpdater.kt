package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Refreshes the maintained list of Russian services which must stay outside the tunnel.
 *
 * The request is made before the VPN starts, so it uses the phone's normal network rather than
 * the proxy being configured. A bad, oversized, or unavailable response never prevents a VPN
 * connection: [Settings.tarnRuDirectRemoteDomains] is replaced only after the complete list has
 * passed validation, and the built-in direct list always remains available.
 *
 * The fetch is on the connect path, so it is gated to at most once per refresh interval:
 * a connect inside that window skips the network entirely and rides the cached list (which
 * `applyCurrentSettings` applies on every start anyway). Without the gate every manual connect
 * paid a full round-trip to raw.githubusercontent.com — up to the connect timeout when that host
 * is blocked or throttled, which it routinely is inside the region this list is for.
 */
object RuDirectDomainUpdater {
    private const val TAG = "RuDirectDomainUpdater"
    private const val SOURCE_URL =
        "https://raw.githubusercontent.com/itdoginfo/allow-domains/main/Russia/outside-raw.lst"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_DOMAINS = 4_096

    /**
     * A successful validation can wait 12h because the list moves slowly. A failed attempt gets
     * a shorter retry window: otherwise one temporary GitHub outage leaves a new installation
     * with an empty remote cache for half a day.
     */
    private const val SUCCESS_REFRESH_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val FAILURE_RETRY_INTERVAL_MS = 15L * 60 * 1000

    private val domainLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    /**
     * Revalidates the source with ETag when a refresh is due; failures preserve the last good set.
     *
     * @return the number of domains only when the cached routing list changed; otherwise `null`.
     */
    fun refresh(): Int? {
        if (!Settings.tarnRuDirect) return null
        val now = System.currentTimeMillis()
        // Skip the network on connects inside the window. A negative delta (the clock moved back)
        // is treated as due rather than as a 12h-into-the-future lockout.
        val lastAttempt = Settings.tarnRuDirectRemoteDomainsLastRefresh
        val sinceLast = now - lastAttempt
        if (sinceLast in 0 until refreshIntervalMs(lastAttempt, Settings.tarnRuDirectRemoteDomainsLastSuccess)) {
            return null
        }
        // Mark the attempt up front, before the request can throw or time out, so a blocked or
        // unreachable source is retried once per window instead of taxing every connect. A
        // completed attempt of any outcome — 200, 304, or failure — starts a fresh window.
        Settings.tarnRuDirectRemoteDomainsLastRefresh = now
        val connection = try {
            URL(SOURCE_URL).openConnection() as? HttpsURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "create Russian-services update request failed; using cached list", e)
            null
        } ?: return null

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Cache-Control", "no-cache")
            Settings.tarnRuDirectRemoteDomainsEtag
                .takeIf { it.isNotBlank() }
                ?.let { connection.setRequestProperty("If-None-Match", it) }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Settings.tarnRuDirectRemoteDomainsLastSuccess = now
                    Log.d(TAG, "Russian-services list is unchanged")
                }

                HttpURLConnection.HTTP_OK -> {
                    val domains = parseDomainList(readResponse(connection))
                    if (domains == null) {
                        Log.w(TAG, "rejected invalid Russian-services list; using cached list")
                        return null
                    }
                    val changed = Settings.tarnRuDirectRemoteDomains != domains
                    if (changed) {
                        Settings.tarnRuDirectRemoteDomains = domains
                    }
                    Settings.tarnRuDirectRemoteDomainsEtag = connection.getHeaderField("ETag") ?: ""
                    Settings.tarnRuDirectRemoteDomainsLastSuccess = now
                    if (changed) {
                        Log.i(TAG, "updated Russian-services list: ${domains.size} domains")
                        return domains.size
                    }
                    Log.d(TAG, "Russian-services list content is unchanged")
                }

                else -> Log.w(TAG, "Russian-services update returned HTTP ${connection.responseCode}; using cached list")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Russian-services update failed; using cached list", e)
        } finally {
            connection.disconnect()
        }
        return null
    }

    /** Exposed for regression tests; an old install has no success timestamp yet. */
    internal fun refreshIntervalMs(lastAttempt: Long, lastSuccess: Long): Long = if (lastSuccess > 0L && lastSuccess >= lastAttempt) {
        SUCCESS_REFRESH_INTERVAL_MS
    } else {
        FAILURE_RETRY_INTERVAL_MS
    }

    private fun readResponse(connection: HttpsURLConnection): String {
        // contentLength, not contentLengthLong: the latter is API 24 and this app ships to 23,
        // where it is a NoSuchMethodError rather than a compile error. The Int cannot overflow
        // anything that matters here — a body past 2GB reports -1, which the streaming cap below
        // catches after 256KB anyway, and that cap is what actually bounds the read.
        val declaredLength = connection.contentLength
        require(declaredLength <= MAX_RESPONSE_BYTES) { "Russian-services list is too large" }
        val body = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(body.size() + count <= MAX_RESPONSE_BYTES) { "Russian-services list is too large" }
                body.write(buffer, 0, count)
            }
        }
        return body.toString(Charsets.UTF_8.name())
    }

    /**
     * Parses the provider's one-domain-per-line format strictly. Rejecting a whole malformed
     * response is safer than silently treating an error page or a changed format as routing
     * data. Source entries are ASCII/punycode host names, never URLs or routing directives.
     */
    internal fun parseDomainList(body: String): Set<String>? {
        val domains = linkedSetOf<String>()
        for (rawLine in body.lineSequence()) {
            val value = rawLine.trim()
            if (value.isEmpty() || value.startsWith('#')) continue
            if (value.length > 253 || value.any { it.isWhitespace() }) return null
            val ascii = runCatching {
                IDN.toASCII(value.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrNull() ?: return null
            val labels = ascii.split('.')
            if (labels.size < 2 || labels.any { !domainLabel.matches(it) }) return null
            domains += ascii
            if (domains.size > MAX_DOMAINS) return null
        }
        return domains.takeIf { it.isNotEmpty() }
    }
}
