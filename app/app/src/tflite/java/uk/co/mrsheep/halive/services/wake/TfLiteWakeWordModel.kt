package uk.co.mrsheep.halive.services.wake

import com.google.ai.edge.litert.Interpreter
import uk.co.mrsheep.halive.core.WakeWordSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TensorFlow Lite implementation of the wake word detection model.
 *
 * This implementation provides the same 3-stage pipeline as the ONNX runtime:
 * 1. Melspectrogram extraction from raw audio frames [1, 1152] -> [1, 1, 5, 32]
 * 2. Embedding generation using a neural network encoder [1, 76, 32, 1] -> [1, 1, 1, 96]
 * 3. Wake word detection classification to produce a detection score [1, 16, 96] -> [1, 1]
 *
 * The implementation uses TensorFlow Lite Interpreter for inference and maintains
 * the same accumulation buffers for mel features and embeddings.
 *
 * @param melSpectrogramFile ByteArray containing the TFLite mel spectrogram model
 * @param embeddingFile ByteArray containing the TFLite embedding model
 * @param wakeWordFile ByteArray containing the TFLite wake word detection model
 * @param settings WakeWordSettings configuring thread count and other parameters
 *
 * @author Home Assistant Live
 */
class TfLiteWakeWordModel(
    melSpectrogramFile: ByteArray,
    embeddingFile: ByteArray,
    wakeWordFile: ByteArray,
    settings: WakeWordSettings
) : WakeWordModel {

    private val melInterpreter: Interpreter
    private val embInterpreter: Interpreter
    private val wakeInterpreter: Interpreter

    private var accumulatedMelOutputs: Array<Array<FloatArray>> = Array(EMB_INPUT_COUNT) { arrayOf() }
    private var accumulatedEmbOutputs: Array<FloatArray> = Array(WAKE_INPUT_COUNT) { floatArrayOf() }

    // Pre-allocated buffers for inference
    private val melInputBuffer = Array(1) { FloatArray(MEL_INPUT_COUNT) }
    private val melOutputBuffer = Array(1) { Array(1) { Array(MEL_OUTPUT_COUNT) { FloatArray(MEL_FEATURE_SIZE) } } }

    private val embInputBuffer = Array(1) { Array(EMB_INPUT_COUNT) { Array(MEL_FEATURE_SIZE) { FloatArray(1) } } }
    private val embOutputBuffer = Array(1) { Array(1) { Array(1) { FloatArray(EMB_FEATURE_SIZE) } } }

    private val wakeInputBuffer = Array(1) { Array(WAKE_INPUT_COUNT) { FloatArray(EMB_FEATURE_SIZE) } }
    private val wakeOutputBuffer = Array(1) { FloatArray(1) }

    // Pre-allocated buffer for mel output transformation (battery optimization)
    private val transformBuffer = Array(MEL_FEATURE_SIZE) { FloatArray(1) }

    init {
        // The melspectrogram TFLite model now has static input shape [1, 1280]
        // No resizeInput needed - the model was converted with fixed dimensions
        melInterpreter = try {
            loadModel(melSpectrogramFile, settings)
        } catch (t: Throwable) {
            throw t
        }

        embInterpreter = try {
            loadModel(embeddingFile, settings)
        } catch (t: Throwable) {
            melInterpreter.close()
            throw t
        }

        wakeInterpreter = try {
            loadModel(wakeWordFile, settings)
        } catch (t: Throwable) {
            melInterpreter.close()
            embInterpreter.close()
            throw t
        }
    }

    override fun processFrame(audio: FloatArray): Float {
        if (audio.size != MEL_INPUT_COUNT) {
            throw IllegalArgumentException(
                "TfLiteWakeWordModel can only process audio frames of $MEL_INPUT_COUNT samples"
            )
        }

        // Copy audio into pre-allocated input buffer
        audio.copyInto(melInputBuffer[0])

        // Run mel spectrogram model: input [1, 1152] -> output [1, 1, 5, 32]
        melInterpreter.run(melInputBuffer, melOutputBuffer)

        // Extract mel output and reshape from [1, 1, 5, 32] to [5, 32]
        val melOutput = melOutputBuffer[0][0] // [5, 32]

        // Accumulate mel outputs with sliding window buffer
        // Use pre-allocated buffer to avoid allocations in hot path (battery optimization)
        for (i in 0..<EMB_INPUT_COUNT) {
            accumulatedMelOutputs[i] = if (i < EMB_INPUT_COUNT - MEL_OUTPUT_COUNT) {
                accumulatedMelOutputs[i + MEL_OUTPUT_COUNT]
            } else {
                val sourceRow = melOutput[i - EMB_INPUT_COUNT + MEL_OUTPUT_COUNT]
                for (j in sourceRow.indices) {
                    transformBuffer[j][0] = (sourceRow[j] / 10.0f) + 2.0f
                }
                // Return a copy to avoid reference issues with the shared buffer
                Array(MEL_FEATURE_SIZE) { j -> floatArrayOf(transformBuffer[j][0]) }
            }
        }
        if (accumulatedMelOutputs[0].isEmpty()) {
            return 0.0f // not fully initialized yet
        }

        // Prepare embedding model input from accumulated mel outputs
        for (i in 0..<EMB_INPUT_COUNT) {
            for (j in 0..<MEL_FEATURE_SIZE) {
                embInputBuffer[0][i][j][0] = accumulatedMelOutputs[i][j][0]
            }
        }

        // Run embedding model: input [1, 76, 32, 1] -> output [1, 1, 1, 96]
        embInterpreter.run(embInputBuffer, embOutputBuffer)

        // Extract embedding output and reshape from [1, 1, 1, 96] to [96]
        val embOutput = embOutputBuffer[0][0][0] // FloatArray[96]

        // Accumulate embedding outputs with sliding window buffer
        // Since EMB_OUTPUT_COUNT = 1, we add one new embedding per frame
        for (i in 0..<WAKE_INPUT_COUNT) {
            accumulatedEmbOutputs[i] = if (i < WAKE_INPUT_COUNT - EMB_OUTPUT_COUNT) {
                accumulatedEmbOutputs[i + EMB_OUTPUT_COUNT]
            } else {
                // Copy embedding to avoid reference issues - without copyOf(), all 16
                // slots would point to the same array, destroying temporal context
                embOutput.copyOf()
            }
        }
        if (accumulatedEmbOutputs[0].isEmpty()) {
            return 0.0f // not fully initialized yet
        }

        // Prepare wake word model input from accumulated embeddings
        for (i in 0..<WAKE_INPUT_COUNT) {
            for (j in 0..<EMB_FEATURE_SIZE) {
                wakeInputBuffer[0][i][j] = accumulatedEmbOutputs[i][j]
            }
        }

        // Run wake word detection model: input [1, 16, 96] -> output [1, 1]
        wakeInterpreter.run(wakeInputBuffer, wakeOutputBuffer)

        // Extract wake word output score
        return wakeOutputBuffer[0][0]
    }

    /**
     * Resets the accumulation buffers to initial state.
     * Call this when starting a new listening session to clear stale data.
     */
    override fun resetAccumulators() {
        accumulatedMelOutputs = Array(EMB_INPUT_COUNT) { arrayOf() }
        accumulatedEmbOutputs = Array(WAKE_INPUT_COUNT) { floatArrayOf() }
    }

    override fun close() {
        melInterpreter.close()
        embInterpreter.close()
        wakeInterpreter.close()
    }

    companion object {
        // mel model shape is [1,x] -> [1,1,floor((x-512)/160)+1,32]
        // Using 1280 samples to match Python openWakeWord default and static TFLite model
        const val MEL_INPUT_COUNT = 1280 // 1280 samples @ 16kHz = 80ms
        const val MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) / 160 + 1 // = 5
        const val MEL_FEATURE_SIZE = 32 // also the size of features received by the emb model

        // emb model shape is [1,76,32,1] -> [1,1,1,96]
        const val EMB_INPUT_COUNT = 76 // hardcoded in the model
        const val EMB_OUTPUT_COUNT = 1
        const val EMB_FEATURE_SIZE = 96 // also the size of features received by the wake model

        // wake model shape is [1,16,96] -> [1,1]
        const val WAKE_INPUT_COUNT = 16 // hardcoded in the model

        private fun loadModel(model: ByteArray, settings: WakeWordSettings): Interpreter {
            try {
                // TFLite Interpreter requires ByteBuffer, not ByteArray
                val byteBuffer = ByteBuffer.allocateDirect(model.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(model)
                    rewind()
                }
                // Create TFLite Interpreter with thread count from settings
                val options = Interpreter.Options().setNumThreads(settings.threadCount)
                return Interpreter(byteBuffer, options)
            } catch (t: Throwable) {
                throw Exception("Failed to load TFLite model", t)
            }
        }
    }
}
