package uk.co.mrsheep.halive.services

import android.content.Context
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper

/**
 * No-op stub implementation of WakeWordService for builds without wake word support.
 *
 * This class provides the same public interface as the real WakeWordService but with
 * all methods implemented as no-ops. Used in the "nowake" build flavor to allow
 * the app to compile and run without ONNX Runtime or wake word detection functionality.
 *
 * All methods are safe to call and will have no side effects.
 */
class WakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    fun startListening() {
        // No-op: wake word detection disabled
    }

    fun stopListening() {
        // No-op: wake word detection disabled
    }

    fun yieldMicrophoneHelper(): MicrophoneHelper? {
        // No-op: return null
        return null
    }

    fun resumeWith(helper: MicrophoneHelper) {
        // Release the provided helper since we're not using it
        helper.release()
    }

    fun startTestMode(onScore: (Float) -> Unit) {
        // No-op: test mode disabled
    }

    fun stopTestMode() {
        // No-op: test mode disabled
    }

    fun isTestMode(): Boolean {
        // Always return false - test mode not available
        return false
    }

    fun destroy() {
        // No-op: nothing to clean up
    }

    fun reloadSettings() {
        // No-op: no settings to reload
    }
}
