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
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * Any dropped audio (queue full, jitter buffer full, decode errors) is counted
 * and reported to the in-app debug log, since drops are audible as skipped
 * speech. [takeStatsSummary] returns and resets the per-turn counters.
 *
 * @param jitterBuffer The buffer to write decoded audio into
 * @param logger Optional in-app debug logger for drop diagnostics
 */
class AudioDecodeStage(
    private val jitterBuffer: JitterBuffer,
    private val logger: AppLogger? = null
) {
    companion object {
        private const val TAG = "AudioDecodeStage"

        // Bounded queue to provide backpressure if decode falls behind
        // Gemini generates audio faster than real-time, so we need a large queue.
        // 512 chunks at ~100ms each = ~50 seconds of buffered base64 strings
        // Memory: ~512 * 6KB = ~3MB max - acceptable for smooth playback
        private const val DECODE_QUEUE_CAPACITY = 512

        // 24kHz 16-bit mono playback = 48 bytes per millisecond of audio
        private const val BYTES_PER_MS = 48

        // Cascading drops would flood the debug log - log the first, then every Nth
        private const val DROP_LOG_INTERVAL = 25
    }

    private val decodeScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("AudioDecode")
    )

    // Channel for incoming base64 audio - bounded to apply backpressure
    private val incomingAudio = Channel<String>(capacity = DECODE_QUEUE_CAPACITY)

    @Volatile
    private var isRunning = false

    // Per-turn diagnostic counters, reset by takeStatsSummary()
    private val chunksQueued = AtomicInteger(0)
    private val chunksDecoded = AtomicInteger(0)
    private val bytesBuffered = AtomicLong(0)
    private val droppedQueueFull = AtomicInteger(0)
    private val droppedBufferFull = AtomicInteger(0)
    private val bytesDroppedBufferFull = AtomicLong(0)
    private val decodeErrors = AtomicInteger(0)

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun debugLog(detail: String, success: Boolean = true) {
        logger?.addLogEntry(LogEntry(
            timestamp = timestampFormat.format(Date()),
            toolName = "Audio: Decode",
            parameters = "",
            success = success,
            result = detail
        ))
    }

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
            debugLog("Decode stage not running - dropped audio chunk", success = false)
            return
        }

        chunksQueued.incrementAndGet()

        // trySend is non-blocking - if buffer full, we're falling behind
        val result = incomingAudio.trySend(base64Data)
        if (result.isFailure) {
            val dropped = droppedQueueFull.incrementAndGet()
            Log.w(TAG, "Decode queue full, dropping audio chunk (#$dropped)")
            if (dropped == 1 || dropped % DROP_LOG_INTERVAL == 0) {
                debugLog("Decode queue full - dropped $dropped chunk(s) so far (audio will skip)", success = false)
            }
        }
    }

    /**
     * Process a single audio chunk: decode and write to jitter buffer.
     */
    private fun processChunk(base64Data: String) {
        try {
            // Decode base64 to PCM bytes
            val pcmData = Base64.decode(base64Data, Base64.NO_WRAP)

            // Write to jitter buffer
            if (!jitterBuffer.write(pcmData)) {
                val dropped = droppedBufferFull.incrementAndGet()
                val droppedBytes = bytesDroppedBufferFull.addAndGet(pcmData.size.toLong())
                Log.w(TAG, "Jitter buffer full, dropping ${pcmData.size} bytes of audio (#$dropped)")
                if (dropped == 1 || dropped % DROP_LOG_INTERVAL == 0) {
                    debugLog(
                        "Jitter buffer full - dropped $dropped chunk(s), ~${droppedBytes / BYTES_PER_MS}ms of speech lost so far",
                        success = false
                    )
                }
            } else {
                chunksDecoded.incrementAndGet()
                bytesBuffered.addAndGet(pcmData.size.toLong())
                Log.v(TAG, "Decoded and buffered ${pcmData.size} bytes")
            }
        } catch (e: IllegalArgumentException) {
            decodeErrors.incrementAndGet()
            Log.e(TAG, "Invalid base64 audio data", e)
            debugLog("Invalid base64 audio data: ${e.message}", success = false)
        } catch (e: Exception) {
            decodeErrors.incrementAndGet()
            Log.e(TAG, "Error decoding audio chunk", e)
            debugLog("Error decoding audio chunk: ${e.message}", success = false)
        }
    }

    /**
     * Returns a one-line summary of the counters since the last call, and
     * resets them. Intended to be logged once per model turn.
     */
    fun takeStatsSummary(): String {
        val queued = chunksQueued.getAndSet(0)
        val decoded = chunksDecoded.getAndSet(0)
        val bytes = bytesBuffered.getAndSet(0)
        val queueDrops = droppedQueueFull.getAndSet(0)
        val bufferDrops = droppedBufferFull.getAndSet(0)
        val bufferDropBytes = bytesDroppedBufferFull.getAndSet(0)
        val errors = decodeErrors.getAndSet(0)

        val summary = StringBuilder(
            "audio chunks received=$queued decoded=$decoded (~${bytes / BYTES_PER_MS}ms)"
        )
        if (queueDrops > 0) summary.append(", DROPPED queueFull=$queueDrops")
        if (bufferDrops > 0) summary.append(", DROPPED bufferFull=$bufferDrops (~${bufferDropBytes / BYTES_PER_MS}ms lost)")
        if (errors > 0) summary.append(", decodeErrors=$errors")
        if (queueDrops == 0 && bufferDrops == 0 && errors == 0) summary.append(", no drops")
        return summary.toString()
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
