package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preference for which conversation service to use when both are available.
 */
object ConversationServicePreference {
    private const val PREFS_NAME = "conversation_service_prefs"
    private const val KEY_PREFERRED_SERVICE = "preferred_service"

    enum class PreferredService {
        FIREBASE,      // Use Firebase SDK
        GEMINI_DIRECT  // Use direct Gemini Live API
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the user's preferred conversation service.
     * Defaults to GEMINI_DIRECT if Gemini API key is configured, otherwise FIREBASE.
     */
    fun getPreferred(context: Context): PreferredService {
        val prefValue = getPrefs(context).getString(KEY_PREFERRED_SERVICE, null)

        return when (prefValue) {
            "firebase" -> PreferredService.FIREBASE
            "gemini_direct" -> PreferredService.GEMINI_DIRECT
            else -> {
                // Default: prefer Gemini Direct if configured, otherwise Firebase
                if (GeminiConfig.isConfigured(context)) {
                    PreferredService.GEMINI_DIRECT
                } else {
                    PreferredService.FIREBASE
                }
            }
        }
    }

    /**
     * Set the user's preferred conversation service.
     */
    fun setPreferred(context: Context, service: PreferredService) {
        val value = when (service) {
            PreferredService.FIREBASE -> "firebase"
            PreferredService.GEMINI_DIRECT -> "gemini_direct"
        }
        getPrefs(context).edit()
            .putString(KEY_PREFERRED_SERVICE, value)
            .apply()
    }

    /**
     * Check if both services are available (user can choose between them).
     */
    fun canChoose(context: Context): Boolean {
        return GeminiConfig.isConfigured(context) && FirebaseConfig.isConfigured(context)
    }
}
