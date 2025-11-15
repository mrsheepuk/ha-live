package uk.co.mrsheep.halive.services

import android.content.Context
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
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.services.GeminiMCPToolExecutor
import uk.co.mrsheep.halive.services.protocol.FunctionCall
import uk.co.mrsheep.halive.services.protocol.FunctionResponse
import uk.co.mrsheep.halive.services.protocol.ToolCallData
import uk.co.mrsheep.halive.services.protocol.ToolDeclaration

@OptIn(PublicPreviewAPI::class)
class GeminiService(
    private val context: Context,
    private val useDirectProtocol: Boolean = true
) {

    // Firebase SDK mode (legacy)
    private var generativeModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null

    // Direct protocol mode (new)
    private var directSession: GeminiLiveSession? = null
    private var directProtocolTools: List<ToolDeclaration>? = null
    private var directProtocolSystemPrompt: String? = null
    private var directProtocolModel: String? = null
    private var directProtocolVoice: String? = null

    /**
     * Called by ViewModel on app launch, *after* Task 1 is complete.
     * Prepares the model for the selected mode (direct protocol or Firebase SDK).
     */
    @OptIn(PublicPreviewAPI::class)
    fun initializeModel(
        tools: List<Tool>,
        systemPrompt: String,
        modelName: String,
        voiceName: String,
        protocolTools: List<ToolDeclaration>? = null
    ) {
        if (useDirectProtocol) {
            // Store configuration for direct protocol mode (will be used in startDirectSession)
            directProtocolTools = protocolTools
            directProtocolSystemPrompt = systemPrompt
            directProtocolModel = modelName
            directProtocolVoice = voiceName
            Log.d(TAG, "GeminiService configured for direct protocol with ${protocolTools?.size ?: 0} tools")
        } else {
            // Firebase SDK mode - initialize the model
            generativeModel = Firebase.ai.liveModel(
                modelName = modelName,
                systemInstruction = content { text(systemPrompt) },
                tools = tools,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    // Good voices: 'Leda', 'Aoede'
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                }
            )

            Log.d(TAG, "GeminiService initialized with Firebase SDK, ${tools.size} tools")
        }
    }

    /**
     * Called by ViewModel when the user presses the "talk" button.
     * Routes to either direct protocol or Firebase SDK based on configuration.
     * The handler is the *key* to connecting Task 3 (MCP execution).
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun startSession(
        // The ViewModel passes a lambda that knows how to call the repository
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart,
        transcriptHandler: ((Transcription?, Transcription?) -> Unit)? = null,
        // Direct protocol needs the tool executor directly to avoid Firebase internal constructors
        toolExecutor: GeminiMCPToolExecutor? = null
    ) {
        if (useDirectProtocol) {
            startDirectSession(toolExecutor, transcriptHandler)
        } else {
            startFirebaseSession(functionCallHandler, transcriptHandler)
        }
    }

    /**
     * Start Firebase SDK session (legacy mode)
     */
    @OptIn(PublicPreviewAPI::class)
    private suspend fun startFirebaseSession(
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart,
        transcriptHandler: ((Transcription?, Transcription?) -> Unit)? = null
    ) {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        try {
            // 1. Connect to Gemini, creating the session
            liveSession = model.connect()

            // 2. Start audio conversation with function call handler
            // Wrap the suspend function in runBlocking since the SDK expects a regular function
            liveSession?.startAudioConversation(
                functionCallHandler = { call -> runBlocking { functionCallHandler(call) } },
                transcriptHandler = transcriptHandler,
                enableInterruptions = true
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "No permission", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Firebase session", e)
            throw e
        }
    }

    /**
     * Start direct protocol session
     * Bypasses Firebase types to avoid internal constructor issues
     */
    private suspend fun startDirectSession(
        toolExecutor: GeminiMCPToolExecutor?,
        transcriptHandler: ((Transcription?, Transcription?) -> Unit)? = null
    ) {
        try {
            // Get API key from config
            val apiKey = GeminiConfig.getApiKey(context)
                ?: throw IllegalStateException("Gemini API key not configured. Please set it in settings.")

            Log.d(TAG, "Starting direct protocol session with API key")

            val executor = toolExecutor ?: throw IllegalStateException("Tool executor required for direct protocol mode")

            // Create session
            directSession = GeminiLiveSession(apiKey, context)

            // Direct tool call handler - bypasses Firebase types entirely
            val directToolCallHandler: suspend (FunctionCall) -> FunctionResponse = { protocolCall ->
                try {
                    // Convert JsonElement args to Map<String, JsonElement> for executor
                    val argsMap = protocolCall.args?.let { jsonElement ->
                        if (jsonElement is kotlinx.serialization.json.JsonObject) {
                            jsonElement.toMap()
                        } else {
                            emptyMap()
                        }
                    } ?: emptyMap()

                    // Execute via MCP executor directly
                    val mcpCall = uk.co.mrsheep.halive.services.mcp.ToolCall(
                        name = protocolCall.name,
                        arguments = argsMap
                    )

                    val result = executor.mcpClient.callTool(protocolCall.name, argsMap)

                    // Build JSON response from MCP result
                    val resultJson = buildJsonObject {
                        result.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").let {
                            put("result", kotlinx.serialization.json.Json.parseToJsonElement(it))
                        }
                    }

                    // Convert to protocol format
                    FunctionResponse(
                        id = protocolCall.id,
                        name = protocolCall.name,
                        response = resultJson
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool call", e)
                    FunctionResponse(
                        id = protocolCall.id,
                        name = protocolCall.name,
                        response = null
                    )
                }
            }

            // Simple transcription callback - just pass strings
            val simpleTranscriptionHandler: ((String?, String?) -> Unit)? =
                if (transcriptHandler != null) {
                    { userText: String?, modelText: String? ->
                        // Can't create Transcription objects (internal constructor)
                        // For now, skip transcriptions in direct protocol mode
                        // TODO: Add transcription support without Firebase types
                        Log.d(TAG, "Transcription: user='$userText', model='$modelText'")
                    }
                } else {
                    null
                }

            // Start the session with stored configuration
            directSession?.start(
                model = directProtocolModel ?: "models/gemini-2.0-flash-exp",
                systemPrompt = directProtocolSystemPrompt ?: "",
                tools = directProtocolTools ?: emptyList(),
                voiceName = directProtocolVoice ?: "Aoede",
                onToolCall = directToolCallHandler,
                onTranscription = simpleTranscriptionHandler
            )

            Log.d(TAG, "Direct protocol session started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start direct protocol session", e)
            directSession?.close()
            directSession = null
            throw e
        }
    }

    /**
     * Send a text message to the active session.
     * Can be used to send initial conversation prompts.
     * Works with both Firebase SDK and direct protocol modes.
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun sendTextMessage(text: String) {
        if (useDirectProtocol) {
            // Direct protocol mode
            val session = directSession ?: throw IllegalStateException("Direct session not active")
            session.sendText(text)
            Log.d(TAG, "Sent text message via direct protocol: $text")
        } else {
            // Firebase SDK mode
            val session = liveSession ?: throw IllegalStateException("Firebase session not active")
            session.sendTextRealtime(text)
            Log.d(TAG, "Sent text message via Firebase SDK: $text")
        }
    }

    /**
     * Called by ViewModel when the user releases the "talk" button.
     * Stops the active session (Firebase SDK or direct protocol).
     */
    @OptIn(PublicPreviewAPI::class)
    fun stopSession() {
        try {
            if (useDirectProtocol) {
                // Direct protocol mode
                directSession?.close()
                directSession = null
                Log.d(TAG, "Direct protocol session stopped")
            } else {
                // Firebase SDK mode
                runBlocking { liveSession?.close() }
                liveSession = null
                Log.d(TAG, "Firebase SDK session stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping session", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopSession()
        // Clear stored configuration
        directProtocolTools = null
        directProtocolSystemPrompt = null
        directProtocolModel = null
        directProtocolVoice = null
        generativeModel = null
    }

    companion object {
        private const val TAG = "GeminiService"
    }
}

