package uk.co.mrsheep.halive.services.geminidirect

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug recorder that captures model audio exactly as received from the
 * Gemini Live API, writing one WAV file per model turn.
 *
 * The capture point is the base64 payload straight off the WebSocket message,
 * BEFORE the playback pipeline (decode queue, jitter buffer, AudioTrack), so
 * the file reflects what the server sent even if playback drops or garbles it.
 * Comparing the file against what was heard tells us whether an artifact
 * originates upstream or in local playback.
 *
 * Runs entirely on its own worker coroutine so recording can never affect
 * playback timing. Chunks and turn boundaries flow through a single ordered
 * channel, preserving arrival order.
 *
 * Files are saved to Downloads/HALive (via MediaStore on Android 10+, or the
 * app's external files dir on older devices).
 */
class ReceivedAudioRecorder(
    private val context: Context,
    private val logger: AppLogger? = null
) {
    companion object {
        private const val TAG = "ReceivedAudioRecorder"

        // Gemini Live output format: 24kHz 16-bit mono PCM
        private const val SAMPLE_RATE = 24000
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 1000

        private const val RELATIVE_DIR = "HALive"
    }

    private sealed class Command {
        data class Chunk(val base64Data: String) : Command()
        data class EndTurn(val reason: String) : Command()
    }

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("ReceivedAudioRecorder")
    )
    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)

    // Only touched by the worker coroutine
    private val currentTurn = ByteArrayOutputStream()

    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val logTimestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Chunk -> appendChunk(command.base64Data)
                    is Command.EndTurn -> finishTurn(command.reason)
                }
            }
            // Channel closed - flush anything left from a partial turn
            finishTurn("sessionClosed")
        }
    }

    private fun debugLog(detail: String, success: Boolean = true) {
        logger?.addLogEntry(LogEntry(
            timestamp = logTimestampFormat.format(Date()),
            toolName = "Audio: Recorder",
            parameters = "",
            success = success,
            result = detail
        ))
    }

    /**
     * Queue a received base64 audio chunk. Non-blocking; call in arrival order.
     */
    fun onAudioChunk(base64Data: String) {
        commands.trySend(Command.Chunk(base64Data))
    }

    /**
     * Mark a turn boundary (turn complete, interruption). Writes the WAV file
     * for everything received since the previous boundary.
     */
    fun onTurnBoundary(reason: String) {
        commands.trySend(Command.EndTurn(reason))
    }

    /**
     * Stop recording, flushing any partial turn to file.
     * Safe to call multiple times.
     */
    fun close() {
        commands.close()
    }

    private fun appendChunk(base64Data: String) {
        try {
            currentTurn.write(Base64.decode(base64Data, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio chunk for recording", e)
        }
    }

    private fun finishTurn(reason: String) {
        if (currentTurn.size() == 0) return

        val pcmData = currentTurn.toByteArray()
        currentTurn.reset()

        val fileName = "halive-rx-${fileTimestampFormat.format(Date())}-$reason.wav"
        try {
            val location = writeWavFile(fileName, pcmData)
            val durationMs = pcmData.size / BYTES_PER_MS
            Log.i(TAG, "Saved received audio: $location (${durationMs}ms)")
            debugLog("Saved ~${durationMs}ms of received audio to $location")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save received audio", e)
            debugLog("Failed to save received audio: ${e.message}", success = false)
        }
    }

    /** Writes a WAV file and returns a human-readable location. */
    private fun writeWavFile(fileName: String, pcmData: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DIR")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { stream ->
                writeWav(stream, pcmData)
            } ?: throw IllegalStateException("Could not open output stream")
            "Downloads/$RELATIVE_DIR/$fileName"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), RELATIVE_DIR)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use { stream ->
                writeWav(stream, pcmData)
            }
            file.absolutePath
        }
    }

    /** Writes a standard 44-byte WAV header followed by the PCM data. */
    private fun writeWav(stream: OutputStream, pcmData: ByteArray) {
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        val dataSize = pcmData.size

        val header = ByteArray(44)
        fun putString(offset: Int, s: String) {
            for (i in s.indices) header[offset + i] = s[i].code.toByte()
        }
        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        putString(0, "RIFF")
        putIntLE(4, 36 + dataSize)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putIntLE(16, 16) // fmt chunk size
        putShortLE(20, 1) // PCM format
        putShortLE(22, channels)
        putIntLE(24, SAMPLE_RATE)
        putIntLE(28, byteRate)
        putShortLE(32, blockAlign)
        putShortLE(34, BYTES_PER_SAMPLE * 8) // bits per sample
        putString(36, "data")
        putIntLE(40, dataSize)

        stream.write(header)
        stream.write(pcmData)
        stream.flush()
    }
}
