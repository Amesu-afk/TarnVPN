package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuDirectDomainUpdaterTest {

    @Test
    fun `accepts one-domain-per-line source data`() {
        assertEquals(
            setOf("gosuslugi.ru", "xn--90aijkdmaud0d.xn--p1ai", "yandex.net"),
            RuDirectDomainUpdater.parseDomainList(
                """
                # Russia outside
                gosuslugi.ru
                xn--90aijkdmaud0d.xn--p1ai
                yandex.net
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `rejects error pages and routing directives`() {
        assertNull(RuDirectDomainUpdater.parseDomainList("404: Not Found"))
        assertNull(RuDirectDomainUpdater.parseDomainList("DOMAIN-SUFFIX,gosuslugi.ru"))
        assertNull(RuDirectDomainUpdater.parseDomainList("# no domains"))
    }

    @Test
    fun `retries a failed fetch sooner than a successful validation`() {
        val successInterval = RuDirectDomainUpdater.refreshIntervalMs(lastAttempt = 100L, lastSuccess = 100L)
        val failureInterval = RuDirectDomainUpdater.refreshIntervalMs(lastAttempt = 100L, lastSuccess = 0L)

        assertEquals(12L * 60 * 60 * 1000, successInterval)
        assertEquals(15L * 60 * 1000, failureInterval)
    }
}
