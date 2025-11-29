package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

class SecureTokenStorage(context: Context) {
    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val PREFS_FILE = "halive_secure_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_HA_URL = "ha_url"
    }

    private val sharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize encrypted prefs, using fallback", e)
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        haUrl: String
    ) {
        try {
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
            sharedPreferences.edit().apply {
                putString(KEY_ACCESS_TOKEN, accessToken)
                putString(KEY_REFRESH_TOKEN, refreshToken)
                putLong(KEY_EXPIRES_AT, expiresAt)
                putString(KEY_HA_URL, haUrl)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tokens", e)
            throw e
        }
    }

    fun getTokens(): OAuthTokens? {
        return try {
            val accessToken = sharedPreferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
            val refreshToken = sharedPreferences.getString(KEY_REFRESH_TOKEN, null) ?: return null
            val expiresAt = sharedPreferences.getLong(KEY_EXPIRES_AT, -1L)

            if (expiresAt == -1L) return null

            OAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve tokens", e)
            null
        }
    }

    fun getHaUrl(): String? {
        return try {
            sharedPreferences.getString(KEY_HA_URL, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve HA URL", e)
            null
        }
    }

    fun clearTokens() {
        try {
            sharedPreferences.edit().apply {
                remove(KEY_ACCESS_TOKEN)
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_EXPIRES_AT)
                remove(KEY_HA_URL)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear tokens", e)
        }
    }
}
