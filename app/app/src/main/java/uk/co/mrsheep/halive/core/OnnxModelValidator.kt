package uk.co.mrsheep.halive.core

import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Validates ONNX wake word detection models.
 * Ensures models are valid, readable, and have correct input/output shapes.
 */
object OnnxModelValidator {
    private const val TAG = "OnnxModelValidator"

    // Expected input shape: [1, 16, 96] (batch=1, channels=16, samples=96)
    private const val EXPECTED_INPUT_BATCH = 1
    private const val EXPECTED_INPUT_CHANNELS = 16
    private const val EXPECTED_INPUT_SAMPLES = 96

    // Expected output shape: [1, 1] (batch=1, score=1)
    private const val EXPECTED_OUTPUT_BATCH = 1
    private const val EXPECTED_OUTPUT_SCORE = 1

    /**
     * Validates an ONNX wake word model file.
     *
     * Checks:
     * - File exists and is readable
     * - File can be loaded by ONNX Runtime
     * - Input shape is [1, 16, 96]
     * - Output shape is [1, 1]
     *
     * @param file ONNX model file to validate
     * @return Result<Boolean> - success(true) if valid, failure(exception) if invalid
     */
    fun validateWakeWordModel(file: File): Result<Boolean> {
        return try {
            // Check file exists and is readable
            if (!file.exists()) {
                return Result.failure(Exception("Model file does not exist: ${file.absolutePath}"))
            }

            if (!file.isFile) {
                return Result.failure(Exception("Path is not a file: ${file.absolutePath}"))
            }

            if (!file.canRead()) {
                return Result.failure(Exception("Model file is not readable: ${file.absolutePath}"))
            }

            Log.d(TAG, "Validating ONNX model: ${file.name} (${file.length()} bytes)")

            // Try to load with ONNX Runtime
            val ortEnv = OrtEnvironment.getEnvironment()
            var session: OrtSession? = null

            try {
                // Create session with model
                session = ortEnv.createSession(file.absolutePath)

                // Get input and output information
                val inputInfo = session.inputInfo
                val outputInfo = session.outputInfo

                Log.d(TAG, "Model loaded successfully. Inputs: ${inputInfo.size}, Outputs: ${outputInfo.size}")

                // Validate we have expected number of inputs/outputs
                if (inputInfo.isEmpty()) {
                    return Result.failure(Exception("Model has no inputs"))
                }

                if (outputInfo.isEmpty()) {
                    return Result.failure(Exception("Model has no outputs"))
                }

                // Get first input (typically the audio feature input)
                val inputName = inputInfo.keys.first()
                val inputNodeInfo = inputInfo[inputName]
                    ?: return Result.failure(Exception("Cannot read input node info for: $inputName"))

                val inputShape = inputNodeInfo.shape
                Log.d(TAG, "Input '$inputName' shape: ${inputShape.contentToString()}")

                // Validate input shape: [1, 16, 96]
                if (inputShape.size < 3) {
                    return Result.failure(
                        Exception(
                            "Input shape has fewer than 3 dimensions: ${inputShape.contentToString()}. " +
                            "Expected [1, 16, 96]"
                        )
                    )
                }

                if (inputShape[0] != EXPECTED_INPUT_BATCH.toLong() ||
                    inputShape[1] != EXPECTED_INPUT_CHANNELS.toLong() ||
                    inputShape[2] != EXPECTED_INPUT_SAMPLES.toLong()
                ) {
                    return Result.failure(
                        Exception(
                            "Input shape mismatch. Got [${inputShape[0]}, ${inputShape[1]}, ${inputShape[2]}], " +
                            "expected [$EXPECTED_INPUT_BATCH, $EXPECTED_INPUT_CHANNELS, $EXPECTED_INPUT_SAMPLES]"
                        )
                    )
                }

                // Get first output (typically the confidence score)
                val outputName = outputInfo.keys.first()
                val outputNodeInfo = outputInfo[outputName]
                    ?: return Result.failure(Exception("Cannot read output node info for: $outputName"))

                val outputShape = outputNodeInfo.shape
                Log.d(TAG, "Output '$outputName' shape: ${outputShape.contentToString()}")

                // Validate output shape: [1, 1]
                if (outputShape.size < 2) {
                    return Result.failure(
                        Exception(
                            "Output shape has fewer than 2 dimensions: ${outputShape.contentToString()}. " +
                            "Expected [1, 1]"
                        )
                    )
                }

                if (outputShape[0] != EXPECTED_OUTPUT_BATCH.toLong() ||
                    outputShape[1] != EXPECTED_OUTPUT_SCORE.toLong()
                ) {
                    return Result.failure(
                        Exception(
                            "Output shape mismatch. Got [${outputShape[0]}, ${outputShape[1]}], " +
                            "expected [$EXPECTED_OUTPUT_BATCH, $EXPECTED_OUTPUT_SCORE]"
                        )
                    )
                }

                Log.d(TAG, "Model validation successful: ${file.name}")
                Result.success(true)

            } finally {
                // Always close the session
                session?.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Model validation failed: ${e.message}", e)
            Result.failure(
                Exception("ONNX model validation failed: ${e.message}", e)
            )
        }
    }
}
