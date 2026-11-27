package io.nekohasekai.sfa.qrs

object QRSConstants {
    const val OFFICIAL_URL_PREFIX = "https://qrss.netlify.app/#"

    const val DEFAULT_FRAME_COUNT = 200
    const val BITMAP_BUFFER_SIZE = 30
    const val RECOVERY_FACTOR = 1.3

    // FPS settings
    const val DEFAULT_FPS = 10
    const val MIN_FPS = 1
    const val MAX_FPS = 60

    // Slice Size settings
    const val DEFAULT_SLICE_SIZE = 500
    const val MIN_SLICE_SIZE = 100
    const val MAX_SLICE_SIZE = 1500

    // Untrusted QR frames must not be able to request unbounded allocations.
    const val MAX_DEGREE = 1000
    const val MAX_TOTAL_BLOCKS = 100_000
    const val MAX_COMPRESSED_SIZE = 16 * 1024 * 1024
    const val MAX_DECOMPRESSED_SIZE = 16 * 1024 * 1024

    fun calculateRequiredFrames(dataSize: Int, sliceSize: Int): Int {
        val k = (dataSize + sliceSize - 1) / sliceSize
        if (k == 0) return 1
        return (k * RECOVERY_FACTOR).toInt().coerceAtLeast(k + 5)
    }
}
