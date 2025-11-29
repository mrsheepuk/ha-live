package uk.co.mrsheep.halive.services.conversation

import android.content.Context
import android.util.Log
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.services.geminidirect.DirectConversationService

/**
 * Factory for creating the ConversationService implementation.
 *
 * Creates a DirectConversationService using the Gemini Live API protocol via WebSocket.
 */
object ConversationServiceFactory {

    private const val TAG = "ConversationServiceFactory"

    /**
     * Creates a DirectConversationService using the Gemini Live API protocol.
     *
     * @param context Android application context
     * @return A DirectConversationService instance
     */
    fun create(context: Context): ConversationService {
        Log.d(TAG, "Using direct Gemini Live API protocol")
        return DirectConversationService(context)
    }
}
