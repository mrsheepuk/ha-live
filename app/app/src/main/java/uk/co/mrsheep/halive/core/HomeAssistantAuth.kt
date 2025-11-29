package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log

sealed class AuthMethod {
    data class OAuth(val tokenManager: OAuthTokenManager) : AuthMethod()
    data class LegacyToken(val token: String) : AuthMethod()
}

class HomeAssistantAuth(private val context: Context) {
    companion object {
        private const val TAG = "HomeAssistantAuth"
    }

    private val secureStorage = SecureTokenStorage(context)

    fun getAuthMethod(): AuthMethod? {
        // Check for OAuth tokens first
        val tokens = secureStorage.getTokens()
        val haUrl = secureStorage.getHaUrl()
        if (tokens != null && haUrl != null) {
            Log.d(TAG, "Using OAuth authentication method")
            return AuthMethod.OAuth(OAuthTokenManager(haUrl, secureStorage))
        }

        // Fall back to legacy token
        val legacyConfig = HAConfig.loadConfig(context)
        if (legacyConfig != null) {
            Log.d(TAG, "Using legacy token authentication method")
            return AuthMethod.LegacyToken(legacyConfig.second)
        }

        Log.d(TAG, "No authentication method available")
        return null
    }

    fun isAuthenticated(): Boolean {
        return getAuthMethod() != null
    }

    fun getHaUrl(): String? {
        return secureStorage.getHaUrl() ?: HAConfig.loadConfig(context)?.first
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
