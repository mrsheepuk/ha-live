package uk.co.mrsheep.halive.services.conversation

import android.content.Context
import android.util.Log
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.ConversationServicePreference
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.services.geminidirect.DirectConversationService
import uk.co.mrsheep.halive.services.geminifirebase.FirebaseConversationService

/**
 * Factory for creating the appropriate ConversationService implementation.
 *
 * This factory pattern enables seamless switching between two conversation protocols:
 * - **DirectConversationService**: Uses the direct Gemini Live API protocol via WebSocket
 * - **FirebaseConversationService**: Uses the Firebase AI SDK
 *
 * The implementation is selected based on user preference (if both are configured)
 * or automatically based on what's available.
 */
object ConversationServiceFactory {

    private const val TAG = "ConversationServiceFactory"

    /**
     * Creates the appropriate ConversationService implementation based on user preference
     * and configuration availability.
     *
     * @param context Android application context
     * @return A ConversationService implementation (either DirectConversationService or FirebaseConversationService)
     */
    fun create(context: Context): ConversationService {
        val preference = ConversationServicePreference.getPreferred(context)

        return when (preference) {
            ConversationServicePreference.PreferredService.GEMINI_DIRECT -> {
                if (GeminiConfig.isConfigured(context)) {
                    Log.d(TAG, "Using direct Gemini Live API protocol (user preference)")
                    DirectConversationService(context)
                } else {
                    Log.d(TAG, "Gemini Direct preferred but not configured, falling back to Firebase SDK")
                    FirebaseConversationService(context)
                }
            }
            ConversationServicePreference.PreferredService.FIREBASE -> {
                Log.d(TAG, "Using Firebase SDK (user preference)")
                FirebaseConversationService(context)
            }
        }
    }
}
