package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the user types has to become something the core's suffix matcher can compare against the
 * name as it appears on the wire. Two failures this guards are silent rather than loud: a
 * Cyrillic domain stored unconverted would simply never match, and a bare TLD would quietly send
 * a large part of the internet around the tunnel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectDomainNormalizationTest {

    private fun norm(raw: String) = Settings.normalizeDirectDomain(raw)

    @Test
    fun `accepts what people actually paste`() {
        assertEquals("example.com", norm("example.com"))
        assertEquals("example.com", norm("  Example.COM  "))
        assertEquals("example.com", norm(".example.com"))
        assertEquals("example.com", norm("https://example.com/login?next=1"))
        assertEquals("example.com", norm("example.com:443"))
        assertEquals("pay.example.com", norm("https://pay.example.com"))
    }

    /**
     * The matcher compares against the punycode form, so a name typed in Cyrillic has to be
     * converted here or the entry would sit in the list looking correct and matching nothing.
     */
    @Test
    fun `converts an internationalised domain to punycode`() {
        assertEquals("xn--80abxggjd.xn--p1ai", norm("мойбанк.рф"))
        // The country TLD has to land on the exact spelling the built-in list uses, or a
        // hand-added .рф name and the built-in ".xn--p1ai" suffix would disagree.
        assertEquals("xn--p1ai", norm("сайт.рф")!!.substringAfterLast('.'))
    }

    /**
     * A bare TLD as a suffix would match every domain under it. "com" is not a mistake worth
     * accepting silently, so it is rejected and the dialog says so.
     */
    @Test
    fun `rejects input with no domain in it`() {
        assertNull(norm(""))
        assertNull(norm("   "))
        assertNull(norm("com"))
        assertNull(norm("https://"))
        assertNull(norm("what is this"))
    }
}
