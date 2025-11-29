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
        GEMINI_DIRECT  // Use direct Gemini Live API
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the user's preferred conversation service.
     * Always returns GEMINI_DIRECT (only provider available).
     */
    fun getPreferred(context: Context): PreferredService {
        return PreferredService.GEMINI_DIRECT
    }

    /**
     * Set the user's preferred conversation service.
     * No-op since only GEMINI_DIRECT is available.
     */
    fun setPreferred(context: Context, service: PreferredService) {
        // No-op: only GEMINI_DIRECT is available
    }

    /**
     * Check if user can choose between services.
     * Always returns false since only GEMINI_DIRECT is available.
     */
    fun canChoose(context: Context): Boolean {
        return false
    }
}
