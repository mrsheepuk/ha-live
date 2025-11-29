package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
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
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_INTEGRATION_INSTALLED = "integration_installed"
        private const val KEY_LAST_FETCH = "last_fetch_time"
    }

    fun saveConfig(config: SharedConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, Json.encodeToString(config))
            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
            .apply()
    }

    fun getConfig(): SharedConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            Json.decodeFromString<SharedConfig>(json)
        } catch (e: Exception) {
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

    fun clear() {
        prefs.edit().clear().apply()
    }
}
