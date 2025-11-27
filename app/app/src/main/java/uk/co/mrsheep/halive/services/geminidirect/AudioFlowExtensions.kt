package uk.co.mrsheep.halive.services.geminidirect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream

/**
 * Flow extensions for audio stream processing.
 *
 * These operators transform raw PCM audio streams for different consumers:
 * - Wake word detection needs fixed-size float chunks
 * - Gemini Live needs accumulated byte chunks
 */

/**
 * Accumulates bytes until exactly [size] bytes are available, then emits.
 * Guarantees exact chunk sizes - any leftover bytes are buffered for the next emission.
 *
 * @param size The exact number of bytes per emitted chunk
 */
fun Flow<ByteArray>.chunkedBytes(size: Int): Flow<ByteArray> = flow {
    val buffer = ByteArrayOutputStream()
    collect { chunk ->
        buffer.write(chunk)
        while (buffer.size() >= size) {
            val data = buffer.toByteArray()
            emit(data.copyOf(size))
            buffer.reset()
            if (data.size > size) {
                buffer.write(data, size, data.size - size)
            }
        }
    }
}

/**
 * Converts 16-bit PCM bytes to float samples in range [-1.0, 1.0].
 * Assumes little-endian byte order (Android default for PCM audio).
 *
 * Each pair of bytes becomes one float sample:
 * - First byte: low 8 bits
 * - Second byte: high 8 bits (sign-extended)
 */
fun Flow<ByteArray>.toFloatSamples(): Flow<FloatArray> = map { bytes ->
    FloatArray(bytes.size / 2) { i ->
        val low = bytes[i * 2].toInt() and 0xFF
        val high = bytes[i * 2 + 1].toInt()
        val sample = (high shl 8) or low
        sample / 32768f
    }
}

/**
 * Combined operator: accumulates exact sample count and converts to float.
 * Convenience function that chains chunkedBytes() and toFloatSamples() with
 * the correct byte count calculation (sampleCount * 2 for 16-bit samples).
 *
 * @param sampleCount The exact number of float samples per emitted chunk
 */
fun Flow<ByteArray>.toFloatChunks(sampleCount: Int): Flow<FloatArray> =
    chunkedBytes(sampleCount * 2).toFloatSamples()
