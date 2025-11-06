package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

object HAConfig {
    private const val PREFS_NAME = "ha_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_BASE_URL, null) != null &&
               prefs.getString(KEY_TOKEN, null) != null
    }

    fun saveConfig(context: Context, baseUrl: String, token: String) {
        getPrefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun loadConfig(context: Context): Pair<String, String>? {
        val prefs = getPrefs(context)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
        val token = prefs.getString(KEY_TOKEN, null)

        return if (baseUrl != null && token != null) {
            Pair(baseUrl, token)
        } else {
            null
        }
    }

    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val normalizedUrl = url.trim()
            normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }

    fun isValidToken(token: String): Boolean {
        return token.isNotBlank()
    }
}
