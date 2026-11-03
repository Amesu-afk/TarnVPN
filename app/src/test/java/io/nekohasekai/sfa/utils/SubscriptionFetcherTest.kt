package io.nekohasekai.sfa.utils

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionFetcherTest {
    private val config = """{"outbounds":[{"type":"vless","server":"example.com"}]}"""
    private val links = "vless://uuid@example.com:443?security=tls#test"

    @Test
    fun `retries sing-box JSON as links and parses the result`() {
        val encoded = Base64.encodeToString(links.toByteArray(), Base64.NO_WRAP)
        val agents = mutableListOf<String?>()
        val selected = SubscriptionFetcher.chooseResponse { agent ->
            agents.add(agent)
            if (agent == null) config else encoded
        }

        assertEquals(listOf(null, "sing-box/1.12.0"), agents)
        val parsed = VlessImporter.inspectSubscriptionBody(selected)
        assertEquals(1, parsed.parsedCount)
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun `already supported response is fetched only once`() {
        var calls = 0
        val selected = SubscriptionFetcher.chooseResponse {
            calls++
            links
        }
        assertEquals(1, calls)
        assertEquals(links, selected)
    }

    @Test
    fun `unavailable or incompatible fallback preserves original error context`() {
        for (fallback in listOf("<html>login</html>", """{"outbounds":[]}""")) {
            val selected = SubscriptionFetcher.chooseResponse { if (it == null) config else fallback }
            assertEquals(config, selected)
            val error = assertThrows(IllegalArgumentException::class.java) {
                VlessImporter.inspectSubscriptionBody(selected)
            }
            assertTrue(error.message!!.contains("sing-box JSON"))
        }
        assertEquals(config, SubscriptionFetcher.chooseResponse {
            if (it == null) config else throw IOException("offline")
        })
    }

    @Test
    fun `unrelated JSON is not re-requested`() {
        var calls = 0
        val body = """{"message":"unavailable"}"""
        assertEquals(body, SubscriptionFetcher.chooseResponse { calls++; body })
        assertEquals(1, calls)
    }
}
