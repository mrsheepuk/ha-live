package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager for Gemini API credentials.
 * Supports both shared keys (from Home Assistant) and local keys (device-specific).
 */
object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_USE_SHARED = "use_shared_key"

    // Cached shared key (set by app on config fetch from HA)
    private var cachedSharedKey: String? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Update the cached shared key from Home Assistant.
     * Called when shared config is fetched from HA.
     */
    fun updateSharedKey(key: String?) {
        cachedSharedKey = key
    }

    /**
     * Get the shared key (if cached).
     */
    fun getSharedKey(): String? = cachedSharedKey

    /**
     * Check if a shared key is available.
     */
    fun hasSharedKey(): Boolean = !cachedSharedKey.isNullOrBlank()

    /**
     * Get the effective Gemini API key.
     * Priority: Shared key (if available and enabled) > Local key
     */
    fun getApiKey(context: Context): String? {
        val prefs = getPrefs(context)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)

        // Prefer shared key if available and user hasn't disabled it
        if (useShared && !cachedSharedKey.isNullOrBlank()) {
            return cachedSharedKey
        }

        // Fall back to local key
        return prefs.getString(KEY_API_KEY, null)
    }

    /**
     * Check if currently using the shared key.
     */
    fun isUsingSharedKey(context: Context): Boolean {
        val prefs = getPrefs(context)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)
        return useShared && !cachedSharedKey.isNullOrBlank()
    }

    /**
     * Get the local API key (device-specific).
     */
    fun getLocalApiKey(context: Context): String? {
        return getPrefs(context).getString(KEY_API_KEY, null)
    }

    /**
     * Save a local API key (device-specific).
     * This is the existing saveApiKey behavior.
     */
    fun saveApiKey(context: Context, apiKey: String) {
        saveLocalApiKey(context, apiKey)
    }

    /**
     * Save a local API key (device-specific).
     */
    fun saveLocalApiKey(context: Context, apiKey: String?) {
        getPrefs(context).edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    /**
     * Set whether to prefer shared key over local.
     */
    fun setUseSharedKey(context: Context, useShared: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_USE_SHARED, useShared)
            .apply()
    }

    /**
     * Check if user prefers shared key.
     */
    fun prefersSharedKey(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_SHARED, true)
    }

    /**
     * Checks if Gemini API key is configured (either shared or local).
     */
    fun isConfigured(context: Context): Boolean {
        return getApiKey(context) != null
    }

    /**
     * Clears all Gemini configuration data (local key only, shared key remains cached).
     */
    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Clear cached shared key (call when logging out of HA).
     */
    fun clearSharedKey() {
        cachedSharedKey = null
    }
}
