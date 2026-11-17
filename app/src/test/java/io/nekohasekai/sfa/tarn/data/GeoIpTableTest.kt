package io.nekohasekai.sfa.tarn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Parses the database that actually ships in the APK, so a regenerated asset with a broken
 * header or a wrong table fails here instead of silently leaving the map unpinned.
 */
class GeoIpTableTest {

    private val table by lazy {
        val file = listOf(
            File("src/main/assets/geoip-country.bin"),
            File("app/src/main/assets/geoip-country.bin"),
        ).firstOrNull(File::isFile) ?: error("geoip asset not found")
        file.inputStream().use(GeoIpTable::read)
    }

    @Test
    fun `resolves well-known addresses`() {
        assertEquals("US", table.countryFor(ipv4("8.8.8.8")))
        assertEquals("DE", table.countryFor(ipv4("5.9.0.1")))
        assertEquals("RU", table.countryFor(ipv4("77.88.8.8")))
        assertEquals("AU", table.countryFor(ipv4("1.1.1.1")))
    }

    @Test
    fun `reserved ranges stay unknown`() {
        assertNull(table.countryFor(ipv4("192.168.1.1")))
        assertNull(table.countryFor(ipv4("203.0.113.1")))
    }

    private fun ipv4(text: String): Long = text.split('.').fold(0L) { value, part ->
        (value shl 8) or part.toLong()
    }
}
