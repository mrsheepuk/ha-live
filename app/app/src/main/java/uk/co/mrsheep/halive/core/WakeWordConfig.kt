package uk.co.mrsheep.halive.core

import android.content.Context

object WakeWordConfig {
    private const val PREFS_NAME = "wake_word_prefs"
    private const val KEY_ENABLED = "wake_word_enabled"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false) // Default OFF
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
