package uk.co.mrsheep.halive.services.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import uk.co.mrsheep.halive.core.ExecutionMode
import uk.co.mrsheep.halive.core.OptimizationLevel
import uk.co.mrsheep.halive.core.WakeWordSettings

/**
 * Extension function to convert ExecutionMode to OrtSession.SessionOptions.ExecutionMode.
 * Defined here to keep ONNX-specific code out of the shared WakeWordConfig.
 */
private fun ExecutionMode.toOrtExecutionMode(): OrtSession.SessionOptions.ExecutionMode = when (this) {
    ExecutionMode.SEQUENTIAL -> OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL
    ExecutionMode.PARALLEL -> OrtSession.SessionOptions.ExecutionMode.PARALLEL
}

/**
 * Extension function to convert OptimizationLevel to OrtSession.SessionOptions.OptLevel.
 * Defined here to keep ONNX-specific code out of the shared WakeWordConfig.
 */
private fun OptimizationLevel.toOrtOptLevel(): OrtSession.SessionOptions.OptLevel = when (this) {
    OptimizationLevel.NO_OPT -> OrtSession.SessionOptions.OptLevel.NO_OPT
    OptimizationLevel.BASIC_OPT -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
    OptimizationLevel.EXTENDED_OPT -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
    OptimizationLevel.ALL_OPT -> OrtSession.SessionOptions.OptLevel.ALL_OPT
}

/**
 * ONNX Runtime implementation of the WakeWordModel interface.
 *
 * This implementation uses ONNX Runtime to run three neural network models in sequence:
 * 1. Melspectrogram model - Extracts mel-spectrogram features from raw audio
 * 2. Embedding model - Generates temporal embeddings from melspectrogram features
 * 3. Wake word detection model - Classifies embeddings to produce detection scores
 *
 * @param melSpectrogramFile Serialized ONNX model for melspectrogram extraction
 * @param embeddingFile Serialized ONNX model for embedding generation
 * @param wakeWordFile Serialized ONNX model for wake word detection
 * @param settings Configuration for ONNX Runtime optimization and execution
 */
class OnnxWakeWordModel(
    melSpectrogramFile: ByteArray,
    embeddingFile: ByteArray,
    wakeWordFile: ByteArray,
    settings: WakeWordSettings
) : WakeWordModel {
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val melSession: OrtSession
    private val embSession: OrtSession
    private val wakeSession: OrtSession

    private var accumulatedMelOutputs: Array<Array<FloatArray>> = Array(EMB_INPUT_COUNT) { arrayOf() }
    private var accumulatedEmbOutputs: Array<FloatArray> = Array(WAKE_INPUT_COUNT) { floatArrayOf() }

    // Pre-allocated buffer for mel output transformation (battery optimization)
    private val transformBuffer = Array(MEL_FEATURE_SIZE) { FloatArray(1) }

    init {
        melSession = try {
            loadModel(melSpectrogramFile, settings)
        } catch (t: Throwable) {
            throw t
        }

        embSession = try {
            loadModel(embeddingFile, settings)
        } catch (t: Throwable) {
            melSession.close()
            throw t
        }

        wakeSession = try {
            loadModel(wakeWordFile, settings)
        } catch (t: Throwable) {
            melSession.close()
            embSession.close()
            throw t
        }
    }

    override fun processFrame(audio: FloatArray): Float {
        if (audio.size != MEL_INPUT_COUNT) {
            throw IllegalArgumentException(
                "OwwModel can only process audio frames of $MEL_INPUT_COUNT samples"
            )
        }

        // Run mel spectrogram model: input [1, 1152] -> output [1, 1, 5, 32]
        val melInputName = melSession.inputNames.first()
        val melInput = OnnxTensor.createTensor(
            ortEnvironment,
            arrayOf(audio)  // Shape inferred from array structure: [1, 1152]
        )
        val melResults = melSession.run(mapOf(melInputName to melInput))

        // Extract mel output and reshape from [1, 1, 5, 32] to [5, 32]
        @Suppress("UNCHECKED_CAST")
        val melOutputRaw = melResults[0].value as Array<Array<Array<FloatArray>>>
        val melOutput = melOutputRaw[0][0] // [5, 32]

        melInput.close()
        melResults.close()

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

        // Run embedding model: input [1, 76, 32, 1] -> output [1, 1, 1, 96]
        val embInputName = embSession.inputNames.first()
        val embInput = OnnxTensor.createTensor(
            ortEnvironment,
            arrayOf(accumulatedMelOutputs)  // Shape inferred: [1, 76, 32, 1]
        )
        val embResults = embSession.run(mapOf(embInputName to embInput))

        // Extract embedding output and reshape from [1, 1, 1, 96] to [96]
        @Suppress("UNCHECKED_CAST")
        val embOutputRaw = embResults[0].value as Array<Array<Array<FloatArray>>>
        val embOutput = embOutputRaw[0][0][0] // FloatArray[96]

        embInput.close()
        embResults.close()

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

        // Run wake word detection model: input [1, 16, 96] -> output [1, 1]
        val wakeInputName = wakeSession.inputNames.first()
        val wakeInput = OnnxTensor.createTensor(
            ortEnvironment,
            arrayOf(accumulatedEmbOutputs)  // Shape inferred: [1, 16, 96]
        )
        val wakeResults = wakeSession.run(mapOf(wakeInputName to wakeInput))

        // Extract wake word output score
        @Suppress("UNCHECKED_CAST")
        val wakeOutputRaw = wakeResults[0].value as Array<FloatArray>
        val wakeOutput = wakeOutputRaw[0][0]

        wakeInput.close()
        wakeResults.close()

        return wakeOutput
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
        melSession.close()
        embSession.close()
        wakeSession.close()
    }

    companion object {
        // mel model shape is [1,x] -> [1,1,floor((x-512)/160)+1,32]
        // Using 1280 samples to match Python openWakeWord default
        const val MEL_INPUT_COUNT = 1280 // 1280 samples @ 16kHz = 80ms
        const val MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) / 160 + 1 // = 5
        const val MEL_FEATURE_SIZE = 32 // also the size of features received by the emb model

        // emb model shape is [1,76,32,1] -> [1,1,1,96]
        const val EMB_INPUT_COUNT = 76 // hardcoded in the model
        const val EMB_OUTPUT_COUNT = 1
        const val EMB_FEATURE_SIZE = 96 // also the size of features received by the wake model

        // wake model shape is [1,16,96] -> [1,1]
        const val WAKE_INPUT_COUNT = 16 // hardcoded in the model

        private fun loadModel(model: ByteArray, settings: WakeWordSettings): OrtSession {
            try {
                // Create ONNX Runtime session with optimizations configured via settings
                val sessionOptions = OrtSession.SessionOptions().apply {
                    // Set optimization level from settings
                    setOptimizationLevel(settings.optimizationLevel.toOrtOptLevel())

                    // Set execution mode from settings
                    setExecutionMode(settings.executionMode.toOrtExecutionMode())

                    // Set thread count from settings
                    setIntraOpNumThreads(settings.threadCount)
                }
                return OrtEnvironment.getEnvironment().createSession(model, sessionOptions)
            } catch (t: Throwable) {
                throw Exception("Failed to load ONNX model", t)
            }
        }
    }
}
