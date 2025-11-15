package uk.co.mrsheep.halive.services.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * GeminiAudioManager handles audio recording and playback for Gemini Live API.
 *
 * Borrows proven configuration from Firebase SDK's AudioHelper:
 * - Recording: 16kHz, mono, PCM 16-bit, AudioSource.VOICE_COMMUNICATION
 * - Playback: 24kHz, mono, PCM 16-bit, USAGE_MEDIA, CONTENT_TYPE_SPEECH
 * - Acoustic Echo Cancellation (if available)
 *
 * Architecture:
 * - Recording: Flow-based streaming (Flow<ByteArray>)
 * - Playback: Channel-based queuing (Channel<ByteArray>)
 * - Both run on IO dispatcher to avoid blocking main thread
 */
class GeminiAudioManager {

    // Recording Configuration (Gemini Live API expects 16kHz input)
    private companion object {
        private const val TAG = "GeminiAudioManager"

        // Recording (microphone input)
        private const val RECORDING_SAMPLE_RATE = 16000  // Hz
        private const val RECORDING_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val RECORDING_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RECORDING_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

        // Playback (speaker output)
        private const val PLAYBACK_SAMPLE_RATE = 24000   // Hz
        private const val PLAYBACK_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val PLAYBACK_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var isRecording = false
    private var isPlaying = false

    /**
     * Calculate minimum buffer size for AudioRecord
     */
    private fun getRecordingBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE,
            RECORDING_CHANNEL_CONFIG,
            RECORDING_AUDIO_FORMAT
        )
        return if (minSize == AudioRecord.ERROR || minSize == AudioRecord.ERROR_BAD_VALUE) {
            // Fallback: 16kHz mono 16-bit = 2 bytes per sample
            // Use 0.1 seconds = 1600 samples = 3200 bytes
            3200
        } else {
            // Use at least 2x minimum for safety
            minSize * 2
        }
    }

    /**
     * Calculate minimum buffer size for AudioTrack
     */
    private fun getPlaybackBufferSize(): Int {
        val minSize = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            PLAYBACK_CHANNEL_CONFIG,
            PLAYBACK_AUDIO_FORMAT
        )
        return if (minSize == AudioTrack.ERROR || minSize == AudioTrack.ERROR_BAD_VALUE) {
            // Fallback: 24kHz mono 16-bit = 2 bytes per sample
            // Use 0.1 seconds = 2400 samples = 4800 bytes
            4800
        } else {
            // Use at least 2x minimum for safety
            minSize * 2
        }
    }

    /**
     * Start recording and return a Flow of audio bytes.
     *
     * Flow emits ByteArray chunks of PCM 16-bit audio at 16kHz mono.
     * Automatically handles:
     * - AudioRecord initialization and state management
     * - Acoustic Echo Cancellation (if available on device)
     * - Chunk-based streaming to upstream (via Flow)
     *
     * @return Flow<ByteArray> emitting audio chunks
     * @throws IllegalStateException if recording is already active
     * @throws SecurityException if RECORD_AUDIO permission not granted
     */
    fun startRecording(): Flow<ByteArray> = flow {
        if (isRecording) {
            throw IllegalStateException("Recording already in progress")
        }

        withContext(Dispatchers.IO) {
            try {
                val bufferSize = getRecordingBufferSize()
                Log.d(TAG, "Starting recording: bufferSize=$bufferSize bytes")

                // Initialize AudioRecord
                audioRecord = AudioRecord(
                    RECORDING_SOURCE,
                    RECORDING_SAMPLE_RATE,
                    RECORDING_CHANNEL_CONFIG,
                    RECORDING_AUDIO_FORMAT,
                    bufferSize
                )

                val record = audioRecord ?: throw IllegalStateException("Failed to create AudioRecord")

                // Check recording initialization
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed, state=${record.state}")
                }

                // Try to enable Acoustic Echo Cancellation if available
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        acousticEchoCanceler = AcousticEchoCanceler.create(record.audioSessionId)
                        if (acousticEchoCanceler?.enabled == false) {
                            acousticEchoCanceler?.enabled = true
                            Log.d(TAG, "Acoustic Echo Cancellation enabled")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable Acoustic Echo Cancellation", e)
                    acousticEchoCanceler = null
                }

                isRecording = true
                record.startRecording()
                Log.d(TAG, "AudioRecord started, state=${record.recordingState}")

                // Emit chunks in loop
                val buffer = ByteArray(bufferSize)
                while (isRecording && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        // Emit only the bytes actually read
                        val chunk = buffer.copyOfRange(0, bytesRead)
                        emit(chunk)
                        Log.v(TAG, "Recording chunk emitted: ${chunk.size} bytes")
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission not granted", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                throw e
            } finally {
                stopRecording()
            }
        }
    }

    /**
     * Stop recording and clean up resources.
     *
     * Safe to call even if recording hasn't started.
     * Releases AudioRecord and Acoustic Echo Canceler.
     */
    fun stopRecording() {
        try {
            isRecording = false

            audioRecord?.let { record ->
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                        Log.d(TAG, "AudioRecord stopped")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord", e)
                }

                try {
                    record.release()
                    Log.d(TAG, "AudioRecord released")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord", e)
                }
            }

            audioRecord = null

            acousticEchoCanceler?.let { aec ->
                try {
                    if (aec.enabled) {
                        aec.enabled = false
                    }
                    aec.release()
                    Log.d(TAG, "Acoustic Echo Canceler released")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing Acoustic Echo Canceler", e)
                }
            }

            acousticEchoCanceler = null

        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRecording", e)
        }
    }

    /**
     * Start playback and return a Channel for queueing audio bytes.
     *
     * The returned Channel can be used to queue PCM 16-bit audio chunks at 24kHz mono.
     * This function:
     * - Creates and initializes AudioTrack
     * - Launches a background coroutine that reads from the channel and writes to AudioTrack
     * - Returns the channel for the caller to write audio data to
     *
     * Usage:
     * ```
     * val playbackChannel = audioManager.startPlayback()
     * try {
     *     playbackChannel.send(audioBytes)
     *     // More audio...
     * } finally {
     *     playbackChannel.close()
     * }
     * ```
     *
     * @return Channel<ByteArray> for queueing audio chunks
     * @throws IllegalStateException if playback is already active
     */
    suspend fun startPlayback(): Channel<ByteArray> {
        if (isPlaying) {
            throw IllegalStateException("Playback already in progress")
        }

        return withContext(Dispatchers.IO) {
            val bufferSize = getPlaybackBufferSize()
            Log.d(TAG, "Starting playback: bufferSize=$bufferSize bytes")

            try {
                // Initialize AudioTrack with modern API (requires API 21+)
                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(PLAYBACK_CHANNEL_CONFIG)
                        .setEncoding(PLAYBACK_AUDIO_FORMAT)
                        .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                val track = audioTrack ?: throw IllegalStateException("Failed to create AudioTrack")

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack initialization failed, state=${track.state}")
                }

                isPlaying = true
                track.play()
                Log.d(TAG, "AudioTrack started, state=${track.playState}")

                // Create channel for queuing audio
                val playbackChannel = Channel<ByteArray>(capacity = 64)  // Buffer up to 64 chunks

                // Launch background coroutine to consume from channel and write to AudioTrack
                @Suppress("DEPRECATION")
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    try {
                        for (audioData in playbackChannel) {
                            if (!isPlaying) break

                            // Write to AudioTrack
                            val bytesWritten = track.write(audioData, 0, audioData.size)
                            if (bytesWritten != audioData.size) {
                                Log.w(TAG, "AudioTrack write mismatch: wrote=$bytesWritten, expected=${audioData.size}")
                            }
                            Log.v(TAG, "Playback chunk written: $bytesWritten bytes")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Playback error in write loop", e)
                    } finally {
                        stopPlayback()
                    }
                }

                playbackChannel

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback", e)
                stopPlayback()
                throw e
            }
        }
    }

    /**
     * Stop playback and clean up resources.
     *
     * Safe to call even if playback hasn't started.
     * Releases AudioTrack resources.
     */
    fun stopPlayback() {
        try {
            isPlaying = false

            audioTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                        Log.d(TAG, "AudioTrack stopped")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioTrack", e)
                }

                try {
                    track.release()
                    Log.d(TAG, "AudioTrack released")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioTrack", e)
                }
            }

            audioTrack = null

        } catch (e: Exception) {
            Log.e(TAG, "Error in stopPlayback", e)
        }
    }

    /**
     * Clean up all resources (recording and playback).
     * Called when the session is completely done.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up audio manager")
        stopRecording()
        stopPlayback()
    }
}
