package uk.co.mrsheep.halive.services.geminidirect

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * A ring buffer designed to absorb network and decode timing jitter for audio playback.
 *
 * Key features:
 * - Pre-buffering: Won't allow reads until a minimum threshold of audio is buffered
 * - Ring buffer: Fixed allocation, no GC pressure during streaming
 * - Thread-safe: Lock-based synchronization between writer (decode) and reader (playback)
 * - Timed waits: Reader can wait for data without busy-spinning
 *
 * @param capacity Total buffer size in bytes
 * @param preBufferThreshold Bytes to accumulate before playback can start
 * @param sampleRate Audio sample rate (for time-based calculations)
 * @param bytesPerSample Bytes per audio sample (2 for 16-bit mono)
 */
class JitterBuffer(
    private val capacity: Int,
    private val preBufferThreshold: Int,
    private val sampleRate: Int = 24000,
    private val bytesPerSample: Int = 2
) {
    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var bufferedBytes = 0

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()

    /**
     * Whether enough audio has been buffered to start playback.
     * Once true, remains true until [clear] is called.
     */
    @Volatile
    var isPreBuffered = false
        private set

    /**
     * Write audio data into the buffer.
     * Called from decode stage (any thread).
     *
     * @param data Audio data to write
     * @param offset Offset into data array
     * @param length Number of bytes to write
     * @return true if data was written, false if buffer is full (overflow)
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        lock.withLock {
            if (bufferedBytes + length > capacity) {
                // Buffer overflow - caller should handle (e.g., drop oldest or reject)
                return false
            }

            // Copy data into ring buffer, handling wrap-around
            val firstChunk = minOf(length, capacity - writePos)
            System.arraycopy(data, offset, buffer, writePos, firstChunk)
            if (firstChunk < length) {
                System.arraycopy(data, offset + firstChunk, buffer, 0, length - firstChunk)
            }

            writePos = (writePos + length) % capacity
            bufferedBytes += length

            // Check if we've reached pre-buffer threshold
            if (!isPreBuffered && bufferedBytes >= preBufferThreshold) {
                isPreBuffered = true
            }

            // Signal any waiting reader
            dataAvailable.signal()
            return true
        }
    }

    /**
     * Read audio data from the buffer into the destination array.
     * Called from playback thread.
     *
     * Will not return data until [isPreBuffered] is true.
     * If no data available after pre-buffering, waits up to [maxWaitMs].
     *
     * @param dest Destination buffer to read into
     * @param maxWaitMs Maximum time to wait for data if buffer is empty
     * @return Number of bytes read (0 if underrun after wait)
     */
    fun read(dest: ByteArray, maxWaitMs: Long = 10): Int {
        lock.withLock {
            // Don't start reading until pre-buffered
            if (!isPreBuffered) {
                // Wait for pre-buffer threshold to be reached
                val preBufferWaitMs = 100L // Give more time for initial buffering
                dataAvailable.await(preBufferWaitMs, TimeUnit.MILLISECONDS)
                if (!isPreBuffered) return 0
            }

            val toRead = minOf(dest.size, bufferedBytes)
            if (toRead == 0) {
                // Underrun - wait briefly for more data
                dataAvailable.await(maxWaitMs, TimeUnit.MILLISECONDS)
                val availableAfterWait = minOf(dest.size, bufferedBytes)
                if (availableAfterWait > 0) {
                    return copyOut(dest, availableAfterWait)
                }
                return 0
            }

            return copyOut(dest, toRead)
        }
    }

    /**
     * Copy data out of the ring buffer.
     * Must be called while holding the lock.
     */
    private fun copyOut(dest: ByteArray, length: Int): Int {
        // Handle wrap-around read
        val firstChunk = minOf(length, capacity - readPos)
        System.arraycopy(buffer, readPos, dest, 0, firstChunk)
        if (firstChunk < length) {
            System.arraycopy(buffer, 0, dest, firstChunk, length - firstChunk)
        }

        readPos = (readPos + length) % capacity
        bufferedBytes -= length
        return length
    }

    /**
     * Clear all buffered audio.
     * Called on interruption to immediately stop current playback.
     */
    fun clear() {
        lock.withLock {
            writePos = 0
            readPos = 0
            bufferedBytes = 0
            isPreBuffered = false
        }
    }

    /**
     * Get the current amount of buffered audio in milliseconds.
     */
    fun bufferedMs(): Int {
        lock.withLock {
            return (bufferedBytes / bytesPerSample) * 1000 / sampleRate
        }
    }

    /**
     * Get the current amount of buffered audio in bytes.
     */
    fun bufferedBytes(): Int {
        lock.withLock {
            return bufferedBytes
        }
    }
}
