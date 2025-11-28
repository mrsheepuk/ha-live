package uk.co.mrsheep.halive.services.geminidirect

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.security.InvalidParameterException

/**
 * A circular buffer for storing audio data with a fixed capacity.
 * When full, oldest data is overwritten.
 */
internal class CircularAudioBuffer(
    private val capacityMs: Int,
    private val sampleRate: Int,
    private val bytesPerSample: Int = 2
) {
    private val capacityBytes = capacityMs * sampleRate * bytesPerSample / 1000
    private val buffer = ByteArray(capacityBytes)
    private var writePosition = 0
    private var totalWritten = 0L

    @Synchronized
    fun write(data: ByteArray) {
        for (byte in data) {
            buffer[writePosition] = byte
            writePosition = (writePosition + 1) % capacityBytes
        }
        totalWritten += data.size
    }

    @Synchronized
    fun getAll(): ByteArray {
        if (totalWritten == 0L) return ByteArray(0)

        val size = minOf(totalWritten, capacityBytes.toLong()).toInt()
        val result = ByteArray(size)

        if (totalWritten < capacityBytes) {
            // Buffer not full yet - data starts at 0
            System.arraycopy(buffer, 0, result, 0, size)
        } else {
            // Buffer is full - writePosition is where oldest data starts
            val firstPartSize = capacityBytes - writePosition
            System.arraycopy(buffer, writePosition, result, 0, firstPartSize)
            System.arraycopy(buffer, 0, result, firstPartSize, writePosition)
        }
        return result
    }

    @Synchronized
    fun clear() {
        writePosition = 0
        totalWritten = 0
    }
}

/**
 * Helper class for recording audio from the microphone.
 *
 * Playback is now handled separately by AudioPlaybackThread + JitterBuffer.
 * This class is responsible only for capturing microphone input.
 */
class AudioHelper(
    /** AudioRecord for recording from the system microphone. */
    private val recorder: AudioRecord,
    val sampleRate: Int
) {
    private var released: Boolean = false
    private var preBuffer: CircularAudioBuffer? = null

    /** Whether this AudioHelper has been released and can no longer be used. */
    val isReleased: Boolean get() = released

    /**
     * Release the system resources on the recorder.
     *
     * Once an [AudioHelper] has been "released", it can _not_ be used again.
     *
     * This method can safely be called multiple times, as it won't do anything if this instance has
     * already been released.
     */
    fun release() {
        if (released) return
        released = true

        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            // Already stopped
        }
        recorder.release()
        Log.d(TAG, "AudioHelper released")
    }

    /**
     * Pause the recording of the microphone, if it's recording.
     *
     * Does nothing if this [AudioHelper] has been [released][release].
     *
     * @see resumeRecording
     */
    fun pauseRecording() {
        if (released || recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) return

        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            release()
            throw IllegalStateException("The recorder was not properly initialized.")
        }
    }

    /**
     * Resumes the recording of the microphone, if it's not already running.
     *
     * Does nothing if this [AudioHelper] has been [released][release].
     *
     * @see pauseRecording
     */
    fun resumeRecording() {
        if (released || recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) return

        recorder.startRecording()
    }

    /**
     * Enables pre-buffering of audio data.
     * When enabled, audio is stored in a circular buffer while recording.
     *
     * @param capacityMs The buffer capacity in milliseconds (default 1500ms)
     */
    fun enablePreBuffering(capacityMs: Int = 1500) {
        preBuffer = CircularAudioBuffer(capacityMs, sampleRate)
    }

    /**
     * Returns all buffered audio data (oldest to newest).
     * Returns empty array if pre-buffering is not enabled or no data buffered.
     */
    fun getBufferedAudio(): ByteArray = preBuffer?.getAll() ?: ByteArray(0)

    /**
     * Clears the pre-buffer.
     */
    fun clearPreBuffer() {
        preBuffer?.clear()
    }

    /**
     * Start perpetually recording the system microphone, and return the bytes read in a flow.
     *
     * Returns an empty flow if this [AudioHelper] has been [released][release].
     */
    fun listenToRecording(): Flow<ByteArray> {
        Log.d(TAG, "listenToRecording() called, released=$released, recordingState=${recorder.recordingState}")
        if (released) {
            Log.w(TAG, "listenToRecording: AudioHelper already released, returning empty flow")
            return emptyFlow()
        }
        resumeRecording()
        Log.d(TAG, "listenToRecording: Recording resumed, recordingState=${recorder.recordingState}")

        return recorder.readAsFlow().onEach { chunk ->
            preBuffer?.write(chunk)
        }
    }

    companion object {
        private val TAG = AudioHelper::class.simpleName

        /**
         * Creates an instance of [AudioHelper] with the recorder initialized.
         *
         * A separate build method is necessary so that we can properly propagate the required manifest
         * permission, and throw exceptions when needed.
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun build(sampleRate: Int = 16000): AudioHelper {
            val bufferSize =
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (bufferSize <= 0)
                throw InvalidParameterException(
                    "Audio Record buffer size is invalid ($bufferSize)"
                )

            val recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            if (recorder.state != AudioRecord.STATE_INITIALIZED)
                throw IllegalStateException(
                    "Audio Record initialization has failed. State: ${recorder.state}"
                )

            // Enable echo cancellation if available
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true
                Log.d(TAG, "Acoustic Echo Canceler enabled")
            }

            return AudioHelper(recorder, sampleRate)
        }
    }
}

/**
 * The minimum buffer size for this instance.
 *
 * The same as calling [AudioRecord.getMinBufferSize], except the params are pre-populated.
 */
internal val AudioRecord.minBufferSize: Int
    get() = AudioRecord.getMinBufferSize(sampleRate, channelConfiguration, audioFormat)

/**
 * Reads from this [AudioRecord] and returns the data in a flow.
 *
 * Will yield when this instance is not recording.
 */
internal fun AudioRecord.readAsFlow() = flow {
    val TAG = "AudioRecord.readAsFlow"
    val buffer = ByteArray(minBufferSize)
    var readsEmitted = 0
    var notRecordingCount = 0

    Log.d(TAG, "readAsFlow started, bufferSize=$minBufferSize, recordingState=$recordingState")

    while (true) {
        if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            notRecordingCount++
            if (notRecordingCount <= 3 || notRecordingCount % 20 == 0) {
                Log.d(TAG, "Not recording (count=$notRecordingCount), recordingState=$recordingState, waiting 100ms...")
            }
            // delay uses a different scheduler in the backend, so it's "stickier" in its enforcement when
            // compared to yield.
            delay(100)
            continue
        }
        notRecordingCount = 0
        val bytesRead = read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
            readsEmitted++
            if (readsEmitted <= 3 || readsEmitted % 100 == 0) {
                Log.d(TAG, "Emitting audio chunk #$readsEmitted ($bytesRead bytes)")
            }
            emit(buffer.copyOf(bytesRead))
        } else if (bytesRead < 0) {
            Log.e(TAG, "AudioRecord.read() returned error: $bytesRead")
        }
        // delay uses a different scheduler in the backend, so it's "stickier" in its enforcement when
        // compared to yield.
        delay(0)
    }
}
