package io.nekohasekai.sfa.tarn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure Kotlin — no Android classes involved, so this runs on the plain JVM.
 *
 * The point of most of these is the *absence* of a flag. A missing flag is a shrug; a
 * confident wrong one ("My Server" flying Malaysia's) reads as information about where
 * the traffic goes, which is the one thing it must never get wrong.
 */
class CountryDetectorTest {

    @Test
    fun `detects country names in both languages`() {
        assertEquals("DE", CountryDetector.detect("Германия, Берлин", null))
        assertEquals("DE", CountryDetector.detect("Germany Frankfurt", null))
        assertEquals("NL", CountryDetector.detect("Нидерланды 01", null))
        assertEquals("TR", CountryDetector.detect("Türkiye", null))
    }

    /**
     * Providers commonly prefix a profile with its flag, and that emoji is the most explicit
     * signal in the whole name — the coffee one is here so any pair of regional indicators
     * is not mistaken for one.
     */
    @Test
    fun `reads the flag emoji in front of a profile name`() {
        assertEquals("DE", CountryDetector.detect("🇩🇪 Германия, Берлин", null))
        assertEquals("JP", CountryDetector.detect("🇯🇵 Tokyo", null))
        assertNull(CountryDetector.detect("🙂 Fast node", null))
    }

    @Test
    fun `detects multi-word names across any separator`() {
        assertEquals("US", CountryDetector.detect("United States — East", null))
        assertEquals("HK", CountryDetector.detect("Hong, Kong", null))
        assertEquals("HK", CountryDetector.detect("Hong-Kong", null))
        assertEquals("ZA", CountryDetector.detect("South Africa 2", null))
    }

    /**
     * Providers commonly name a node after its city and nothing else. "TestMiami-Test" is
     * the case that showed up as "Страна не определена" on the home screen; the CamelCase
     * split is what turns the glued prefix back into words the city table can match.
     */
    @Test
    fun `detects a hosting city when the name has no country`() {
        assertEquals("US", CountryDetector.detect("TestMiami-Test", null))
        assertEquals("US", CountryDetector.detect("TestMiami", "TestMiami"))
        assertEquals("US", CountryDetector.detect("New York 01", null))
        assertEquals("DE", CountryDetector.detect("Frankfurt am Main", null))
        assertEquals("NL", CountryDetector.detect("Amsterdam-02", null))
        assertEquals("JP", CountryDetector.detect("Tokyo", null))
    }

    /** A city inside a longer word is not a location signal, just like a country inside one. */
    @Test
    fun `does not match a city inside a longer word`() {
        assertNull(CountryDetector.detect("Miamiville relay", null))
        assertNull(CountryDetector.detect("Berliner filter", null))
    }

    @Test
    fun `reads a leading code from a structured tag`() {
        assertEquals("DE", CountryDetector.detect("Fast node", "de-ber-01"))
        assertEquals("US", CountryDetector.detect("Fast node", "us-nyc-01"))
    }

    @Test
    fun `reads a code from any position of a structured tag`() {
        assertEquals("NL", CountryDetector.detect("Fast node", "vpn-nl-ams"))
    }

    @Test
    fun `reads a leading code from a display name`() {
        assertEquals("DE", CountryDetector.detect("DE Berlin", null))
        assertEquals("NL", CountryDetector.detect("NL 01", null))
    }

    /**
     * The regression this file exists for: a two-letter English word inside prose used to be
     * accepted as a country code from any position, so ordinary names grew flags of countries
     * they had nothing to do with.
     */
    @Test
    fun `does not read country codes out of ordinary words`() {
        // Leading, but capitalised as a word rather than written as a code.
        assertNull(CountryDetector.detect("My Server", null))
        assertNull(CountryDetector.detect("It works", null))
        // Not leading at all. Berlin is a city, not the word "in" being read as India.
        assertEquals("DE", CountryDetector.detect("Server in Berlin", null))
        assertNull(CountryDetector.detect("Backup is fast", null))
    }

    /** The capitals are what separate a deliberate code from a two-letter English word. */
    @Test
    fun `a lowercase leading word is not treated as a code`() {
        assertNull(CountryDetector.detect("no name", null))
        assertEquals("NO", CountryDetector.detect("NO Oslo", null))
    }

    @Test
    fun `does not match a country name inside a longer word`() {
        // "chinatown" is not China, "iran" inside "iranian"-style compounds likewise.
        assertNull(CountryDetector.detect("Chinatown relay", null))
    }

    @Test
    fun `returns null when nothing identifies a country`() {
        assertNull(CountryDetector.detect("Fast node 42", null))
        assertNull(CountryDetector.detect("", null))
    }

    @Test
    fun `prefers the display name over the tag`() {
        assertEquals("DE", CountryDetector.detect("Германия", "us-nyc-01"))
    }

    @Test
    fun `falls back to the tag when the name says nothing`() {
        assertEquals("JP", CountryDetector.detect("Node 7", "japan-01"))
    }
}
