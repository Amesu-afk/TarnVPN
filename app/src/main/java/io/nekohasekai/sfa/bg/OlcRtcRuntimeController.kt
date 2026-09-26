package io.nekohasekai.sfa.bg

import android.app.Service
import android.net.VpnService
import android.util.Log
import io.nekohasekai.mobile.LogWriter
import io.nekohasekai.mobile.Mobile
import io.nekohasekai.mobile.SocketProtector
import io.nekohasekai.sfa.utils.OlcRtcUri
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** Owns the optional olcRTC process that feeds a generated local SOCKS outbound. */
internal class OlcRtcRuntimeController(private val service: Service) {
    /**
     * The carrier's local SOCKS listener. Loopback is shared by every app on the device, so
     * a fixed, open port let any app use the tunnel, including apps excluded from it, and
     * made the VPN trivial to detect by scanning. Each carrier start therefore gets its own
     * ephemeral port and credentials; they live only in memory and in the in-memory config
     * handed to sing-box.
     */
    data class LocalEndpoint(val port: Int, val username: String, val password: String) {
        companion object {
            fun random(): LocalEndpoint = LocalEndpoint(
                port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort },
                username = randomToken(),
                password = randomToken(),
            )

            private val random = SecureRandom()

            private fun randomToken(): String = ByteArray(16)
                .also(random::nextBytes)
                .joinToString("") { "%02x".format(it) }
        }
    }

    private class Session(val source: String, val endpoint: LocalEndpoint)

    /**
     * The carrier this service wants running. It outlives the runtime itself: a carrier can
     * exit on its own (the conference ended, the reconnect budget ran out), and the session is
     * what [restartIfDead] brings back.
     */
    @Volatile
    private var session: Session? = null

    /** True while the selected profile is olcRTC, whether or not its carrier is up right now. */
    val hasSession: Boolean get() = session != null

    /** True when the selected profile is olcRTC and its carrier has exited on its own. */
    fun needsRestart(): Boolean = session != null && !Mobile.isRunning()

    /**
     * Keeps the current olcRTC process when a service reload uses the same source URI. The
     * carrier owns its reconnect lifecycle; restarting it for an unrelated sing-box reload
     * interrupts a healthy WebRTC session and can fail the replacement handshake.
     *
     * Returns the endpoint sing-box must dial, or null when the profile is not olcRTC.
     */
    fun startForProfile(configFile: File): LocalEndpoint? {
        val sourceFile = File(configFile.parentFile, configFile.nameWithoutExtension + ".vless")
        val source = sourceFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (!OlcRtcUri.isUri(source)) {
            stop()
            return null
        }
        session?.let { current ->
            if (shouldReuseRunningProfile(current.source, source, Mobile.isRunning())) return current.endpoint
        }
        val profile = OlcRtcUri.parse(source)
        stop()

        val next = Session(source, LocalEndpoint.random())
        session = next
        try {
            launch(profile, next.endpoint)
        } catch (exception: Exception) {
            if (session === next) session = null
            throw exception
        }
        return next.endpoint
    }

    /**
     * Brings an exited carrier back on the same local endpoint, so the running sing-box config
     * stays valid and no reload is needed. Returns false when there was nothing to restart.
     * Throws when the carrier could not come back; the session is kept for the next attempt.
     */
    fun restartIfDead(): Boolean {
        val current = session ?: return false
        if (Mobile.isRunning()) return false
        launch(OlcRtcUri.parse(current.source), current.endpoint)
        if (session !== current) {
            // Stopped or replaced while this start was waiting for the carrier.
            stopRuntime()
            return false
        }
        return true
    }

    private fun launch(profile: OlcRtcUri.Profile, endpoint: LocalEndpoint) {
        // Manager QR parsing requires client_id. The original v1 URI has no such field and the
        // legacy runtime intentionally generates its own DeviceID when this value is empty.
        val clientId = profile.clientId.orEmpty()
        val sensitiveValues = listOf(
            profile.sourceUri,
            profile.room,
            profile.key,
            clientId,
            endpoint.username,
            endpoint.password,
        )
        try {
            synchronized(logTail) { logTail.clear() }
            Mobile.setLogWriter(LogWriter { message -> recordLog(message, sensitiveValues) })
            Mobile.setProviders()
            Mobile.setTransport(profile.transport)
            Mobile.setDNS(profile.dnsServer ?: DEFAULT_DNS)
            Mobile.setLivenessOptions(
                livenessIntervalMillis(profile.keepaliveSeconds),
                DEFAULT_LIVENESS_TIMEOUT_MS,
                DEFAULT_LIVENESS_FAILURES,
            )
            configureTransport(profile)
            if (service is VpnService) {
                Mobile.setProtector(SocketProtector { descriptor -> service.protect(descriptor.toInt()) })
            } else {
                Mobile.setProtector(null)
            }
            Mobile.startWithTransport(
                profile.provider,
                profile.transport,
                profile.room,
                clientId,
                profile.key,
                endpoint.port.toLong(),
                endpoint.username,
                endpoint.password,
            )
            Mobile.waitReady(
                if (profile.provider == "jitsi" || profile.provider == "telemost" || profile.provider == "wbstream") {
                    SLOW_PROVIDER_READY_TIMEOUT_MS
                } else {
                    READY_TIMEOUT_MS
                },
            )
        } catch (exception: Exception) {
            runCatching { Mobile.stop() }
            throw diagnosticFailure(exception, sensitiveValues)
        }
    }

    fun stop() {
        session = null
        stopRuntime()
    }

    private fun stopRuntime() {
        if (Mobile.isRunning()) Mobile.stop()
        // The mobile API is process-global. Do not retain a stopped Service through its callback
        // or apply its VPN protection to a later proxy-only service.
        Mobile.setLogWriter(LogWriter { _ -> })
        Mobile.setProtector(null)
    }

    /**
     * Keeps the last runtime log lines so a failed connection can say what the carrier was doing.
     * The runtime logs the chosen bridge path and whether a peer answered, and that is the
     * difference between "nobody is in the room" and "the room works but the tunnel stalled" —
     * indistinguishable from the error text alone.
     */
    private fun recordLog(message: String, sensitiveValues: List<String>) {
        val line = redactRuntimeLog(message.trim(), sensitiveValues)
        if (line.isEmpty()) return
        Log.i(LOG_TAG, line)
        synchronized(logTail) {
            logTail.addLast(line)
            while (logTail.size > LOG_TAIL_LINES) logTail.removeFirst()
        }
    }

    private fun diagnosticFailure(cause: Exception, sensitiveValues: List<String>): Exception {
        val tail = synchronized(logTail) { logTail.toList() }
        val message = redactRuntimeLog(cause.message.orEmpty(), sensitiveValues)
            .ifBlank { cause.javaClass.simpleName }
        if (tail.isEmpty()) return IllegalStateException(message)
        return IllegalStateException(
            message + "\n" + "\n" + tail.joinToString(separator = "\n"),
        )
    }

    private fun configureTransport(profile: OlcRtcUri.Profile) {
        fun option(name: String, default: Long): Long = profile.options[name]?.toLong() ?: default
        if (profile.transport == "vp8channel") {
            Mobile.setVP8Options(option("vp8-fps", 60), option("vp8-batch", 8))
        }
    }

    private val logTail = ArrayDeque<String>()

    internal companion object {
        const val LOG_TAG = "olcRTC"
        const val LOG_TAIL_LINES = 12
        const val DEFAULT_DNS = "8.8.8.8:53"
        const val DEFAULT_LIVENESS_INTERVAL_MS = 10_000L
        const val DEFAULT_LIVENESS_TIMEOUT_MS = 15_000L
        const val DEFAULT_LIVENESS_FAILURES = 4L
        const val READY_TIMEOUT_MS = 20_000L
        const val SLOW_PROVIDER_READY_TIMEOUT_MS = 45_000L
        const val REDACTED_VALUE = "<redacted>"

        fun shouldReuseRunningProfile(activeSource: String?, source: String, running: Boolean): Boolean = running && activeSource == source

        /**
         * Points the profile's generated SOCKS outbound at this start's [endpoint]. The stored
         * profile keeps the placeholder [OlcRtcUri.SOCKS_PORT] and no credentials, so nothing
         * secret is ever written to disk. A null endpoint (not an olcRTC profile) is a no-op.
         */
        fun applyLocalEndpoint(config: String, endpoint: LocalEndpoint?): String {
            if (endpoint == null) return config
            val root = JSONObject(config)
            val outbounds = root.optJSONArray("outbounds") ?: return config
            var patched = false
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                if (outbound.optString("type") != "socks") continue
                if (outbound.optString("server") != "127.0.0.1") continue
                if (outbound.optInt("server_port") != OlcRtcUri.SOCKS_PORT) continue
                outbound.put("server_port", endpoint.port)
                outbound.put("username", endpoint.username)
                outbound.put("password", endpoint.password)
                patched = true
            }
            // Without the patch sing-box would dial a port nobody listens on: fail loudly.
            check(patched) { "olcRTC profile has no local SOCKS outbound" }
            return root.toString()
        }

        /** The mobile runtime is a singleton, so restore the legacy 10-second default per start. */
        fun livenessIntervalMillis(keepaliveSeconds: Int?): Long = keepaliveSeconds?.toLong()?.times(1_000L) ?: DEFAULT_LIVENESS_INTERVAL_MS

        /** Keeps user-provided connection data out of logcat and surfaced startup diagnostics. */
        fun redactRuntimeLog(message: String, sensitiveValues: List<String>): String = sensitiveValues
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .fold(message) { line, value ->
                line.replace(value, REDACTED_VALUE, ignoreCase = true)
            }
    }
}
