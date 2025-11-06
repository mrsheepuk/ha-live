package uk.co.mrsheep.halive.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@OptIn(PublicPreviewAPI::class)
class GeminiService {

    private var generativeModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null

    /**
     * Called by ViewModel on app launch, *after* Task 1 is complete.
     */
    @OptIn(PublicPreviewAPI::class)
    fun initializeModel(tools: List<Tool>, systemPrompt: String) {
        // 1. Configure the model
        generativeModel = Firebase.ai.liveModel(
            modelName = "gemini-live-2.5-flash-preview",
            systemInstruction = content { text(systemPrompt) },
            tools = tools,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                // Good voices: 'Leda', 'Aoede'
                speechConfig = SpeechConfig(voice = Voice("Aoede"))

            }
        )

        Log.d(TAG, "GeminiService initialized with ${tools.size} tools")
    }

    /**
     * Called by ViewModel when the user presses the "talk" button.
     * The handler is the *key* to connecting Task 3 (MCP execution).
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun startSession(
        // The ViewModel passes a lambda that knows how to call the repository
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart
    ) {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        try {
            // 1. Connect to Gemini, creating the session
            liveSession = model.connect()

            // 2. Start audio conversation with function call handler
            // Wrap the suspend function in runBlocking since the SDK expects a regular function
            liveSession?.startAudioConversation(
                functionCallHandler = { call -> runBlocking { functionCallHandler(call) } }
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "No permission", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            throw e
        }
    }

    /**
     * Called by ViewModel when the user releases the "talk" button.
     */
    @OptIn(PublicPreviewAPI::class)
    fun stopSession() {
        try {
            // Stop the audio conversation by closing the session
            runBlocking { liveSession?.close() }
            liveSession = null

            Log.d(TAG, "Session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping session", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopSession()
    }

    companion object {
        private const val TAG = "GeminiService"
    }
}
