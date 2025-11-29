package uk.co.mrsheep.halive.core

import android.content.Context

/**
 * Home Assistant configuration facade.
 * Delegates to HomeAssistantAuth/SecureTokenStorage for OAuth-based authentication.
 */
object HAConfig {

    fun isConfigured(context: Context): Boolean {
        return HomeAssistantAuth(context).isAuthenticated()
    }

    fun getHaUrl(context: Context): String? {
        return HomeAssistantAuth(context).getHaUrl()
    }

    fun clearConfig(context: Context) {
        HomeAssistantAuth(context).clearAuth()
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
}
