package uk.co.mrsheep.halive.services.wake

/**
 * Abstraction for wake word detection models.
 *
 * This interface defines the contract for wake word detection models that can be implemented
 * by different inference runtimes (ONNX, TFLite, etc.).
 *
 * The model implements a 3-stage pipeline:
 * 1. Melspectrogram extraction from raw audio frames
 * 2. Embedding generation using a neural network encoder
 * 3. Wake word detection classification to produce a detection score
 *
 * Implementations are responsible for managing internal buffers, feature extraction,
 * and model inference.
 *
 * @author Home Assistant Live
 */
interface WakeWordModel : AutoCloseable {

    /**
     * Process a frame of audio and return the wake word detection score.
     *
     * @param audio FloatArray containing raw int16 PCM audio values converted to float.
     *              Expected range: -32768.0 to 32767.0
     *              Typically contains 512 or 1024 samples depending on model configuration.
     *
     * @return Detection score as a Float between 0.0 (no wake word) and 1.0 (wake word detected).
     *         The threshold for triggering wake word detection is typically configurable
     *         and depends on the specific model and application requirements.
     *
     * @throws IllegalArgumentException if audio array is null or has unexpected length
     * @throws RuntimeException if model inference fails
     */
    fun processFrame(audio: FloatArray): Float

    /**
     * Reset the internal accumulation buffers.
     *
     * This method clears any streaming state maintained by the model, including:
     * - Melspectrogram feature buffers
     * - Embedding accumulators
     * - Any intermediate computation state
     *
     * Call this when starting a new wake word detection session or when you want to
     * discard previous audio history and start fresh.
     *
     * @throws RuntimeException if buffer reset fails
     */
    fun resetAccumulators()

    /**
     * Release resources held by the model.
     *
     * This method should free any native resources, close file handles, unload the model
     * from memory, and perform any other necessary cleanup.
     *
     * After calling this method, the model instance should not be used.
     * Calling any other methods after close() is undefined behavior.
     *
     * Safe to call multiple times (idempotent).
     */
    override fun close()
}
