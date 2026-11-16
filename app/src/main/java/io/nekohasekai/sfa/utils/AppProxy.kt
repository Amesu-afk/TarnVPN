package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * The app's own requests (update checks, profile fetches while connected) used to leave the
 * device with the app excluded from its own tunnel — by design, so the transport cannot loop
 * — which meant they bypassed the VPN entirely and were resolved by the system DNS.
 *
 * This object owns the loopback SOCKS listener that the generated config exposes back into
 * the tunnel: a random high port plus random per-install credentials, so the listener is
 * useless to any other app that finds it. The client side is [TunneledHttp], which speaks
 * SOCKS5 itself and hands the *hostname* to the core, so resolution happens inside the
 * tunnel through the configured resolver instead of on the device.
 *
 * Port and credentials are allocated once and persisted in the settings database (which is
 * excluded from backups). The config generator injects the inbound on every connect, so a
 * profile file written by an older build catches up on its first connection.
 */
object AppProxy {
    data class Descriptor(val port: Int, val username: String, val password: String)

    private const val TAG = "AppProxy"
    private const val PORT_RANGE_START = 20_000
    private const val PORT_RANGE_END = 60_000

    private val random = SecureRandom()

    /** Allocates the listener once. Safe to call from any thread, any number of times. */
    fun ensure() {
        if (descriptorOrNull() != null) return
        synchronized(this) {
            if (descriptorOrNull() != null) return
            val port = pickFreeLoopbackPort()
            if (port == 0) {
                Log.w(TAG, "no free loopback port for the app proxy")
                return
            }
            Settings.tarnAppProxyUser = randomToken(12)
            Settings.tarnAppProxyPassword = randomToken(24)
            Settings.tarnAppProxyPort = port
        }
    }

    /** The endpoint to inject into generated configs, or null before [ensure] ran. */
    fun descriptor(): Descriptor? = descriptorOrNull()

    /**
     * The endpoint for [HTTPClient], or null while no tunnel is up: with nothing to route
     * into, a proxied request would only fail. Down means direct — the pre-connect path.
     */
    fun descriptorIfTunnelStarted(): Descriptor? {
        if (BoxService.tunnelStatus != Status.Started) return null
        return descriptorOrNull()
    }

    private fun descriptorOrNull(): Descriptor? {
        val port = Settings.tarnAppProxyPort
        val username = Settings.tarnAppProxyUser
        val password = Settings.tarnAppProxyPassword
        if (port == 0 || username.isEmpty() || password.isEmpty()) return null
        return Descriptor(port, username, password)
    }

    private fun pickFreeLoopbackPort(): Int {
        repeat(10) {
            val port = PORT_RANGE_START + random.nextInt(PORT_RANGE_END - PORT_RANGE_START)
            val free = runCatching {
                ServerSocket().use { socket ->
                    socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }
                true
            }.getOrDefault(false)
            if (free) return port
        }
        return 0
    }

    private fun randomToken(length: Int): String {
        val bytes = ByteArray(length / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
