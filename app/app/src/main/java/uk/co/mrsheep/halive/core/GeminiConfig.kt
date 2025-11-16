package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager for Gemini API credentials.
 * Stores and retrieves the API key for direct Gemini Live API access.
 */
object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "gemini_api_key"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the Gemini API key to SharedPreferences.
     *
     * @param context Android context
     * @param apiKey The Gemini API key
     */
    fun saveApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    /**
     * Retrieves the Gemini API key from SharedPreferences.
     *
     * @param context Android context
     * @return The API key, or null if not configured
     */
    fun getApiKey(context: Context): String? {
        return getPrefs(context).getString(KEY_API_KEY, null)
    }

    /**
     * Checks if Gemini API key is configured.
     *
     * @param context Android context
     * @return true if API key is set, false otherwise
     */
    fun isConfigured(context: Context): Boolean {
        return getApiKey(context) != null
    }

    /**
     * Clears all Gemini configuration data.
     *
     * @param context Android context
     */
    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
