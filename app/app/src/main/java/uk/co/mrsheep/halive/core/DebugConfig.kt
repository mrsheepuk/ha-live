package uk.co.mrsheep.halive.core

import android.content.Context

/**
 * Debug/diagnostic settings, persisted via SharedPreferences.
 */
object DebugConfig {
    private const val PREFS_NAME = "debug_prefs"
    private const val KEY_SAVE_RECEIVED_AUDIO = "save_received_audio"

    /**
     * Whether to save raw model audio, exactly as received from the Gemini
     * Live API, to a WAV file per turn. Used to diagnose whether audio
     * artifacts originate upstream (present in the received data) or in the
     * local playback pipeline.
     */
    fun isSaveReceivedAudioEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SAVE_RECEIVED_AUDIO, false)
    }

    fun setSaveReceivedAudioEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SAVE_RECEIVED_AUDIO, enabled).apply()
    }
}
