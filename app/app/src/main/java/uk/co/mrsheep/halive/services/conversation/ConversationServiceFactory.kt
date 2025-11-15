package uk.co.mrsheep.halive.services.conversation

import android.content.Context
import android.util.Log
import uk.co.mrsheep.halive.core.GeminiConfig

/**
 * Factory for creating the appropriate ConversationService implementation.
 *
 * This factory pattern enables seamless switching between two conversation protocols:
 * - **DirectConversationService**: Uses the direct Gemini Live API protocol when a Gemini API key is configured.
 *   This provides direct WebSocket communication with Google's Gemini Live API.
 * - **FirebaseConversationService**: Uses the Firebase SDK when no Gemini API key is configured.
 *   This is the fallback implementation for standard Firebase AI SDK integration.
 *
 * The implementation is selected based on the presence of a configured Gemini API key,
 * determined by [GeminiConfig.isConfigured].
 */
object ConversationServiceFactory {

    private const val TAG = "ConversationServiceFactory"

    /**
     * Creates the appropriate ConversationService implementation based on whether
     * a Gemini API key is configured.
     *
     * @param context Android application context
     * @return A ConversationService implementation (either DirectConversationService or FirebaseConversationService)
     */
    fun create(context: Context): ConversationService {
        return if (GeminiConfig.isConfigured(context)) {
            Log.d(TAG, "Using direct Gemini Live API protocol")
            DirectConversationService(context)
        } else {
            Log.d(TAG, "Using Firebase SDK (no Gemini API key configured)")
            FirebaseConversationService(context)
        }
    }
}
