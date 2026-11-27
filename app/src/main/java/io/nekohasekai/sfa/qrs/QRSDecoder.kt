package io.nekohasekai.sfa.qrs

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class QRSDecoder {
    companion object {
        const val MAX_FRAME_BYTES = 4 + 4 * QRSConstants.MAX_DEGREE + 12 + QRSConstants.MAX_SLICE_SIZE
        const val MAX_BASE64_FRAME_LENGTH = ((MAX_FRAME_BYTES + 2) / 3) * 4 + 4

        internal fun isValidFrame(payload: ByteArray): Boolean = parsePayload(payload) != null

        private fun parsePayload(payload: ByteArray): LubyCodec.EncodedBlock? {
            if (payload.size !in 16..MAX_FRAME_BYTES) return null

            var offset = 0
            val degree = payload.readIntLE(offset)
            offset += 4
            if (degree !in 1..QRSConstants.MAX_DEGREE) return null

            val headerSize = 4L + 4L * degree + 12
            if (headerSize > payload.size) return null

            val indices = IntArray(degree) {
                val idx = payload.readIntLE(offset)
                offset += 4
                idx
            }
            val totalBlocks = payload.readIntLE(offset)
            offset += 4
            val compressedSize = payload.readIntLE(offset)
            offset += 4
            val checksum = payload.readIntLE(offset).toLong() and 0xFFFFFFFFL
            offset += 4

            if (payload.size - offset !in QRSConstants.MIN_SLICE_SIZE..QRSConstants.MAX_SLICE_SIZE) return null
            val data = payload.copyOfRange(offset, payload.size)
            return try {
                LubyCodec.EncodedBlock(degree, indices, totalBlocks, compressedSize, checksum, data)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private var codec: LubyCodec? = null
    private var state: LubyCodec.DecodingState? = null
    private val processedHashes = mutableSetOf<Int>()

    private val inflater = Inflater()
    private val decompressBuffer = ByteArray(8192)
    private val outputBuffer = ByteArrayOutputStream(32768)

    data class DecodeProgress(
        val decodedBlocks: Int,
        val totalBlocks: Int,
        val isComplete: Boolean,
        val data: ByteArray? = null,
        val error: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodeProgress
            return decodedBlocks == other.decodedBlocks &&
                totalBlocks == other.totalBlocks &&
                isComplete == other.isComplete &&
                data.contentEquals(other.data) &&
                error == other.error
        }

        override fun hashCode(): Int {
            var result = decodedBlocks
            result = 31 * result + totalBlocks
            result = 31 * result + isComplete.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }

    @Synchronized
    fun processFrame(base64Content: String): DecodeProgress? {
        if (base64Content.length > MAX_BASE64_FRAME_LENGTH) return null
        val payload = try {
            Base64.decode(base64Content, Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        return processFrame(payload)
    }

    @Synchronized
    fun processFrame(payload: ByteArray): DecodeProgress? {
        if (payload.size !in 16..MAX_FRAME_BYTES) return null
        val hash = payload.contentHashCode()
        if (hash in processedHashes) {
            return state?.let {
                DecodeProgress(it.decodedCount, it.totalBlocks, it.decodedCount == it.totalBlocks)
            }
        }

        val block = parsePayload(payload) ?: return null
        processedHashes.add(hash)

        // Auto-detect dataset switch: if checksum changes, reset decoder.
        if (state != null && state!!.checksum != block.checksum) {
            reset()
        }

        if (codec == null) {
            codec = LubyCodec(sliceSize = block.data.size)
            state = codec!!.createDecodingState(block)
        }

        val currentState = state!!
        if (block.totalBlocks != currentState.totalBlocks ||
            block.compressedSize != currentState.compressedSize ||
            block.data.size != currentState.sliceSize
        ) {
            return DecodeProgress(
                currentState.decodedCount,
                currentState.totalBlocks,
                false,
                error = "Inconsistent QRS frame",
            )
        }

        val complete = try {
            codec!!.processBlock(currentState, block)
        } catch (_: IllegalArgumentException) {
            return DecodeProgress(
                currentState.decodedCount,
                currentState.totalBlocks,
                false,
                error = "Invalid QRS frame",
            )
        }

        return if (complete) {
            val assembledData = codec!!.assembleData(currentState)
            val compressedData = assembledData.copyOf(currentState.compressedSize)

            val decompressedData = try {
                decompress(compressedData)
            } catch (_: Exception) {
                null
            }

            if (decompressedData != null) {
                val checksumValid = codec!!.verifyChecksum(
                    decompressedData,
                    currentState.checksum,
                    currentState.totalBlocks,
                )
                if (checksumValid) {
                    return DecodeProgress(
                        currentState.decodedCount,
                        currentState.totalBlocks,
                        true,
                        decompressedData,
                    )
                }
            }

            val rawChecksumValid = codec!!.verifyChecksum(
                compressedData,
                currentState.checksum,
                currentState.totalBlocks,
            )
            if (rawChecksumValid) {
                DecodeProgress(currentState.decodedCount, currentState.totalBlocks, true, compressedData)
            } else {
                DecodeProgress(
                    currentState.decodedCount,
                    currentState.totalBlocks,
                    true,
                    error = "Checksum verification failed",
                )
            }
        } else {
            DecodeProgress(currentState.decodedCount, currentState.totalBlocks, false)
        }
    }

    @Synchronized
    fun reset() {
        codec = null
        state = null
        processedHashes.clear()
    }

    val progress: DecodeProgress?
        @Synchronized get() = state?.let {
            DecodeProgress(it.decodedCount, it.totalBlocks, it.decodedCount == it.totalBlocks)
        }

    private fun decompress(data: ByteArray): ByteArray {
        inflater.reset()
        inflater.setInput(data)
        outputBuffer.reset()

        while (!inflater.finished()) {
            val count = inflater.inflate(decompressBuffer)
            if (count > 0) {
                require(outputBuffer.size().toLong() + count <= QRSConstants.MAX_DECOMPRESSED_SIZE) {
                    "Decompressed QRS data is too large"
                }
                outputBuffer.write(decompressBuffer, 0, count)
                continue
            }
            when {
                inflater.needsInput() -> error("Incomplete compressed QRS data")
                inflater.needsDictionary() -> error("Preset dictionary is not supported")
                else -> error("Inflater made no progress")
            }
        }

        return outputBuffer.toByteArray()
    }
}
