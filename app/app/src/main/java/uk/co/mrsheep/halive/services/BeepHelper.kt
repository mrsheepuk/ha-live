package uk.co.mrsheep.halive.services

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helper object for playing beeps and haptic feedback.
 *
 * Provides audio and tactile feedback for voice assistant conversation lifecycle:
 * - Ready beep: Single pleasant tone when conversation starts
 * - End beep: Single lower tone when conversation ends
 *
 * All beeps use STREAM_MUSIC to match the agent's audio volume level.
 * All operations are non-blocking and crash-safe.
 */
object BeepHelper {

    private const val TAG = "BeepHelper"

    /**
     * Plays a short ready beep with haptic feedback.
     *
     * This function:
     * - Plays a pleasant beep using ToneGenerator (TONE_PROP_BEEP, ~200ms duration)
     * - Uses STREAM_MUSIC to match the agent's audio volume
     * - Triggers haptic feedback (short vibration pattern)
     * - Executes asynchronously to avoid blocking the UI thread
     * - Handles all errors gracefully to prevent crashes
     *
     * The function returns immediately and executes audio/haptic feedback in the background.
     *
     * @param context The Android application context used to access system services
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun playReadyBeep(context: Context) {
        // Execute asynchronously to avoid blocking the UI thread
        GlobalScope.launch(Dispatchers.Default) {
            try {
                // Play the beep
                playBeep()

                // Play haptic feedback
                playHaptic(context)
            } catch (e: Exception) {
                // Silently handle any errors - beep failure shouldn't crash the app
                Log.w(TAG, "Failed to play ready beep or haptic", e)
            }
        }
    }

    /**
     * Plays an end beep with haptic feedback.
     *
     * This function:
     * - Plays a negative acknowledgment tone using ToneGenerator (TONE_PROP_NACK, ~200ms duration)
     * - Uses STREAM_MUSIC to match the agent's audio volume
     * - Triggers haptic feedback (short vibration pattern)
     * - Executes asynchronously to avoid blocking the UI thread
     * - Handles all errors gracefully to prevent crashes
     *
     * The function returns immediately and executes audio/haptic feedback in the background.
     *
     * @param context The Android application context used to access system services
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun playEndBeep(context: Context) {
        // Execute asynchronously to avoid blocking the UI thread
        GlobalScope.launch(Dispatchers.Default) {
            try {
                // Play the end beep tone
                playEndBeepTone()

                // Play haptic feedback
                playHaptic(context)
            } catch (e: Exception) {
                // Silently handle any errors - beep failure shouldn't crash the app
                Log.w(TAG, "Failed to play end beep or haptic", e)
            }
        }
    }

    /**
     * Plays a single pleasant beep tone.
     *
     * Uses ToneGenerator with STREAM_MUSIC audio stream to match the agent's audio volume.
     * ToneGenerator is properly cleaned up in a finally block.
     */
    private suspend fun playBeep() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        try {
            // 1. Start with a short standard beep (usually mid-frequency)
            // Duration is very short (40ms) to act as the "ramp up"
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            delay(50) 

            // 2. Cut immediately to the ACK tone (usually higher frequency)
            // Duration is slightly longer (100ms) to act as the "peak"
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            delay(150) // Wait for the tone to finish playing
        } finally {
            // Ensure ToneGenerator is always released to prevent resource leaks
            toneGenerator.release()
        }
    }

    /**
     * Plays a single negative acknowledgment tone (more conclusive ending sound).
     *
     * Uses ToneGenerator with STREAM_MUSIC audio stream to match the agent's audio volume.
     * ToneGenerator is properly cleaned up in a finally block.
     */
    private suspend fun playEndBeepTone() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        try {
            // Play TONE_PROP_NACK for 200ms (more definitive ending tone)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 200)
            // Allow time for tone to play
            delay(250)
        } finally {
            // Ensure ToneGenerator is always released to prevent resource leaks
            toneGenerator.release()
        }
    }

    /**
     * Plays haptic feedback (short vibration pattern).
     *
     * Handles both pre-API 31 and API 31+ vibrator APIs gracefully.
     * Uses VibrationEffect.EFFECT_TICK for a consistent haptic pattern across Android versions.
     *
     * @param context The Android application context
     */
    private fun playHaptic(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ - Use VibratorManager
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator

                vibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                )
            } else {
                // Pre-API 31 - Use Vibrator directly
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // API 26+ - Use VibrationEffect
                    vibrator?.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    )
                } else {
                    // Pre-API 26 - Use deprecated vibrate method with millisecond duration
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(50) // 50ms vibration
                }
            }
        } catch (e: Exception) {
            // Silently handle vibrator errors - audio beep is more important than haptic feedback
            Log.w(TAG, "Failed to play haptic feedback", e)
        }
    }
}

