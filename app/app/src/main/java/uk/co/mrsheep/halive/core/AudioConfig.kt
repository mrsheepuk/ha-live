package uk.co.mrsheep.halive.core

import android.content.Context

/**
 * Audio output routing options for conversation sessions.
 */
enum class AudioOutputMode(val displayName: String) {
    /**
     * Route audio through the device's loudspeaker (hands-free mode).
     * Loudest option, good for ambient conversations.
     */
    SPEAKERPHONE("Speakerphone"),

    /**
     * Route audio through the device's earpiece (phone speaker).
     * Quieter, more private option. Requires holding phone to ear.
     */
    EARPIECE("Earpiece"),

    /**
     * Route audio through connected Bluetooth device (headset, car, etc.).
     * Requires an active Bluetooth audio device connection.
     */
    BLUETOOTH("Bluetooth");

    companion object {
        fun fromString(value: String): AudioOutputMode {
            return entries.find { it.name == value } ?: SPEAKERPHONE
        }
    }
}

/**
 * Configuration storage for audio settings.
 */
object AudioConfig {
    private const val PREFS_NAME = "audio_config"
    private const val KEY_OUTPUT_MODE = "output_mode"

    /**
     * Get the saved audio output mode preference.
     * Defaults to SPEAKERPHONE.
     */
    fun getOutputMode(context: Context): AudioOutputMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_OUTPUT_MODE, AudioOutputMode.SPEAKERPHONE.name)
        return AudioOutputMode.fromString(saved ?: AudioOutputMode.SPEAKERPHONE.name)
    }

    /**
     * Save the audio output mode preference.
     */
    fun setOutputMode(context: Context, mode: AudioOutputMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
    }
}
