package io.nekohasekai.sfa.utils

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

class HTTPClientTest {
    @Test
    fun `stalled headers obey request timeout`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(300)
            exchange.close()
        }
        server.start()
        try {
            HTTPClient("test").use { client ->
                assertThrows(Exception::class.java) {
                    client.download("http://127.0.0.1:${server.address.port}/", ByteArrayOutputStream(), 100, 50)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chunked body cannot bypass size limit`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("oversize".toByteArray()) }
        }
        server.start()
        try {
            HTTPClient("test").use { client ->
                assertThrows(IllegalArgumentException::class.java) {
                    client.download("http://127.0.0.1:${server.address.port}/", ByteArrayOutputStream(), 3, 1000)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `redirect to another port does not forward credentials`() {
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { exchange ->
            val body = (exchange.requestHeaders.getFirst("Authorization") ?: "absent").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        target.start()
        origin.start()
        try {
            HTTPClient("test").use { client ->
                assertEquals("absent", client.getString("http://127.0.0.1:${origin.address.port}/", mapOf("Authorization" to "secret")))
            }
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `bounded downloads accept exact limit and reject excess`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("test".toByteArray()) }
        }
        server.start()
        try {
            HTTPClient("test").use { client ->
                val url = "http://127.0.0.1:${server.address.port}/"
                val output = ByteArrayOutputStream()
                client.download(url, output, 4, 1000)
                assertEquals("test", output.toString("UTF-8"))
                assertThrows(IllegalArgumentException::class.java) {
                    client.download(url, ByteArrayOutputStream(), 3, 1000)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `redirect loops are bounded`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            HTTPClient("test").use { client ->
                assertThrows(IllegalStateException::class.java) {
                    client.getString("http://127.0.0.1:${server.address.port}/")
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
