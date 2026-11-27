package io.nekohasekai.sfa.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class HTTPClientTest {
    @Test
    fun `plaintext HTTP is rejected before opening a connection`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.close()
        }
        server.start()
        try {
            HTTPClient("test").use { client ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    client.getString("http://127.0.0.1:${server.address.port}/")
                }
                assertEquals("HTTPS required", error.message)
                assertEquals(0, requests)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stalled headers obey request timeout`() {
        HttpsTestServer().use { server ->
            server.createContext("/") { exchange ->
                Thread.sleep(300)
                exchange.close()
            }
            HTTPClient("test").use { client ->
                assertThrows(Exception::class.java) {
                    client.download(server.url("/"), ByteArrayOutputStream(), 100, 50)
                }
            }
        }
    }

    @Test
    fun `chunked body cannot bypass size limit`() {
        HttpsTestServer().use { server ->
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("oversize".toByteArray()) }
            }
            HTTPClient("test").use { client ->
                assertThrows(IllegalArgumentException::class.java) {
                    client.download(server.url("/"), ByteArrayOutputStream(), 3, 1000)
                }
            }
        }
    }

    @Test
    fun `redirect to another port does not forward credentials`() {
        HttpsTestServer().use { target ->
            target.createContext("/") { exchange ->
                val body = (exchange.requestHeaders.getFirst("Authorization") ?: "absent").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            HttpsTestServer().use { origin ->
                origin.createContext("/") { exchange ->
                    exchange.responseHeaders.add("Location", target.url("/"))
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                HTTPClient("test").use { client ->
                    assertEquals(
                        "absent",
                        client.getString(origin.url("/"), mapOf("Authorization" to "secret")),
                    )
                }
            }
        }
    }

    @Test
    fun `bounded downloads accept exact limit and reject excess`() {
        HttpsTestServer().use { server ->
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 4)
                exchange.responseBody.use { it.write("test".toByteArray()) }
            }
            HTTPClient("test").use { client ->
                val url = server.url("/")
                val output = ByteArrayOutputStream()
                client.download(url, output, 4, 1000)
                assertEquals("test", output.toString("UTF-8"))
                assertThrows(IllegalArgumentException::class.java) {
                    client.download(url, ByteArrayOutputStream(), 3, 1000)
                }
            }
        }
    }

    @Test
    fun `redirect loops are bounded`() {
        HttpsTestServer().use { server ->
            server.createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", "/")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            HTTPClient("test").use { client ->
                assertThrows(IllegalStateException::class.java) {
                    client.getString(server.url("/"))
                }
            }
        }
    }

    @Test
    fun `HTTPS redirect cannot downgrade to plaintext HTTP`() {
        val plaintext = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        plaintext.createContext("/") { exchange ->
            requests++
            exchange.close()
        }
        plaintext.start()
        HttpsTestServer().use { origin ->
            origin.createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${plaintext.address.port}/")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            try {
                HTTPClient("test").use { client ->
                    assertThrows(IllegalArgumentException::class.java) {
                        client.getString(origin.url("/"))
                    }
                }
                assertEquals(0, requests)
            } finally {
                plaintext.stop(0)
            }
        }
    }
}

private class HttpsTestServer : Closeable {
    private val password = "changeit".toCharArray()
    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        val resource = sequenceOf(
            File("src/test/resources/http-test-localhost.p12"),
            File("app/src/test/resources/http-test-localhost.p12"),
        ).firstOrNull { it.isFile }
        requireNotNull(resource) { "Missing HTTPS test keystore" }
        FileInputStream(resource).use { load(it, password) }
    }
    private val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore, password)
    }.keyManagers
    private val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore)
    }.trustManagers
    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers, trustManagers, null)
    }
    private val previousSslContext = SSLContext.getDefault()
    private val previousHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        setHttpsConfigurator(HttpsConfigurator(sslContext))
    }

    init {
        SSLContext.setDefault(sslContext)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        server.start()
    }

    fun createContext(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange -> handler(exchange) }
    }

    fun url(path: String): String = "https://localhost:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        SSLContext.setDefault(previousSslContext)
        HttpsURLConnection.setDefaultHostnameVerifier(previousHostnameVerifier)
    }
}
