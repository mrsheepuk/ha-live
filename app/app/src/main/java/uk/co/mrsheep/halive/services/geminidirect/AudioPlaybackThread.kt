package uk.co.mrsheep.halive.services.geminidirect

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Dedicated thread for audio playback with minimal latency.
 *
 * Key design principles:
 * - Runs at THREAD_PRIORITY_URGENT_AUDIO for real-time scheduling
 * - Pre-allocated buffers to avoid GC during playback
 * - RMS calculation uses sparse sampling (O(n/16) instead of O(n))
 * - RMS is calculated AFTER write, reported on NEXT iteration
 * - Level callbacks posted to main thread without blocking
 *
 * The playback loop:
 * 1. Report previous chunk's audio level (non-blocking post to UI)
 * 2. Read from jitter buffer
 * 3. Write to AudioTrack (may block waiting for buffer space)
 * 4. Calculate RMS for next iteration (in "slack time" after write)
 *
 * @param audioTrack The AudioTrack to write audio to
 * @param jitterBuffer The buffer to read audio from
 * @param chunkSizeBytes Size of each read/write chunk
 * @param onAudioLevel Callback for audio level updates (called on main thread)
 * @param onUnderrun Callback when buffer underrun occurs
 */
class AudioPlaybackThread(
    private val audioTrack: AudioTrack,
    private val jitterBuffer: JitterBuffer,
    private val chunkSizeBytes: Int,
    private val onAudioLevel: ((Float) -> Unit)?,
    private val onUnderrun: (() -> Unit)? = null
) : Thread("AudioPlayback") {

    companion object {
        private const val TAG = "AudioPlaybackThread"

        // Sample every 16th sample (32 bytes apart for 16-bit audio)
        // This is ~16x faster than full scan while maintaining accuracy
        private const val SAMPLE_STRIDE_BYTES = 32
    }

    @Volatile
    private var running = true

    // Pre-allocated buffer - NO allocations in the hot loop
    private val writeBuffer = ByteArray(chunkSizeBytes)

    // Level from previous iteration - reported at START of next iteration
    // One frame behind = imperceptible to humans (~20-40ms)
    private var pendingLevel = 0f

    // Handler for posting level updates to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track if AudioTrack has been started
    private var trackStarted = false

    init {
        priority = Thread.MAX_PRIORITY
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        Log.d(TAG, "Audio playback thread started with priority URGENT_AUDIO")

        try {
            playbackLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Playback thread error", e)
        } finally {
            Log.d(TAG, "Audio playback thread stopped")
        }
    }

    private fun playbackLoop() {
        while (running) {
            // 1. Post PREVIOUS chunk's level to UI thread (non-blocking)
            //    This happens before read/write so the UI update is "in flight"
            //    while we're doing the actual audio work
            if (pendingLevel > 0f || trackStarted) {
                val levelToPost = pendingLevel
                mainHandler.post { onAudioLevel?.invoke(levelToPost) }
            }

            // 2. Read from jitter buffer
            val bytesRead = jitterBuffer.read(writeBuffer)

            if (bytesRead > 0) {
                // 3. Start AudioTrack on first data (after pre-buffering complete)
                if (!trackStarted) {
                    audioTrack.play()
                    trackStarted = true
                    Log.d(TAG, "AudioTrack started after pre-buffering")
                }

                // 4. Write to AudioTrack - TIME CRITICAL
                //    This may block waiting for hardware buffer space (which is fine)
                val written = writeAudioWithRetry(bytesRead)

                if (written > 0) {
                    // 5. Calculate level for NEXT report
                    //    Happens in "slack time" after write returns
                    //    Uses sampling for speed
                    pendingLevel = sampleRmsLevel(writeBuffer, written)
                }
            } else {
                // No data available - potential underrun
                // Note: Underruns are common during pauses and not always problematic
                // Removed logging here as it creates excessive noise
                pendingLevel = 0f
            }
        }

        // Clean up
        if (trackStarted) {
            try {
                audioTrack.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack already stopped")
            }
        }
    }

    /**
     * Write audio to AudioTrack, handling partial writes.
     * Returns total bytes written.
     */
    private fun writeAudioWithRetry(length: Int): Int {
        var bytesWritten = 0
        var retryCount = 0
        val maxRetries = 10

        while (bytesWritten < length && running && retryCount < maxRetries) {
            val result = try {
                audioTrack.write(writeBuffer, bytesWritten, length - bytesWritten)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack released during write")
                return bytesWritten
            }

            when {
                result < 0 -> {
                    // Error
                    handleAudioTrackError(result)
                    return bytesWritten
                }
                result == 0 -> {
                    // Buffer full, brief pause then retry
                    retryCount++
                    Thread.sleep(1)
                }
                else -> {
                    bytesWritten += result
                    retryCount = 0 // Reset on successful write
                }
            }
        }

        return bytesWritten
    }

    private fun handleAudioTrackError(errorCode: Int) {
        when (errorCode) {
            AudioTrack.ERROR_INVALID_OPERATION ->
                Log.e(TAG, "AudioTrack ERROR_INVALID_OPERATION")
            AudioTrack.ERROR_BAD_VALUE ->
                Log.e(TAG, "AudioTrack ERROR_BAD_VALUE")
            AudioTrack.ERROR_DEAD_OBJECT ->
                Log.e(TAG, "AudioTrack ERROR_DEAD_OBJECT - track released")
            AudioTrack.ERROR ->
                Log.e(TAG, "AudioTrack ERROR - unknown error")
        }
    }

    /**
     * Ultra-fast RMS estimation using sparse sampling.
     *
     * Instead of processing every sample (O(n)), we sample every 16th sample.
     * For audio visualization, this is perceptually identical - human eyes
     * can't distinguish the difference in a bouncing level meter.
     *
     * At 24kHz with 20ms chunks (480 samples), sampling every 16th sample
     * gives us 30 samples - more than enough for accurate RMS estimation.
     *
     * @param data Audio data buffer (16-bit PCM, little-endian)
     * @param length Number of valid bytes in the buffer
     * @return RMS level normalized to 0.0-1.0
     */
    private fun sampleRmsLevel(data: ByteArray, length: Int): Float {
        if (length < SAMPLE_STRIDE_BYTES) return 0f

        var sum = 0.0
        var count = 0

        // Sample every 16th sample (32 bytes apart for 16-bit audio)
        var i = 0
        while (i < length - 1) {
            // Convert little-endian 16-bit sample (mask both bytes to prevent sign extension)
            val sample = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            // Convert unsigned to signed
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sum += signedSample.toDouble() * signedSample.toDouble()
            count++
            i += SAMPLE_STRIDE_BYTES
        }

        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Signal the thread to stop.
     * The thread will finish its current iteration and exit cleanly.
     */
    fun shutdown() {
        Log.d(TAG, "Shutdown requested")
        running = false
        interrupt() // Wake up if blocked on jitter buffer read
    }

    /**
     * Check if the thread is still running.
     */
    fun isRunning(): Boolean = running && isAlive
}
