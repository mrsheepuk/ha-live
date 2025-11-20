package uk.co.mrsheep.halive.services.geminidirect

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.security.InvalidParameterException

/**
 * Helper class for recording audio and playing back a separate audio track at the same time.
 */
internal class AudioHelper(
    /** Record for recording the System microphone. */
    private val recorder: AudioRecord,
    /** Track for playing back what the model says. */
    private val playbackTrack: AudioTrack,
    /** Optional callback to report playback issues for debugging */
    private val onPlaybackIssue: ((String) -> Unit)? = null
) {
    private var released: Boolean = false

    /**
     * Release the system resources on the recorder and playback track.
     *
     * Once an [AudioHelper] has been "released", it can _not_ be used again.
     *
     * This method can safely be called multiple times, as it won't do anything if this instance has
     * already been released.
     */
    fun release() {
        if (released) return
        released = true

        recorder.release()
        playbackTrack.release()
    }

    /**
     * Play the provided audio data on the playback track.
     *
     * Does nothing if this [AudioHelper] has been [released][release].
     *
     * @throws IllegalStateException If the playback track was not properly initialized.
     * @throws IllegalArgumentException If the playback data is invalid.
     * @throws RuntimeException If we fail to play the audio data for some unknown reason.
     */
    fun playAudio(data: ByteArray): Int {
        if (released) return 0
        if (data.isEmpty()) return 0

        if (playbackTrack.playState == AudioTrack.PLAYSTATE_STOPPED) {
            playbackTrack.play()
            Log.d(TAG, "AudioTrack started playing")
        }

        val result = playbackTrack.write(data, 0, data.size)

        // Detect partial writes or underruns
        if (result > 0 && result < data.size) {
            val issue = "Partial write: ${result}/${data.size} bytes written (potential buffer issue)"
            Log.w(TAG, issue)
            onPlaybackIssue?.invoke(issue)
        }

        if (result > 0) return result

        if (result == 0) {
            val issue = "AudioTrack write returned 0 bytes (buffer full or track paused)"
            Log.w(TAG, issue)
            onPlaybackIssue?.invoke(issue)
            return 0
        }

        // ERROR_INVALID_OPERATION and ERROR_BAD_VALUE should never occur
        when (result) {
            AudioTrack.ERROR_INVALID_OPERATION -> {
                val issue = "AudioTrack not properly initialized"
                onPlaybackIssue?.invoke(issue)
                throw IllegalStateException("The playback track was not properly initialized.")
            }
            AudioTrack.ERROR_BAD_VALUE -> {
                val issue = "Invalid audio data"
                onPlaybackIssue?.invoke(issue)
                throw IllegalArgumentException("Playback data is somehow invalid.")
            }
            AudioTrack.ERROR_DEAD_OBJECT -> {
                val issue = "AudioTrack was released"
                Log.w(TAG, "Attempted to playback some audio, but the track has been released.")
                onPlaybackIssue?.invoke(issue)
                release() // to ensure `released` is set and `record` is released too
            }
            AudioTrack.ERROR -> {
                val issue = "AudioTrack write failed with unknown error"
                onPlaybackIssue?.invoke(issue)
                throw RuntimeException("Failed to play the audio data for some unknown reason.")
            }
        }
        return 0  // Error case
    }

    /**
     * Get the current playback position in frames.
     * Returns the number of frames that have been played by the hardware.
     */
    fun getPlaybackHeadPosition(): Int {
        if (released) return 0
        return playbackTrack.playbackHeadPosition
    }

    /**
     * Get the playback state.
     */
    fun getPlaybackState(): Int {
        if (released) return AudioTrack.PLAYSTATE_STOPPED
        return playbackTrack.playState
    }

    /**
     * Pause the recording of the microphone, if it's recording.
     *
     * Does nothing if this [AudioHelper] has been [released][release].
     *
     * @see resumeRecording
     *
     * @throws IllegalStateException If the playback track was not properly initialized.
     */
    fun pauseRecording() {
        if (released || recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) return

        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            release()
            throw IllegalStateException("The playback track was not properly initialized.")
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
     * Start perpetually recording the system microphone, and return the bytes read in a flow.
     *
     * Returns an empty flow if this [AudioHelper] has been [released][release].
     */
    fun listenToRecording(): Flow<ByteArray> {
        if (released) return emptyFlow()
        resumeRecording()

        return recorder.readAsFlow()
    }

    companion object {
        private val TAG = AudioHelper::class.simpleName

        /**
         * Creates an instance of [AudioHelper] with the track and record initialized.
         *
         * A separate build method is necessary so that we can properly propagate the required manifest
         * permission, and throw exceptions when needed.
         *
         * It also makes it easier to read, since the long initialization is separate from the
         * constructor.
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun build(onPlaybackIssue: ((String) -> Unit)? = null): AudioHelper {
            // Calculate minimum buffer size
            val minBufferSize = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Use 4x minimum buffer size to handle Gemini's streaming pattern
            // Gemini sends audio in bursts with ~200ms gaps between generation cycles
            // Typical minBufferSize is 80-160ms, so 4x gives us 320-640ms buffer
            // This prevents underruns during normal inter-chunk delays
            val playbackBufferSize = minBufferSize * 4

            Log.d(TAG, "AudioTrack buffer: min=$minBufferSize bytes, using=${playbackBufferSize} bytes (~${playbackBufferSize / 48}ms at 24kHz)")

            val playbackTrack =
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    playbackBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

            val bufferSize =
                AudioRecord.getMinBufferSize(
                    16000,
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
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            if (recorder.state != AudioRecord.STATE_INITIALIZED)
                throw IllegalStateException(
                    "Audio Record initialization has failed. State: ${recorder.state}"
                )

            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true
            }

            return AudioHelper(recorder, playbackTrack, onPlaybackIssue)
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
    val buffer = ByteArray(minBufferSize)

    while (true) {
        if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // delay uses a different scheduler in the backend, so it's "stickier" in its enforcement when
            // compared to yield.
            delay(0)
            continue
        }
        val bytesRead = read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
            emit(buffer.copyOf(bytesRead))
        }
        // delay uses a different scheduler in the backend, so it's "stickier" in its enforcement when
        // compared to yield.
        delay(0)
    }
}