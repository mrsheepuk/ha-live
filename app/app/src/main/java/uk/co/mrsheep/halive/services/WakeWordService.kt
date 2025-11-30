package uk.co.mrsheep.halive.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordRuntime
import uk.co.mrsheep.halive.core.WakeWordSettings
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper
import uk.co.mrsheep.halive.services.audio.toFloatChunks
import uk.co.mrsheep.halive.services.wake.OnnxWakeWordModel
import uk.co.mrsheep.halive.services.wake.TfLiteWakeWordModel
import uk.co.mrsheep.halive.services.wake.WakeWordModel

/**
 * Manages wake word detection using MicrophoneHelper and configurable inference runtime.
 *
 * Captures 16kHz mono PCM audio via the shared MicrophoneHelper, processes it in
 * 1152-sample chunks using Flow operators, and runs inference via the configured
 * runtime (ONNX or TFLite). Triggers callback when detection confidence exceeds
 * the configured threshold.
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
        private const val CHUNK_SIZE = 1152 // OnnxWakeWordModel.MEL_INPUT_COUNT

        // Number of frames to skip after starting to allow model to warm up.
        // The model needs time to fill its accumulators and stabilize.
        // At 16kHz with 1152-sample chunks, each frame is ~72ms, so 20 frames ≈ 1.4 seconds
        private const val WARMUP_FRAMES = 20
    }

    private var microphoneHelper: MicrophoneHelper? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isListening = false
    private var currentSettings: WakeWordSettings = WakeWordConfig.getSettings(context)
    private var framesProcessed = 0

    /**
     * Test mode callback - if set, every detection score is reported to this callback.
     * Used for real-time threshold tuning in settings UI.
     */
    private var testModeCallback: ((Float) -> Unit)? = null

    /**
     * Wake word model instance. Created on-demand and can be recreated when settings change.
     * Reused across multiple listening sessions for battery efficiency.
     */
    private var owwModel: WakeWordModel? = null

    /**
     * Initializes or returns the existing wake word model.
     * Models are loaded with current settings and reused until explicitly closed.
     * The runtime (ONNX or TFLite) is determined by currentSettings.runtime.
     */
    private fun getOrCreateModel(): WakeWordModel {
        owwModel?.let { return it }

        val runtime = currentSettings.runtime
        val extension = when (runtime) {
            WakeWordRuntime.ONNX -> ".onnx"
            WakeWordRuntime.TFLITE -> ".tflite"
        }

        val melModel = getAssetData("melspectrogram$extension")
        val embModel = getAssetData("embedding_model$extension")
        val wakeModel = getAssetData("lizzy_aitch$extension")

        Log.d(TAG, "Initializing wake word models with runtime=$runtime")
        return when (runtime) {
            WakeWordRuntime.ONNX -> OnnxWakeWordModel(melModel, embModel, wakeModel, currentSettings)
            WakeWordRuntime.TFLITE -> TfLiteWakeWordModel(melModel, embModel, wakeModel, currentSettings)
        }.also {
            owwModel = it
            Log.d(TAG, "Wake word models initialized successfully (runtime=$runtime)")
        }
    }

    /**
     * Starts listening for the wake word.
     *
     * If already listening, returns early without error. AudioRecord requires
     * the RECORD_AUDIO permission, which is checked by MainActivity at app startup.
     *
     * The method initializes the model, creates a MicrophoneHelper instance, and launches
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
            microphoneHelper = MicrophoneHelper.build(SAMPLE_RATE)
            microphoneHelper?.enablePreBuffering(1500)  // Buffer 1.5 seconds of audio
            isListening = true
            framesProcessed = 0  // Reset warm-up counter
            Log.d(TAG, "Started listening for wake word (sampleRate=$SAMPLE_RATE, chunkSize=$CHUNK_SIZE, threshold=${currentSettings.threshold}, warmup=$WARMUP_FRAMES frames)")

            launchRecordingPipeline()

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
     * Creates and launches the recording pipeline for wake word detection.
     * Extracted to allow reuse when resuming with an existing MicrophoneHelper.
     */
    private fun launchRecordingPipeline() {
        val helper = microphoneHelper ?: return
        val model = getOrCreateModel()

        recordingJob = helper.listenToRecording()
            .onStart { Log.d(TAG, "Audio flow started") }
            .toFloatChunks(CHUNK_SIZE)
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
                        // IMPORTANT: Launch callback in a new coroutine BEFORE cleanup,
                        // because cleanup() cancels this coroutine. Using scope.launch
                        // creates a new job that won't be cancelled by our cleanup.
                        scope.launch(Dispatchers.Main) {
                            onWakeWordDetected()
                        }
                        cleanup()
                        return@onEach // Stop processing after detection
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error running inference: ${e.message}", e)
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
    }

    /**
     * Stops listening for the wake word.
     *
     * Cancels the audio processing coroutine and releases audio resources.
     * If not currently listening, logs a debug message and returns.
     */
    fun stopListening() {
        if (!isListening && microphoneHelper == null) {
            Log.d(TAG, "Not currently listening for wake word")
            return
        }

        Log.d(TAG, "Stopping wake word listener${if (testModeCallback != null) " (test mode)" else ""}")
        cleanup()
    }

    /**
     * Yields the MicrophoneHelper for handover to another service.
     * Pauses recording but does NOT release resources.
     * Returns null if not currently listening or no MicrophoneHelper available.
     *
     * After calling this, the WakeWordService is no longer listening and
     * the caller takes ownership of the MicrophoneHelper.
     */
    fun yieldMicrophoneHelper(): MicrophoneHelper? {
        if (!isListening || microphoneHelper == null) {
            Log.d(TAG, "yieldMicrophoneHelper: not listening or no microphoneHelper, returning null")
            return null
        }

        Log.d(TAG, "Yielding MicrophoneHelper for handover")
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        microphoneHelper?.pauseRecording()

        val helper = microphoneHelper
        microphoneHelper = null  // Transfer ownership
        return helper
    }

    /**
     * Resumes wake word listening with an existing MicrophoneHelper.
     * Used after a conversation session returns the MicrophoneHelper.
     *
     * @param helper The MicrophoneHelper to resume with (takes ownership)
     */
    fun resumeWith(helper: MicrophoneHelper) {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring resumeWith() - releasing provided helper")
            helper.release()
            return
        }

        // Verify MicrophoneHelper is still usable
        if (helper.isReleased) {
            Log.w(TAG, "MicrophoneHelper already released, starting fresh")
            startListening()
            return
        }

        Log.d(TAG, "Resuming wake word listening with existing MicrophoneHelper")

        try {
            // Reset model accumulators for fresh detection
            getOrCreateModel().resetAccumulators()

            microphoneHelper = helper
            microphoneHelper?.clearPreBuffer()  // Fresh start for buffering
            isListening = true
            framesProcessed = 0

            launchRecordingPipeline()
            Log.d(TAG, "Successfully resumed with existing MicrophoneHelper")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming with MicrophoneHelper, starting fresh: ${e.message}", e)
            helper.release()
            startListening()
        }
    }

    /**
     * Starts test mode for real-time score monitoring.
     * In test mode, every detection score is reported via the callback.
     * Wake word detection is disabled - this is for testing only.
     *
     * @param onScore Callback invoked with each detection score (called on Main dispatcher)
     */
    fun startTestMode(onScore: (Float) -> Unit) {
        testModeCallback = onScore
        startListening()
        Log.d(TAG, "Test mode started")
    }

    /**
     * Stops test mode and cleans up resources.
     */
    fun stopTestMode() {
        testModeCallback = null
        stopListening()
        Log.d(TAG, "Test mode stopped")
    }

    /**
     * Returns true if currently in test mode.
     */
    fun isTestMode(): Boolean = testModeCallback != null

    /**
     * Cleans up audio resources.
     *
     * Cancels the recording job and releases MicrophoneHelper. Note: Model is NOT closed here
     * for battery efficiency - it's reused across listening sessions.
     */
    private fun cleanup() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        microphoneHelper?.release()
        microphoneHelper = null
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

        // Close models only on destroy (not after each listening session)
        owwModel?.let {
            try {
                it.close()
                Log.d(TAG, "WakeWordModel closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing WakeWordModel: ${e.message}", e)
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
    private fun getAssetData(filename: String): ByteArray {
        val thing = context.assets.open(filename)
        val fileBytes = ByteArray(thing.available())
        thing.read(fileBytes)
        thing.close()
        return fileBytes
        //return File(context.filesDir, filename)
    }
}
