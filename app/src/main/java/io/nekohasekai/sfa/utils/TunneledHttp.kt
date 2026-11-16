package io.nekohasekai.sfa.utils

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * A minimal HTTPS GET over SOCKS5, for the app's own requests once a tunnel is up.
 *
 * The stock path in [HTTPClient] resolves the target through the system resolver before it
 * talks to a proxy, which keeps leaking the hostname to the local network — exactly what
 * routing the app through its own tunnel is supposed to stop. This client hands the
 * unresolved host to the core's SOCKS listener (`ATYP=domain`), so the lookup happens inside
 * the tunnel through the configured DNS, and then wraps the same socket in TLS with the
 * original host as SNI.
 *
 * Deliberately narrow: GET only, HTTP/1.1, no compression, one request per connection
 * (`Connection: close`). Redirect handling and the size/deadline limits mirror [HTTPClient]
 * so callers cannot tell the two paths apart.
 */
internal object TunneledHttp {
    private const val MAX_REDIRECTS = 6
    private const val MAX_LINE_BYTES = 16 * 1024
    private const val MAX_HEADER_COUNT = 64
    private const val BUFFER_SIZE = 32 * 1024
    private const val LOOPBACK = "127.0.0.1"

    fun get(
        url: URL,
        proxy: AppProxy.Descriptor,
        agent: String,
        headers: Map<String, String>,
        output: OutputStream,
        maxBytes: Long,
        timeoutMs: Long,
        progress: (Long, Long) -> Unit,
        track: (Socket?) -> Unit,
    ) {
        require(url.protocol == "https") { "Tunneled requests must be HTTPS" }
        require(maxBytes > 0 && timeoutMs in 1..600_000) { "Invalid HTTP limits" }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var target = url
        var attempt = 0
        while (true) {
            if (attempt++ >= MAX_REDIRECTS) error("Too many HTTP redirects")
            val remaining = remainingMs(deadline)
            require(remaining > 0) { "HTTP request timed out" }
            val socket = Socket()
            track(socket)
            try {
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(LOOPBACK), proxy.port),
                    minOf(15_000L, remaining).toInt(),
                )
                socket.soTimeout = minOf(15_000L, remaining).toInt()
                socket.tcpNoDelay = true
                socks5Connect(socket, proxy, target.host, portOf(target))
                val tls = startTls(socket, target, deadline)
                val tlsInput = BufferedInputStream(tls.inputStream)
                writeRequest(tls.outputStream, target, url, agent, headers)
                val response = readResponseHead(tlsInput, deadline)
                if (response.status in REDIRECT_CODES) {
                    val location = response.headers["location"]
                        ?: error("Missing redirect location")
                    val next = URL(target, location)
                    require(next.protocol == "https") { "Insecure HTTP redirect" }
                    target = next
                    closeQuietly(tls)
                    continue
                }
                require(response.status == 200) { "HTTP ${response.status}" }
                readBody(tlsInput, response, output, maxBytes, deadline, progress)
                closeQuietly(tls)
                return
            } finally {
                closeQuietly(socket)
                track(null)
            }
        }
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private fun socks5Connect(socket: Socket, proxy: AppProxy.Descriptor, host: String, port: Int) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(socket.getOutputStream())
        // Offer username/password and no-auth; the core is configured with users, so it must
        // answer 0x02. A 0x00 here means the listener is unauthenticated — not ours — and the
        // request must not go through it.
        out.write(byteArrayOf(5, 2, 2, 0))
        out.flush()
        check(input.readUnsignedByte() == 5) { "Not a SOCKS5 proxy" }
        when (input.readUnsignedByte()) {
            2 -> {
                val user = proxy.username.toByteArray(Charsets.UTF_8)
                val pass = proxy.password.toByteArray(Charsets.UTF_8)
                require(user.isNotEmpty() && user.size <= 255)
                require(pass.isNotEmpty() && pass.size <= 255)
                out.writeByte(1)
                out.writeByte(user.size)
                out.write(user)
                out.writeByte(pass.size)
                out.write(pass)
                out.flush()
                check(input.readUnsignedByte() == 1) { "SOCKS auth version mismatch" }
                check(input.readUnsignedByte() == 0) { "SOCKS authentication rejected" }
            }

            0 -> error("SOCKS listener is unauthenticated")
            else -> error("Unsupported SOCKS auth method")
        }
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        require(hostBytes.size in 1..255) { "Host too long" }
        out.writeByte(5)
        out.writeByte(1)
        out.writeByte(0)
        out.writeByte(3) // ATYP: domain name, so the core resolves it, not the device
        out.writeByte(hostBytes.size)
        out.write(hostBytes)
        out.writeByte(port shr 8)
        out.writeByte(port and 0xFF)
        out.flush()
        check(input.readUnsignedByte() == 5) { "SOCKS connect reply malformed" }
        check(input.readUnsignedByte() == 0) { "SOCKS connect failed" }
        input.readUnsignedByte() // reserved
        when (val atyp = input.readUnsignedByte()) {
            1 -> skipFully(input, 4)
            3 -> skipFully(input, input.readUnsignedByte())
            4 -> skipFully(input, 16)
            else -> error("SOCKS address type unsupported: $atyp")
        }
        skipFully(input, 2)
    }

    private fun startTls(socket: Socket, target: URL, deadline: Long): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(
            socket,
            target.host,
            portOf(target),
            true,
        ) as SSLSocket
        tls.soTimeout = minOf(15_000L, remainingMs(deadline)).toInt()
        tls.startHandshake()
        return tls
    }

    private fun writeRequest(
        out: OutputStream,
        target: URL,
        original: URL,
        agent: String,
        headers: Map<String, String>,
    ) {
        val path = buildString {
            append(target.path.ifEmpty { "/" })
            target.query?.let { append('?').append(it) }
        }
        val hostHeader = if (portOf(target) == 443) {
            target.host
        } else {
            "${target.host}:${portOf(target)}"
        }
        val request = StringBuilder()
        request.append("GET ").append(path).append(" HTTP/1.1\r\n")
        request.append("Host: ").append(sanitizeHeader(hostHeader)).append("\r\n")
        request.append("User-Agent: ").append(sanitizeHeader(agent)).append("\r\n")
        request.append("Accept-Encoding: identity\r\n")
        request.append("Connection: close\r\n")
        val sameOrigin = target.protocol == original.protocol &&
            target.host.equals(original.host, true) && target.port == original.port
        headers.forEach { (name, value) ->
            if ((!name.equals("Authorization", true) && !name.equals("Cookie", true)) || sameOrigin) {
                request.append(sanitizeHeader(name)).append(": ")
                    .append(sanitizeHeader(value)).append("\r\n")
            }
        }
        request.append("\r\n")
        out.write(request.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private class Response(
        val status: Int,
        val headers: Map<String, String>,
        val contentLength: Long,
        val chunked: Boolean,
    )

    private fun readResponseHead(input: InputStream, deadline: Long): Response {
        val statusLine = readLine(input, deadline)
        val parts = statusLine.split(' ', limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "Bad HTTP status line" }
        val status = parts[1].toIntOrNull() ?: error("Bad HTTP status")
        val headers = HashMap<String, String>()
        repeat(MAX_HEADER_COUNT) {
            val line = readLine(input, deadline)
            if (line.isEmpty()) {
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                val chunked = headers["transfer-encoding"]?.lowercase(Locale.US) == "chunked"
                return Response(status, headers, contentLength, chunked)
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }
        error("Too many HTTP headers")
    }

    private fun readBody(
        input: InputStream,
        response: Response,
        output: OutputStream,
        maxBytes: Long,
        deadline: Long,
        progress: (Long, Long) -> Unit,
    ) {
        require(response.contentLength <= maxBytes) { "HTTP response is too large" }
        var count = 0L
        if (response.chunked) {
            while (true) {
                val size = readLine(input, deadline).substringBefore(';').trim().toLongOrNull(16)
                    ?: error("Bad chunk size")
                if (size == 0L) {
                    while (readLine(input, deadline).isNotEmpty()) {
                        // Trailer section.
                    }
                    break
                }
                require(size > 0) { "Bad chunk size" }
                count = copyExact(input, output, size, count, maxBytes, deadline)
                progress(count, -1L)
                check(readLine(input, deadline).isEmpty()) { "Bad chunk terminator" }
            }
        } else if (response.contentLength >= 0) {
            count = copyExact(input, output, response.contentLength, count, maxBytes, deadline)
            progress(count, response.contentLength)
        } else {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                check(remainingMs(deadline) > 0) { "HTTP request timed out" }
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maxBytes) { "HTTP response is too large" }
                output.write(buffer, 0, read)
                progress(count, -1L)
            }
        }
        require(response.chunked || response.contentLength < 0 || count == response.contentLength) {
            "Incomplete HTTP response"
        }
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        length: Long,
        startCount: Long,
        maxBytes: Long,
        deadline: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var count = startCount
        while (copied < length) {
            check(remainingMs(deadline) > 0) { "HTTP request timed out" }
            val chunk = minOf(buffer.size.toLong(), length - copied).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) throw EOFException("Incomplete HTTP response")
            output.write(buffer, 0, read)
            copied += read
            count += read
            require(count <= maxBytes) { "HTTP response is too large" }
        }
        return count
    }

    private fun readLine(input: InputStream, deadline: Long): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            check(remainingMs(deadline) > 0) { "HTTP request timed out" }
            val byte = input.read()
            if (byte == -1) throw EOFException("Unexpected end of stream")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) {
                buffer.write(byte)
                if (buffer.size() > MAX_LINE_BYTES) error("HTTP header line too long")
            }
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private fun skipFully(input: InputStream, count: Int) {
        repeat(count) {
            if (input.read() == -1) throw EOFException("Unexpected end of stream")
        }
    }

    private fun sanitizeHeader(value: String): String = value.replace("\r", "").replace("\n", "")

    private fun portOf(url: URL): Int = if (url.port == -1) 443 else url.port

    private fun remainingMs(deadline: Long): Long =
        (deadline - System.nanoTime()) / 1_000_000L

    private fun closeQuietly(target: java.io.Closeable) {
        runCatching { target.close() }
    }
}
