package uk.co.mrsheep.halive.services

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.services.wake.OwwModel
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

/**
 * Manages wake word detection using AudioRecord and ONNX Runtime inference.
 *
 * Captures 16kHz mono PCM audio, processes it in 1152-sample chunks, and runs
 * ONNX Runtime inference via OwwModel. Triggers callback when detection confidence
 * exceeds the configured threshold.
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
        private const val WAKE_WORD_THRESHOLD = 0.5f
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isListening = false

    /**
     * Lazy initialization of wake word models.
     * Models are loaded once and reused across multiple listening sessions for battery efficiency.
     * Only closed when the service is destroyed.
     */
    private val owwModel: OwwModel by lazy {
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

        Log.d(TAG, "Initializing wake word models (once per service lifetime)")
        OwwModel(melModel, embModel, wakeModel).also {
            Log.d(TAG, "Wake word models initialized successfully")
        }
    }

    /**
     * Starts listening for the wake word.
     *
     * If already listening, returns early without error. AudioRecord requires
     * the RECORD_AUDIO permission, which is checked by MainActivity at app startup.
     *
     * The method initializes the model, creates an AudioRecord instance, and launches
     * a coroutine on Dispatchers.IO to process audio chunks in real-time.
     *
     * Note: Permission warning is suppressed because MainActivity verifies RECORD_AUDIO
     * permission before calling ViewModel lifecycle methods that trigger this function.
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening for wake word, ignoring startListening() call")
            return
        }

        // Verify models can be loaded (lazy init happens here on first access)
        try {
            // Touch the lazy property to trigger initialization if needed
            owwModel.resetAccumulators()
            Log.d(TAG, "Wake word models ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake word models: ${e.message}", e)
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (bufferSize <= 0) {
                Log.e(TAG, "Invalid AudioRecord buffer size: $bufferSize")
                cleanup()
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            val recorder = audioRecord
            if (recorder == null) {
                Log.e(TAG, "Failed to create AudioRecord instance")
                cleanup()
                return
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized properly")
                recorder.release()
                audioRecord = null
                cleanup()
                return
            }

            recorder.startRecording()
            isListening = true
            Log.d(
                TAG,
                "Started listening for wake word (sampleRate=$SAMPLE_RATE, chunkSize=$CHUNK_SIZE)"
            )

            recordingJob = scope.launch {
                val audioBuffer = ShortArray(CHUNK_SIZE)
                val floatBuffer = FloatArray(CHUNK_SIZE)

                while (isActive && isListening) {
                    try {
                        val samplesRead = recorder.read(audioBuffer, 0, CHUNK_SIZE)

                        if (samplesRead == CHUNK_SIZE) {
                            // Convert 16-bit PCM samples to float range (-1.0 to 1.0)
                            for (i in 0 until CHUNK_SIZE) {
                                floatBuffer[i] = audioBuffer[i] / 32768.0f
                            }

                            // Run ONNX Runtime inference on audio chunk
                            try {
                                val detectionScore = owwModel.processFrame(floatBuffer)

                                if (detectionScore > WAKE_WORD_THRESHOLD) {
                                    Log.i(
                                        TAG,
                                        "Wake word detected! Score: %.4f (threshold: $WAKE_WORD_THRESHOLD)".format(
                                            detectionScore
                                        )
                                    )
                                    isListening = false

                                    // Trigger callback on main dispatcher
                                    withContext(Dispatchers.Main) {
                                        onWakeWordDetected()
                                    }

                                    // Exit audio processing loop
                                    return@launch
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error running ONNX Runtime inference: ${e.message}", e)
                            }
                        } else if (samplesRead < 0) {
                            Log.e(TAG, "AudioRecord read error: $samplesRead")
                            isListening = false
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in audio processing loop: ${e.message}", e)
                        isListening = false
                        return@launch
                    }
                }

                Log.d(TAG, "Audio processing loop completed")
            }
        } catch (s: SecurityException) {
            Log.e(TAG, "No permission to listen (should be handled above): ${s.message}")
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
     * If not currently listening, logs a warning and returns.
     */
    fun stopListening() {
        if (!isListening) {
            Log.w(TAG, "Not currently listening for wake word")
            return
        }

        isListening = false
        Log.d(TAG, "Stopping wake word listener")

        recordingJob?.cancel()
        recordingJob = null

        cleanup()
    }

    /**
     * Cleans up audio resources.
     *
     * Safely stops and releases AudioRecord. Note: Model is NOT closed here
     * for battery efficiency - it's reused across listening sessions.
     */
    private fun cleanup() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                    Log.d(TAG, "AudioRecord stopped")
                }
                it.release()
                Log.d(TAG, "AudioRecord released")
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}", e)
        }
    }

    /**
     * Releases all resources and cancels all coroutines.
     *
     * Should be called when the wake word service is no longer needed.
     * After calling this, the service cannot be reused.
     */
    fun destroy() {
        stopListening()

        // Close ONNX models only on destroy (not after each listening session)
        try {
            if (::owwModel.isInitialized) {
                owwModel.close()
                Log.d(TAG, "OwwModel closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing OwwModel: ${e.message}", e)
        }

        scope.cancel()
        Log.d(TAG, "WakeWordService destroyed")
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
}
