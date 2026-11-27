package io.nekohasekai.sfa.qrs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.CRC32
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QRSDecoderTest {
    @Test
    fun `malformed frame headers are rejected without untrusted allocations`() {
        val hugeDegree = ByteArray(20).apply { writeIntLE(0, Int.MAX_VALUE) }
        assertNull(QRSDecoder().processFrame(hugeDegree))

        val malformed = listOf(
            frame(totalBlocks = Int.MAX_VALUE, compressedSize = 100),
            frame(totalBlocks = 1, compressedSize = Int.MAX_VALUE),
            frame(totalBlocks = 1, compressedSize = 100, indices = intArrayOf(1)),
            frame(totalBlocks = 2, compressedSize = 501, indices = intArrayOf(0, 0)),
            frame(totalBlocks = 2, compressedSize = 500, indices = intArrayOf(0, 1)),
            frame(totalBlocks = 1, compressedSize = 100, dataSize = QRSConstants.MIN_SLICE_SIZE - 1),
            ByteArray(QRSDecoder.MAX_FRAME_BYTES + 1),
        )
        malformed.forEach { payload ->
            assertFalse(QRSDecoder.isValidFrame(payload))
            assertNull(QRSDecoder().processFrame(payload))
        }
    }

    @Test
    fun `inconsistent dataset metadata returns an error instead of throwing`() {
        val decoder = QRSDecoder()
        val first = decoder.processFrame(
            frame(totalBlocks = 2, compressedSize = 501, indices = intArrayOf(0, 1), checksum = 7),
        )
        assertTrue(first != null && !first.isComplete && first.error == null)

        val inconsistent = decoder.processFrame(
            frame(totalBlocks = 3, compressedSize = 1001, indices = intArrayOf(0, 1, 2), checksum = 7),
        )
        assertTrue(inconsistent != null && inconsistent.error == "Inconsistent QRS frame")
    }

    @Test
    fun `codec rejects out of range and excessive state metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            LubyCodec.EncodedBlock(1, intArrayOf(1), 1, 100, 0, ByteArray(500))
        }
        val excessiveBlocks = QRSConstants.MAX_TOTAL_BLOCKS + 1
        assertThrows(IllegalArgumentException::class.java) {
            LubyCodec.DecodingState(
                sliceSize = QRSConstants.DEFAULT_SLICE_SIZE,
                totalBlocks = excessiveBlocks,
                compressedSize = excessiveBlocks * QRSConstants.DEFAULT_SLICE_SIZE,
                checksum = 0,
            )
        }
    }

    @Test
    fun `valid systematic frames decode to the original bytes`() {
        val original = ByteArray(600) { (it * 31).toByte() }
        val compressed = compress(original)
        val decoder = QRSDecoder()
        val result = feedSystematicFrames(decoder, original, compressed)

        assertTrue(result.isComplete)
        assertNull(result.error)
        assertArrayEquals(original, result.data)
    }

    @Test
    fun `decompression is bounded before output is returned`() {
        val original = ByteArray(QRSConstants.MAX_DECOMPRESSED_SIZE + 1)
        val compressed = compress(original)
        val decoder = QRSDecoder()
        val result = feedSystematicFrames(decoder, original, compressed)

        assertTrue(result.isComplete)
        assertNull(result.data)
        assertTrue(result.error != null)
    }

    private fun feedSystematicFrames(decoder: QRSDecoder, original: ByteArray, compressed: ByteArray): QRSDecoder.DecodeProgress {
        val sliceSize = QRSConstants.DEFAULT_SLICE_SIZE
        val totalBlocks = (compressed.size + sliceSize - 1) / sliceSize
        val crc = CRC32().apply { update(original) }
        val checksum = (crc.value xor totalBlocks.toLong()) and 0xFFFFFFFFL
        var result: QRSDecoder.DecodeProgress? = null
        for (index in 0 until totalBlocks) {
            val data = compressed.copyOfRange(index * sliceSize, minOf((index + 1) * sliceSize, compressed.size))
                .copyOf(sliceSize)
            result = decoder.processFrame(
                frame(
                    totalBlocks = totalBlocks,
                    compressedSize = compressed.size,
                    indices = intArrayOf(index),
                    checksum = checksum,
                    data = data,
                ),
            )
        }
        return requireNotNull(result)
    }

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun frame(
        totalBlocks: Int,
        compressedSize: Int,
        indices: IntArray = intArrayOf(0),
        checksum: Long = 0,
        dataSize: Int = QRSConstants.DEFAULT_SLICE_SIZE,
        data: ByteArray = ByteArray(dataSize),
    ): ByteArray {
        val payload = ByteArray(4 + 4 * indices.size + 12 + data.size)
        var offset = 0
        payload.writeIntLE(offset, indices.size)
        offset += 4
        indices.forEach { index ->
            payload.writeIntLE(offset, index)
            offset += 4
        }
        payload.writeIntLE(offset, totalBlocks)
        offset += 4
        payload.writeIntLE(offset, compressedSize)
        offset += 4
        payload.writeIntLE(offset, checksum.toInt())
        offset += 4
        data.copyInto(payload, offset)
        return payload
    }
}
