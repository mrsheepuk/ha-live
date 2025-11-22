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

        // Stop recording before releasing to ensure threads terminate cleanly
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recorder", e)
            }
        }
        recorder.release()

        // Stop playback before releasing to ensure threads terminate cleanly
        if (playbackTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
            try {
                playbackTrack.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping playback track", e)
            }
        }
        playbackTrack.release()
    }

    /**
     * Play the provided audio data on the playback track.
     *
     * This method handles partial writes by looping until all bytes are written,
     * ensuring zero packet loss during playback.
     *
     * Does nothing if this [AudioHelper] has been [released][release].
     *
     * @throws IllegalStateException If the playback track was not properly initialized.
     * @throws IllegalArgumentException If the playback data is invalid.
     * @throws RuntimeException If we fail to play the audio data for some unknown reason.
     */
    fun playAudio(data: ByteArray) {
        if (released) return
        if (data.isEmpty()) return

        if (playbackTrack.playState == AudioTrack.PLAYSTATE_STOPPED) {
            playbackTrack.play()
        }

        var bytesWritten = 0
        while (bytesWritten < data.size) {
            // Check if released during the loop (race condition mitigation)
            if (released) return

            // Write only the remaining bytes
            val result = try {
                playbackTrack.write(
                    data,
                    bytesWritten,
                    data.size - bytesWritten
                )
            } catch (e: IllegalStateException) {
                // AudioTrack was released while we were writing (race condition with close())
                Log.w(TAG, "AudioTrack released during write, stopping playback")
                return
            }

            if (result < 0) {
                // Handle errors
                when (result) {
                    AudioTrack.ERROR_INVALID_OPERATION ->
                        throw IllegalStateException("The playback track was not properly initialized.")
                    AudioTrack.ERROR_BAD_VALUE ->
                        throw IllegalArgumentException("Playback data is somehow invalid.")
                    AudioTrack.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "Attempted to playback some audio, but the track has been released.")
                        release() // to ensure `released` is set and `record` is released too
                        return
                    }
                    AudioTrack.ERROR ->
                        throw RuntimeException("Failed to play the audio data for some unknown reason.")
                }
                Log.e(TAG, "AudioTrack write error: $result")
                return
            }

            if (result == 0) {
                // Buffer is full, sleep briefly to avoid busy-wait burning CPU
                try {
                    Thread.sleep(1)
                } catch (e: InterruptedException) {
                    return
                }
            }

            bytesWritten += result
        }
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
        fun build(): AudioHelper {
            // Calculate minimum buffer size
            val minBufferSize = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Use 8x minimum buffer size to prevent underruns from network jitter
            val playbackBufferSize = minBufferSize * 8

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

            return AudioHelper(recorder, playbackTrack)
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