package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.mrsheep.halive.services.SharedConfig

/**
 * Caches shared config locally for offline access.
 */
class SharedConfigCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shared_config_cache", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SharedConfigCache"
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_INTEGRATION_INSTALLED = "integration_installed"
        private const val KEY_LAST_FETCH = "last_fetch_time"
        private const val KEY_LAST_FETCH_FAILED = "last_fetch_failed"
        private const val OFFLINE_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
    }

    fun saveConfig(config: SharedConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, Json.encodeToString(config))
            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
            .putBoolean(KEY_LAST_FETCH_FAILED, false)
            .apply()
    }

    fun getConfig(): SharedConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            Json.decodeFromString<SharedConfig>(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize cached config, clearing cache", e)
            // Clear corrupted cache to prevent repeated failures
            prefs.edit().remove(KEY_CONFIG).apply()
            null
        }
    }

    fun setIntegrationInstalled(installed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_INTEGRATION_INSTALLED, installed)
            .apply()
    }

    fun isIntegrationInstalled(): Boolean {
        return prefs.getBoolean(KEY_INTEGRATION_INSTALLED, false)
    }

    fun getLastFetchTime(): Long {
        return prefs.getLong(KEY_LAST_FETCH, 0)
    }

    /**
     * Consider offline if last successful fetch > 5 minutes ago
     * and we have cached data.
     */
    fun isOffline(): Boolean {
        val lastFetch = getLastFetchTime()
        val cacheAge = System.currentTimeMillis() - lastFetch
        return cacheAge > OFFLINE_THRESHOLD_MS && getConfig() != null
    }

    fun setLastFetchFailed(failed: Boolean) {
        prefs.edit().putBoolean(KEY_LAST_FETCH_FAILED, failed).apply()
    }

    fun didLastFetchFail(): Boolean {
        return prefs.getBoolean(KEY_LAST_FETCH_FAILED, false)
    }

    /**
     * Clear the profile cache while preserving the Gemini API key.
     * Profiles will be re-fetched from Home Assistant on next sync.
     * Use this for the "Clear Cache" button in settings.
     */
    fun clearProfileCache() {
        val currentConfig = getConfig()
        val apiKey = currentConfig?.geminiApiKey

        // Clear sync metadata
        prefs.edit()
            .remove(KEY_CONFIG)
            .remove(KEY_LAST_FETCH)
            .putBoolean(KEY_LAST_FETCH_FAILED, false)
            .apply()

        // If we had an API key, save it back with empty profiles
        if (apiKey != null) {
            val configWithKeyOnly = SharedConfig(
                geminiApiKey = apiKey,
                profiles = emptyList()
            )
            prefs.edit()
                .putString(KEY_CONFIG, Json.encodeToString(configWithKeyOnly))
                .apply()
            Log.d(TAG, "Cleared profile cache, preserved Gemini API key")
        } else {
            Log.d(TAG, "Cleared profile cache (no API key to preserve)")
        }
    }

    /**
     * Clear everything including the Gemini API key.
     * Use this when logging out or resetting the app.
     */
    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all cached config")
    }
}

