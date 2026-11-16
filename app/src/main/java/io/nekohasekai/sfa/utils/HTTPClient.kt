package io.nekohasekai.sfa.utils

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HTTPClient(private val agent: String = userAgent) : Closeable {
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "TarnHttpDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /**
         * The default User-Agent for everything the app requests on its own behalf: update
         * checks, the Russian direct list, profile fetches. It used to carry the build number,
         * the core version and the UI locale, which is a free fingerprint for anyone watching
         * the network in exactly the places this app is meant for. Subscription providers are
         * the one exception and negotiate their format on a sing-box string instead — see
         * `SubscriptionFetcher.SHARE_LINKS_USER_AGENT`.
         */
        const val userAgent = "TarnVPN"
    }

    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var activeSocket: java.net.Socket? = null
    private val lifecycle = Any()
    private var closed = false

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val output = ByteArrayOutputStream()
        download(url, output, 16L * 1024 * 1024, 30_000L, headers)
        return output.toString(Charsets.UTF_8.name())
    }

    /** Separate size and whole-request budgets for text and APK downloads. */
    fun download(
        url: String,
        output: OutputStream,
        maxBytes: Long,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        require(maxBytes > 0 && timeoutMs in 1..600_000) { "Invalid HTTP limits" }
        // With a tunnel up, the app's own requests go back into it through the loopback
        // SOCKS listener instead of out the system network: the hostname is resolved by the
        // core (over the configured DoH), not by the device. While down, there is nothing to
        // route into and the direct path below is the only one that can work.
        val appProxy = AppProxy.descriptorIfTunnelStarted()
        if (appProxy != null && url.startsWith("https://", ignoreCase = true)) {
            TunneledHttp.get(
                url = URL(url),
                proxy = appProxy,
                agent = agent,
                headers = headers,
                output = output,
                maxBytes = maxBytes,
                timeoutMs = timeoutMs,
                progress = progress,
                track = { socket ->
                    synchronized(lifecycle) {
                        if (socket != null && closed) error("HTTP client is closed")
                        activeSocket = socket
                    }
                },
            )
            return
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var target = URL(url)
        val original = target
        repeat(6) {
            require(target.protocol == "https" || target.protocol == "http") { "Unsupported HTTP URL" }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            require(remaining > 0) { "HTTP request timed out" }
            val connection = target.openConnection() as HttpURLConnection
            synchronized(lifecycle) {
                check(!closed) { "HTTP client is closed" }
                check(active == null) { "HTTP client already has an active request" }
                active = connection
            }
            val timer = deadlines.schedule({ connection.disconnect() }, remaining, TimeUnit.MILLISECONDS)
            try {
                connection.connectTimeout = minOf(15_000L, remaining).toInt()
                connection.readTimeout = minOf(15_000L, remaining).toInt()
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", agent)
                connection.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) ->
                    val sameOrigin = target.protocol == original.protocol &&
                        target.host.equals(original.host, true) && target.port == original.port
                    if ((!name.equals("Authorization", true) && !name.equals("Cookie", true)) || sameOrigin) {
                        connection.setRequestProperty(name, value)
                    }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val next = URL(target, connection.getHeaderField("Location") ?: error("Missing redirect location"))
                    require(target.protocol != "https" || next.protocol == "https") { "Insecure HTTP redirect" }
                    target = next
                    return@repeat
                }
                require(status == HttpURLConnection.HTTP_OK) { "HTTP $status" }
                val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                require(total <= maxBytes) { "HTTP response is too large" }
                var count = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        check(System.nanoTime() < deadline) { "HTTP request timed out" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= maxBytes) { "HTTP response is too large" }
                        output.write(buffer, 0, read)
                        progress(count, total)
                    }
                }
                require(total < 0 || count == total) { "Incomplete HTTP response" }
                return
            } finally {
                timer.cancel(false)
                connection.disconnect()
                synchronized(lifecycle) { active = null }
            }
        }
        error("Too many HTTP redirects")
    }

    override fun close() {
        val connection = synchronized(lifecycle) {
            closed = true
            active
        }
        connection?.disconnect()
        synchronized(lifecycle) {
            activeSocket?.let { runCatching { it.close() } }
            activeSocket = null
        }
    }
}
