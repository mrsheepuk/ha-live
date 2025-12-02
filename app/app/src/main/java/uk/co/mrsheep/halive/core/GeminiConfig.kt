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
    private const val KEY_SHARED_API_KEY = "shared_gemini_api_key"

    // In-memory cache of shared key (also persisted to prefs)
    private var cachedSharedKey: String? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initialize shared key from persisted storage.
     * Call this on app startup before checking isConfigured().
     */
    fun initialize(context: Context) {
        if (cachedSharedKey == null) {
            cachedSharedKey = getPrefs(context).getString(KEY_SHARED_API_KEY, null)
        }
    }

    /**
     * Update the cached shared key from Home Assistant.
     * Called when shared config is fetched from HA.
     * Persists to SharedPreferences so it survives app restart.
     */
    fun updateSharedKey(key: String?, context: Context? = null) {
        cachedSharedKey = key
        // Persist to prefs if context available
        context?.let {
            getPrefs(it).edit()
                .putString(KEY_SHARED_API_KEY, key)
                .apply()
        }
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
        // Ensure shared key is loaded from prefs
        initialize(context)

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
     * Clears all Gemini configuration data (local key only, shared key remains).
     */
    fun clearConfig(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_API_KEY)
            .apply()
    }

    /**
     * Clear cached shared key (call when logging out of HA).
     */
    fun clearSharedKey(context: Context? = null) {
        cachedSharedKey = null
        context?.let {
            getPrefs(it).edit()
                .remove(KEY_SHARED_API_KEY)
                .apply()
        }
    }
}
