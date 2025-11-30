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
import uk.co.mrsheep.halive.core.WakeWordSettings
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper
import uk.co.mrsheep.halive.services.audio.toFloatChunks
import uk.co.mrsheep.halive.services.wake.TfLiteWakeWordModel
import uk.co.mrsheep.halive.services.wake.WakeWordModel

/**
 * TFLite flavor implementation of WakeWordService.
 * Uses TensorFlow Lite for wake word detection inference.
 */
class WakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordService"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 1280
        private const val WARMUP_FRAMES = 20
    }

    private var microphoneHelper: MicrophoneHelper? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isListening = false
    private var currentSettings: WakeWordSettings = WakeWordConfig.getSettings(context)
    private var framesProcessed = 0
    private var testModeCallback: ((Float) -> Unit)? = null
    private var owwModel: WakeWordModel? = null

    private fun getOrCreateModel(): WakeWordModel {
        owwModel?.let { return it }

        val melModel = getAssetData("melspectrogram.tflite")
        val embModel = getAssetData("embedding_model.tflite")
        val wakeModel = getAssetData("lizzy_aitch.tflite")

        Log.d(TAG, "Initializing TFLite wake word models")
        return TfLiteWakeWordModel(melModel, embModel, wakeModel, currentSettings).also {
            owwModel = it
            Log.d(TAG, "TFLite wake word models initialized successfully")
        }
    }

    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening for wake word, ignoring startListening() call")
            return
        }

        try {
            getOrCreateModel().resetAccumulators()
            Log.d(TAG, "Wake word models ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake word models: ${e.message}", e)
            return
        }

        try {
            microphoneHelper = MicrophoneHelper.build(SAMPLE_RATE)
            microphoneHelper?.enablePreBuffering(1500)
            isListening = true
            framesProcessed = 0
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

                    val isTestMode = testModeCallback != null
                    if (isTestMode || framesProcessed % 10 == 0 || framesProcessed <= 5) {
                        val warmupStatus = if (framesProcessed <= WARMUP_FRAMES) "WARMUP" else "ACTIVE"
                        Log.d(TAG, "Frame $framesProcessed [$warmupStatus]: score=%.4f, threshold=${currentSettings.threshold}, testMode=$isTestMode".format(detectionScore))
                    }

                    testModeCallback?.let { callback ->
                        Log.v(TAG, "Invoking test mode callback with score=%.4f".format(detectionScore))
                        withContext(Dispatchers.Main) {
                            callback(detectionScore)
                        }
                    }

                    if (testModeCallback == null && framesProcessed > WARMUP_FRAMES && detectionScore > currentSettings.threshold) {
                        Log.i(TAG, "Wake word detected! Score: %.4f (threshold: ${currentSettings.threshold})".format(detectionScore))
                        scope.launch(Dispatchers.Main) {
                            onWakeWordDetected()
                        }
                        cleanup()
                        return@onEach
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
                val shouldRestart = isListening
                cleanup()
                if (shouldRestart) {
                    Log.d(TAG, "Restarting wake word listening after error")
                    startListening()
                }
            }
            .launchIn(scope)
    }

    fun stopListening() {
        if (!isListening && microphoneHelper == null) {
            Log.d(TAG, "Not currently listening for wake word")
            return
        }

        Log.d(TAG, "Stopping wake word listener${if (testModeCallback != null) " (test mode)" else ""}")
        cleanup()
    }

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
        microphoneHelper = null
        return helper
    }

    fun resumeWith(helper: MicrophoneHelper) {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring resumeWith() - releasing provided helper")
            helper.release()
            return
        }

        if (helper.isReleased) {
            Log.w(TAG, "MicrophoneHelper already released, starting fresh")
            startListening()
            return
        }

        Log.d(TAG, "Resuming wake word listening with existing MicrophoneHelper")

        try {
            getOrCreateModel().resetAccumulators()

            microphoneHelper = helper
            microphoneHelper?.clearPreBuffer()
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

    fun startTestMode(onScore: (Float) -> Unit) {
        testModeCallback = onScore
        startListening()
        Log.d(TAG, "Test mode started")
    }

    fun stopTestMode() {
        testModeCallback = null
        stopListening()
        Log.d(TAG, "Test mode stopped")
    }

    fun isTestMode(): Boolean = testModeCallback != null

    private fun cleanup() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        microphoneHelper?.release()
        microphoneHelper = null
    }

    fun destroy() {
        testModeCallback = null
        stopListening()

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

    fun reloadSettings() {
        currentSettings = WakeWordConfig.getSettings(context)

        if (isListening) {
            stopListening()

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

    private fun getAssetData(filename: String): ByteArray {
        val thing = context.assets.open(filename)
        val fileBytes = ByteArray(thing.available())
        thing.read(fileBytes)
        thing.close()
        return fileBytes
    }
}
