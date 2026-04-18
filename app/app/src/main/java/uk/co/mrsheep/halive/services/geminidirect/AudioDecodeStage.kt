package uk.co.mrsheep.halive.services.geminidirect

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Asynchronous audio decode stage.
 *
 * Decouples Base64 decoding from the message handling loop, ensuring that
 * variable decode times don't affect message processing or audio playback.
 *
 * Architecture:
 * - Incoming base64 audio strings are queued via [queueAudio]
 * - A dedicated coroutine processes the queue, decoding and writing to the jitter buffer
 * - Bounded channel provides backpressure if decode falls behind
 *
 * @param jitterBuffer The buffer to write decoded audio into
 */
class AudioDecodeStage(
    private val jitterBuffer: JitterBuffer
) {
    companion object {
        private const val TAG = "AudioDecodeStage"

        // Bounded queue to provide backpressure if decode falls behind
        // Gemini generates audio faster than real-time, so we need a large queue.
        // 512 chunks at ~100ms each = ~50 seconds of buffered base64 strings
        // Memory: ~512 * 6KB = ~3MB max - acceptable for smooth playback
        private const val DECODE_QUEUE_CAPACITY = 512
    }

    private val decodeScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("AudioDecode")
    )

    // Channel for incoming base64 audio - bounded to apply backpressure
    private val incomingAudio = Channel<String>(capacity = DECODE_QUEUE_CAPACITY)

    @Volatile
    private var isRunning = false

    /**
     * Start the decode worker.
     * Must be called before [queueAudio].
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Decode stage already running")
            return
        }

        isRunning = true
        Log.d(TAG, "Starting decode stage")

        decodeScope.launch {
            for (base64Audio in incomingAudio) {
                if (!isRunning) break
                processChunk(base64Audio)
            }
            Log.d(TAG, "Decode worker finished")
        }
    }

    /**
     * Queue audio for decoding.
     *
     * This is non-blocking - if the queue is full (decode falling behind),
     * the chunk will be dropped and a warning logged.
     *
     * @param base64Data Base64-encoded PCM audio data
     */
    fun queueAudio(base64Data: String) {
        if (!isRunning) {
            Log.w(TAG, "Decode stage not running, dropping audio")
            return
        }

        // trySend is non-blocking - if buffer full, we're falling behind
        val result = incomingAudio.trySend(base64Data)
        if (result.isFailure) {
            Log.w(TAG, "Decode queue full, dropping audio chunk")
        }
    }

    // Track chunk sizes for anomaly detection
    private var lastChunkSize = 0
    private var typicalChunkSize = 0
    private var chunkCount = 0

    /**
     * Process a single audio chunk: decode and write to jitter buffer.
     */
    private fun processChunk(base64Data: String) {
        try {
            // Decode base64 to PCM bytes
            val pcmData = Base64.decode(base64Data, Base64.NO_WRAP)
            val chunkSize = pcmData.size

            // Track typical chunk size (use first 10 chunks to establish baseline)
            chunkCount++
            if (chunkCount <= 10) {
                typicalChunkSize = maxOf(typicalChunkSize, chunkSize)
            }

            // Log if chunk is unusually small (less than 50% of typical) after baseline established
            val isSmall = chunkCount > 10 && typicalChunkSize > 0 && chunkSize < typicalChunkSize / 2
            if (isSmall) {
                Log.w(TAG, "SMALL_CHUNK: ${chunkSize} bytes (typical: ${typicalChunkSize}, prev: ${lastChunkSize})")
            }

            lastChunkSize = chunkSize

            // Write to jitter buffer
            if (!jitterBuffer.write(pcmData)) {
                Log.w(TAG, "Jitter buffer full, dropping ${pcmData.size} bytes of audio")
            } else {
                Log.v(TAG, "Decoded and buffered ${pcmData.size} bytes (buffered: ${jitterBuffer.bufferedMs()}ms)")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid base64 audio data", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio chunk", e)
        }
    }

    /**
     * Shutdown the decode stage.
     * Closes the channel and cancels the decode worker.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down decode stage")
        isRunning = false
        incomingAudio.close()
        decodeScope.cancel()
    }
}
