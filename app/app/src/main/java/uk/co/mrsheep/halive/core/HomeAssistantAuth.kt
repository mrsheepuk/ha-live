package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log

/**
 * Manages Home Assistant OAuth authentication.
 * OAuth is the only supported authentication method.
 */
class HomeAssistantAuth(private val context: Context) {
    companion object {
        private const val TAG = "HomeAssistantAuth"
    }

    private val secureStorage = SecureTokenStorage(context)

    /**
     * Get the OAuth token manager if authenticated.
     */
    fun getTokenManager(): OAuthTokenManager? {
        val tokens = secureStorage.getTokens()
        val haUrl = secureStorage.getHaUrl()
        if (tokens != null && haUrl != null) {
            Log.d(TAG, "OAuth authentication available")
            return OAuthTokenManager(haUrl, secureStorage)
        }
        Log.d(TAG, "No OAuth authentication available")
        return null
    }

    fun isAuthenticated(): Boolean {
        return secureStorage.getTokens() != null && secureStorage.getHaUrl() != null
    }

    fun getHaUrl(): String? {
        return secureStorage.getHaUrl()
    }

    fun clearAuth() {
        try {
            secureStorage.clearTokens()
            Log.d(TAG, "Cleared OAuth tokens")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear auth", e)
        }
    }
}
