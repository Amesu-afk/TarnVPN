package io.nekohasekai.sfa.tarn.data

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress

/**
 * The country the selected server's address is registered in, from a database bundled in the
 * APK.
 *
 * The profile name is a claim; this is the address the tunnel actually dials. Resolving the
 * host is the only network step, and it only asks the system resolver for the address the
 * app is about to connect to anyway. The lookup itself is a binary search in a local file:
 * no address leaves the phone and no geolocation service is called.
 *
 * The table is IPv4-only. A host that resolves to IPv6 only returns null, and the home screen
 * falls back to the name-based guess rather than inventing a country. The address is the
 * entry point; a domain-fronted or CDN-hosted relay can be registered somewhere other than
 * where traffic finally leaves, which no offline table can see.
 */
object GeoIpCountry {
    private const val ASSET = "geoip-country.bin"
    private val loadLock = Any()

    @Volatile
    private var database: GeoIpTable? = null

    fun countryForHost(context: Context, host: String): String? {
        val address = resolveIPv4(host) ?: return null
        return countryForIp(context, address)
    }

    fun countryForIp(context: Context, address: ByteArray): String? {
        if (address.size != 4) return null
        val db = ensureLoaded(context) ?: return null
        val ip = ((address[0].toLong() and 0xFF) shl 24) or
            ((address[1].toLong() and 0xFF) shl 16) or
            ((address[2].toLong() and 0xFF) shl 8) or
            (address[3].toLong() and 0xFF)
        return db.countryFor(ip)
    }

    /**
     * All addresses the name resolves to are candidates; the first IPv4 one is the closest
     * match to what the core will dial. DNS failures are a miss, not an error.
     */
    private fun resolveIPv4(host: String): ByteArray? = runCatching {
        InetAddress.getAllByName(host)
            .firstOrNull { it.address.size == 4 }
            ?.address
    }.getOrNull()

    private fun ensureLoaded(context: Context): GeoIpTable? {
        database?.let { return it }
        synchronized(loadLock) {
            database?.let { return it }
            val loaded = runCatching {
                context.assets.open(ASSET).use(GeoIpTable::read)
            }.getOrNull()
            database = loaded
            return loaded
        }
    }
}

/**
 * The binary table itself, kept free of Android types so the real asset can be parsed by a
 * plain JVM test and a corrupt file fails there rather than on a phone.
 */
internal class GeoIpTable private constructor(
    private val starts: LongArray,
    private val codeIndices: ByteArray,
    private val codes: Array<String>,
) {
    /** Last range whose start is at or before [ip]; the ranges cover IPv4 without holes. */
    fun countryFor(ip: Long): String? {
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= ip) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (found < 0) return null
        val codeIndex = codeIndices[found].toInt() and 0xFF
        val code = codes.getOrNull(codeIndex) ?: return null
        return code.takeUnless { it == "ZZ" }
    }

    companion object {
        fun read(input: InputStream): GeoIpTable {
            DataInputStream(input.buffered()).use { stream ->
                val magic = ByteArray(4)
                stream.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == "TGI1") { "bad geoip asset" }

                val codeCount = stream.readUnsignedShort()
                require(codeCount in 1..255) { "bad geoip country count" }
                val codes = Array(codeCount) {
                    val bytes = ByteArray(2)
                    stream.readFully(bytes)
                    String(bytes, Charsets.US_ASCII)
                }

                val rangeCount = stream.readInt()
                require(rangeCount in 1..1_000_000) { "bad geoip range count" }
                val starts = LongArray(rangeCount) {
                    stream.readInt().toLong() and 0xFFFFFFFFL
                }
                val codeIndices = ByteArray(rangeCount)
                stream.readFully(codeIndices)
                return GeoIpTable(starts, codeIndices, codes)
            }
        }
    }
}
