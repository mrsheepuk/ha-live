package uk.co.mrsheep.halive.services

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helper object for playing beeps and haptic feedback.
 *
 * Provides audio and tactile feedback for voice assistant conversation lifecycle:
 * - Ready beep: Ascending two-tone pattern (powering up) when conversation starts
 * - End beep: Descending two-tone pattern (powering down) when conversation ends
 *
 * All operations are non-blocking and crash-safe.
 */
object BeepHelper {

    private const val TAG = "BeepHelper"

    /**
     * Plays a short ready beep with haptic feedback.
     *
     * This function:
     * - Plays an ascending two-tone notification beep using ToneGenerator (DTMF 4→8, ~230ms duration)
     * - Triggers haptic feedback (short vibration pattern)
     * - Executes asynchronously to avoid blocking the UI thread
     * - Handles all errors gracefully to prevent crashes
     *
     * The function returns immediately and executes audio/haptic feedback in the background.
     *
     * @param context The Android application context used to access system services
     */
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
     * - Plays a descending two-tone notification beep using ToneGenerator
     * - Triggers haptic feedback (short vibration pattern)
     * - Executes asynchronously to avoid blocking the UI thread
     * - Handles all errors gracefully to prevent crashes
     *
     * The function returns immediately and executes audio/haptic feedback in the background.
     *
     * @param context The Android application context used to access system services
     */
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
     * Plays an ascending two-tone pattern (powering up).
     *
     * Uses ToneGenerator with STREAM_NOTIFICATION audio stream and releases resources immediately
     * after the tones play. ToneGenerator is properly cleaned up in a finally block.
     * Pattern: DTMF 4 (100ms) → wait 110ms → DTMF 8 (100ms) → wait 120ms
     */
    private suspend fun playBeep() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        try {
            // Play DTMF tone 4 for 100ms (ascending pattern start)
            toneGenerator.startTone(ToneGenerator.TONE_DTMF_4, 100)
            delay(110)

            // Play DTMF tone 8 for 100ms (ascending pattern end)
            toneGenerator.startTone(ToneGenerator.TONE_DTMF_8, 100)
            delay(120)
        } finally {
            // Ensure ToneGenerator is always released to prevent resource leaks
            toneGenerator.release()
        }
    }

    /**
     * Plays a descending two-tone pattern (powering down).
     *
     * Uses ToneGenerator with STREAM_NOTIFICATION audio stream and releases resources immediately
     * after the tones play. ToneGenerator is properly cleaned up in a finally block.
     * Pattern: DTMF 8 (100ms) → wait 110ms → DTMF 4 (100ms) → wait 120ms
     */
    private suspend fun playEndBeepTone() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        try {
            // Play DTMF tone 8 for 100ms (descending pattern start)
            toneGenerator.startTone(ToneGenerator.TONE_DTMF_8, 100)
            delay(110)

            // Play DTMF tone 4 for 100ms (descending pattern end)
            toneGenerator.startTone(ToneGenerator.TONE_DTMF_4, 100)
            delay(120)
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
