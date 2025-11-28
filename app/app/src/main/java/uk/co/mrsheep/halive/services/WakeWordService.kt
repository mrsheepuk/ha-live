package uk.co.mrsheep.halive.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import uk.co.mrsheep.halive.services.geminidirect.AudioHelper
import uk.co.mrsheep.halive.services.geminidirect.toFloatChunks
import uk.co.mrsheep.halive.services.geminidirect.toFloatChunksWithCapture
import uk.co.mrsheep.halive.services.wake.OwwModel
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages wake word detection using AudioHelper and ONNX Runtime inference.
 *
 * Captures 16kHz mono PCM audio via the shared AudioHelper, processes it in
 * 1152-sample chunks using Flow operators, and runs ONNX Runtime inference via
 * OwwModel. Triggers callback when detection confidence exceeds the configured threshold.
 *
 * @param context Application context for accessing files and audio resources
 * @param onWakeWordDetected Callback invoked on the main dispatcher when wake word is detected
 */
class WakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordService"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 1152 // OwwModel.MEL_INPUT_COUNT

        // Number of frames to skip after starting to allow model to warm up.
        // The ONNX model needs time to fill its accumulators and stabilize.
        // At 16kHz with 1152-sample chunks, each frame is ~72ms, so 20 frames ≈ 1.4 seconds
        private const val WARMUP_FRAMES = 20
    }

    private var audioHelper: AudioHelper? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isListening = false
    private var currentSettings: WakeWordSettings = WakeWordConfig.getSettings(context)
    private var framesProcessed = 0

    // Audio dump for debugging - saves raw audio to WAV file
    private var audioDumpEnabled = false
    private var audioDumpStream: FileOutputStream? = null
    private var audioDumpFile: File? = null
    private var audioDumpBytesWritten = 0

    /**
     * Test mode callback - if set, every detection score is reported to this callback.
     * Used for real-time threshold tuning in settings UI.
     */
    private var testModeCallback: ((Float) -> Unit)? = null

    /**
     * Wake word model instance. Created on-demand and can be recreated when settings change.
     * Reused across multiple listening sessions for battery efficiency.
     */
    private var owwModel: OwwModel? = null

    /**
     * Initializes or returns the existing wake word model.
     * Models are loaded with current settings and reused until explicitly closed.
     */
    private fun getOrCreateModel(): OwwModel {
        owwModel?.let { return it }

        val melModel = loadModelFile("melspectrogram.onnx")
        val embModel = loadModelFile("embedding_model.onnx")
        val wakeModel = loadModelFile("ok_computer.onnx")

        if (!melModel.exists() || !embModel.exists() || !wakeModel.exists()) {
            Log.e(TAG, "One or more model files not found in ${context.filesDir}")
            Log.e(TAG, "  melspectrogram.onnx: ${melModel.exists()}")
            Log.e(TAG, "  embedding_model.onnx: ${embModel.exists()}")
            Log.e(TAG, "  ok_computer.onnx: ${wakeModel.exists()}")
            throw IllegalStateException("Wake word model files not found")
        }

        Log.d(TAG, "Initializing wake word models with current settings")
        return OwwModel(melModel, embModel, wakeModel, currentSettings).also {
            owwModel = it
            Log.d(TAG, "Wake word models initialized successfully")
        }
    }

    /**
     * Starts listening for the wake word.
     *
     * If already listening, returns early without error. AudioRecord requires
     * the RECORD_AUDIO permission, which is checked by MainActivity at app startup.
     *
     * The method initializes the model, creates an AudioHelper instance, and launches
     * a Flow-based coroutine to process audio chunks in real-time.
     *
     * Note: Permission warning is suppressed because MainActivity verifies RECORD_AUDIO
     * permission before calling ViewModel lifecycle methods that trigger this function.
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening for wake word, ignoring startListening() call")
            return
        }

        // Verify models can be loaded and initialize if needed
        try {
            getOrCreateModel().resetAccumulators()
            Log.d(TAG, "Wake word models ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake word models: ${e.message}", e)
            return
        }

        try {
            audioHelper = AudioHelper.build(SAMPLE_RATE)
            isListening = true
            framesProcessed = 0  // Reset warm-up counter
            Log.d(TAG, "Started listening for wake word (sampleRate=$SAMPLE_RATE, chunkSize=$CHUNK_SIZE, threshold=${currentSettings.threshold}, warmup=$WARMUP_FRAMES frames)")

            val model = getOrCreateModel()

            // Choose flow operator based on whether audio dump is enabled
            val audioFlow = audioHelper!!.listenToRecording()
                .onStart { Log.d(TAG, "Audio flow started (upstream -> toFloatChunks)") }

            val floatFlow = if (audioDumpEnabled) {
                Log.d(TAG, "Audio dump is ENABLED - capturing raw bytes to file")
                audioFlow.toFloatChunksWithCapture(CHUNK_SIZE) { bytes ->
                    writeAudioDumpBytes(bytes)
                }
            } else {
                audioFlow.toFloatChunks(CHUNK_SIZE)
            }

            recordingJob = floatFlow
                .onStart { Log.d(TAG, "Float chunks flow started (toFloatChunks -> onEach)") }
                .onEach { floatBuffer ->
                    try {
                        val detectionScore = model.processFrame(floatBuffer)
                        framesProcessed++

                        // Log progress periodically (every 10 frames) or always in test mode
                        val isTestMode = testModeCallback != null
                        if (isTestMode || framesProcessed % 10 == 0 || framesProcessed <= 5) {
                            val warmupStatus = if (framesProcessed <= WARMUP_FRAMES) "WARMUP" else "ACTIVE"
                            Log.d(TAG, "Frame $framesProcessed [$warmupStatus]: score=%.4f, threshold=${currentSettings.threshold}, testMode=$isTestMode".format(detectionScore))
                        }

                        // Test mode: report every score to callback
                        testModeCallback?.let { callback ->
                            Log.v(TAG, "Invoking test mode callback with score=%.4f".format(detectionScore))
                            withContext(Dispatchers.Main) {
                                callback(detectionScore)
                            }
                        }

                        // Normal mode: only trigger on threshold (skip if in test mode or warming up)
                        if (testModeCallback == null && framesProcessed > WARMUP_FRAMES && detectionScore > currentSettings.threshold) {
                            Log.i(TAG, "Wake word detected! Score: %.4f (threshold: ${currentSettings.threshold})".format(detectionScore))
                            cleanup()
                            withContext(Dispatchers.Main) {
                                onWakeWordDetected()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error running ONNX inference: ${e.message}", e)
                    }
                }
                .onCompletion { cause ->
                    if (cause != null) {
                        Log.d(TAG, "Flow completed with exception: ${cause.message}")
                    } else {
                        Log.d(TAG, "Flow completed normally (this shouldn't happen - flow is infinite)")
                    }
                }
                .catch { e ->
                    Log.e(TAG, "Error in audio stream: ${e.message}", e)
                    // Check if we were intentionally listening before cleanup resets the flag
                    val shouldRestart = isListening
                    cleanup()
                    // Auto-restart listening on error (unless we were stopping intentionally)
                    if (shouldRestart) {
                        Log.d(TAG, "Restarting wake word listening after error")
                        startListening()
                    }
                }
                .launchIn(scope)

        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to record audio: ${e.message}")
            isListening = false
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake word listening: ${e.message}", e)
            isListening = false
            cleanup()
        }
    }

    /**
     * Stops listening for the wake word.
     *
     * Cancels the audio processing coroutine and releases audio resources.
     * If not currently listening, logs a debug message and returns.
     */
    fun stopListening() {
        if (!isListening && audioHelper == null) {
            Log.d(TAG, "Not currently listening for wake word")
            return
        }

        Log.d(TAG, "Stopping wake word listener${if (testModeCallback != null) " (test mode)" else ""}")
        cleanup()
    }

    /**
     * Starts test mode for real-time score monitoring.
     * In test mode, every detection score is reported via the callback.
     * Wake word detection is disabled - this is for testing only.
     *
     * @param onScore Callback invoked with each detection score (called on Main dispatcher)
     * @param withAudioDump If true, also saves audio to a WAV file for debugging
     * @return The audio dump file if withAudioDump is true, null otherwise
     */
    fun startTestMode(onScore: (Float) -> Unit, withAudioDump: Boolean = false): File? {
        val dumpFile = if (withAudioDump) enableAudioDump() else null
        testModeCallback = onScore
        startListening()
        Log.d(TAG, "Test mode started${if (withAudioDump) " with audio dump" else ""}")
        return dumpFile
    }

    /**
     * Stops test mode and cleans up resources.
     * If audio dump was enabled, finalizes the WAV file.
     *
     * @return The finalized audio dump file if one was active, null otherwise
     */
    fun stopTestMode(): File? {
        testModeCallback = null
        stopListening()
        val dumpFile = stopAudioDump()
        Log.d(TAG, "Test mode stopped${if (dumpFile != null) ", audio saved to ${dumpFile.absolutePath}" else ""}")
        return dumpFile
    }

    /**
     * Returns true if currently in test mode.
     */
    fun isTestMode(): Boolean = testModeCallback != null

    /**
     * Cleans up audio resources.
     *
     * Cancels the recording job and releases AudioHelper. Note: Model is NOT closed here
     * for battery efficiency - it's reused across listening sessions.
     */
    private fun cleanup() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        audioHelper?.release()
        audioHelper = null
    }

    /**
     * Releases all resources and cancels all coroutines.
     *
     * Should be called when the wake word service is no longer needed.
     * After calling this, the service cannot be reused.
     */
    fun destroy() {
        testModeCallback = null
        stopListening()

        // Close ONNX models only on destroy (not after each listening session)
        owwModel?.let {
            try {
                it.close()
                Log.d(TAG, "OwwModel closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing OwwModel: ${e.message}", e)
            }
        }
        owwModel = null

        scope.cancel()
        Log.d(TAG, "WakeWordService destroyed")
    }

    /**
     * Reloads wake word settings from SharedPreferences.
     * If currently listening, stops and restarts with new settings.
     */
    fun reloadSettings() {
        currentSettings = WakeWordConfig.getSettings(context)

        if (isListening) {
            stopListening()

            // Force re-initialization of model with new settings on next start
            owwModel?.let {
                try {
                    it.close()
                    Log.d(TAG, "Closed old model to apply new settings")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing model during reload: ${e.message}", e)
                }
            }
            owwModel = null

            startListening()
        }
    }

    /**
     * Returns a File object pointing to a model file in the application's files directory.
     * The file should be copied there by AssetCopyUtil during app startup.
     *
     * @param filename Name of the model file (e.g., "melspectrogram.onnx")
     * @return File object pointing to the model in filesDir
     */
    private fun loadModelFile(filename: String): File {
        return File(context.filesDir, filename)
    }

    // ==================== Audio Dump Debug Feature ====================

    /**
     * Enables audio dumping for debugging. When enabled, all audio sent to the
     * wake word model will be saved to a WAV file in the app's external files directory.
     * This makes the file accessible via file manager or adb pull without root.
     *
     * Location: Android/data/uk.co.mrsheep.halive/files/wake_word_debug_*.wav
     *
     * Call this BEFORE startListening() or startTestMode().
     * Call stopAudioDump() after stopping to finalize the WAV file.
     *
     * @return The File where audio will be saved, or null if failed
     */
    fun enableAudioDump(): File? {
        try {
            val timestamp = System.currentTimeMillis()
            // Use external files directory for accessibility (same as CrashLogger)
            val externalDir = context.getExternalFilesDir(null)
                ?: context.filesDir // Fallback to internal if external not available
            audioDumpFile = File(externalDir, "wake_word_debug_$timestamp.wav")
            audioDumpStream = FileOutputStream(audioDumpFile)
            audioDumpBytesWritten = 0

            // Write placeholder WAV header (44 bytes) - will update sizes at end
            writeWavHeader(audioDumpStream!!, 0)

            audioDumpEnabled = true
            Log.i(TAG, "Audio dump enabled: ${audioDumpFile?.absolutePath}")
            return audioDumpFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable audio dump: ${e.message}", e)
            audioDumpEnabled = false
            return null
        }
    }

    /**
     * Stops audio dumping and finalizes the WAV file.
     * The file can then be played back to verify audio capture quality.
     *
     * @return The finalized WAV file, or null if no dump was active
     */
    fun stopAudioDump(): File? {
        if (!audioDumpEnabled) return null

        try {
            // Flush and close the stream to ensure all data is written
            audioDumpStream?.flush()
            audioDumpStream?.close()

            // Update WAV header with actual sizes
            audioDumpFile?.let { file ->
                val fileSize = file.length()
                Log.i(TAG, "Audio dump raw file size before header update: $fileSize bytes")
                Log.i(TAG, "Audio dump bytes written counter: $audioDumpBytesWritten bytes")

                RandomAccessFile(file, "rw").use { raf ->
                    // Update RIFF chunk size (file size - 8)
                    raf.seek(4)
                    raf.write(intToLittleEndianBytes(36 + audioDumpBytesWritten))

                    // Update data chunk size
                    raf.seek(40)
                    raf.write(intToLittleEndianBytes(audioDumpBytesWritten))
                }

                val finalSize = file.length()
                val durationSeconds = audioDumpBytesWritten / 32000f
                Log.i(TAG, "Audio dump finalized: ${file.absolutePath}")
                Log.i(TAG, "  Final file size: $finalSize bytes (header: 44, audio: $audioDumpBytesWritten)")
                Log.i(TAG, "  Duration: %.2f seconds".format(durationSeconds))
            }

            val result = audioDumpFile
            audioDumpEnabled = false
            audioDumpStream = null
            audioDumpFile = null
            audioDumpBytesWritten = 0
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize audio dump: ${e.message}", e)
            audioDumpEnabled = false
            return null
        }
    }

    /**
     * Writes raw PCM bytes to the audio dump file (if enabled).
     * Called internally during audio processing.
     */
    private fun writeAudioDumpBytes(bytes: ByteArray) {
        if (!audioDumpEnabled) return
        try {
            audioDumpStream?.write(bytes)
            audioDumpBytesWritten += bytes.size
            // Log every 100 chunks (~7 seconds at 72ms/chunk) to show progress
            if ((audioDumpBytesWritten / bytes.size) % 100 == 0) {
                Log.d(TAG, "Audio dump progress: $audioDumpBytesWritten bytes written (${audioDumpBytesWritten / 32000f} seconds)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audio dump: ${e.message}", e)
        }
    }

    /**
     * Writes a WAV file header for 16-bit mono 16kHz PCM audio.
     */
    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)  // File size - 8
        buffer.put("WAVE".toByteArray())

        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                     // Subchunk1Size (16 for PCM)
        buffer.putShort(1.toShort())          // AudioFormat (1 = PCM)
        buffer.putShort(1.toShort())          // NumChannels (1 = mono)
        buffer.putInt(SAMPLE_RATE)            // SampleRate (16000)
        buffer.putInt(SAMPLE_RATE * 2)        // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
        buffer.putShort(2.toShort())          // BlockAlign (NumChannels * BitsPerSample/8)
        buffer.putShort(16.toShort())         // BitsPerSample

        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)        // Subchunk2Size

        out.write(buffer.array())
    }

    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
