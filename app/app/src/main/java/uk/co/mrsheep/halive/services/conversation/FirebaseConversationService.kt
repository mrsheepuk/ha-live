package uk.co.mrsheep.halive.services.conversation

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.services.GeminiMCPToolTransformer
import uk.co.mrsheep.halive.services.LocalToolDefinitions
import uk.co.mrsheep.halive.services.mcp.McpToolsListResult

/**
 * Firebase Gemini Live API implementation of ConversationService.
 *
 * This service manages conversations with the Gemini Live API using the official
 * Firebase AI SDK. It handles tool transformation, session lifecycle, and bidirectional
 * communication with the model.
 *
 * @param context Android application context for resource access
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseConversationService(private val context: Context) : ConversationService {

    private var generativeModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null

    companion object {
        private const val TAG = "FirebaseConversationService"
    }

    /**
     * Initialize the service with MCP tools and configuration.
     *
     * Transforms MCP tools to Firebase format, creates local tools, and initializes
     * the Gemini Live model with combined tools and system prompt.
     *
     * @param mcpTools Raw tools from Home Assistant MCP server
     * @param systemPrompt System instructions for the AI
     * @param modelName Model to use (e.g., "models/gemini-2.0-flash-exp")
     * @param voiceName Voice to use (e.g., "Aoede", "Leda")
     */
    @OptIn(PublicPreviewAPI::class)
    override suspend fun initialize(
        mcpTools: McpToolsListResult,
        systemPrompt: String,
        modelName: String,
        voiceName: String
    ) {
        try {
            // Transform MCP tools to Firebase format
            val mcpToolsList = GeminiMCPToolTransformer.transform(mcpTools)

            // Create local tools (e.g., EndConversation)
            val endConversationTool = LocalToolDefinitions.createEndConversationTool()
            val localTools = listOf(Tool.functionDeclarations(listOf(endConversationTool)))

            // Combine MCP tools with local tools
            val allTools = mcpToolsList + localTools

            // Initialize the generative model
            generativeModel = Firebase.ai.liveModel(
                modelName = modelName,
                systemInstruction = content { text(systemPrompt) },
                tools = allTools,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                }
            )

            Log.d(TAG, "FirebaseConversationService initialized with $modelName, " +
                    "${mcpToolsList.size + localTools.size} total tools")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseConversationService", e)
            throw e
        }
    }

    /**
     * Start a conversation session with the Gemini Live API.
     *
     * Establishes connection to the model, sets up function call and transcription handlers,
     * and begins audio conversation.
     *
     * @param onToolCall Callback invoked when the model calls a tool
     * @param onTranscript Optional callback for transcription updates
     */
    @OptIn(PublicPreviewAPI::class)
    override suspend fun startSession(
        onToolCall: suspend (ToolCall) -> ToolResponse,
        onTranscript: ((TranscriptInfo) -> Unit)?
    ) {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized. Call initialize() first.")

        try {
            // Connect to Gemini, creating the session
            liveSession = model.connect()

            // Wrap suspend function in runBlocking since SDK expects a regular function
            val functionCallHandlerAdapter: (FunctionCallPart) -> FunctionResponsePart = { firebaseCall ->
                try {
                    runBlocking {
                        // Convert Firebase FunctionCallPart to domain ToolCall
                        val domainCall = firebaseCall.toDomainToolCall()

                        // Invoke the tool call handler
                        val response = onToolCall(domainCall)

                        // Convert domain ToolResponse to Firebase FunctionResponsePart
                        response.toFirebaseResponse()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling tool call: ${firebaseCall.name}", e)
                    // Return error response to model
                    FunctionResponsePart(
                        name = firebaseCall.name,
                        response = buildJsonObject {
                            put("error", "Tool execution failed: ${e.message}")
                        }
                    )
                }
            }

            // Transcription handler - converts Firebase Transcription to domain TranscriptInfo
            val transcriptionHandlerAdapter: ((Transcription?, Transcription?) -> Unit)? =
                if (onTranscript != null) {
                    { userTranscript: Transcription?, modelTranscript: Transcription? ->
                        try {
                            val userText = userTranscript?.text
                            val modelText = modelTranscript?.text
                            onTranscript(TranscriptInfo(userText, modelText))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling transcription", e)
                        }
                    }
                } else {
                    null
                }

            // Start audio conversation with handlers
            liveSession?.startAudioConversation(
                functionCallHandler = functionCallHandlerAdapter,
                transcriptHandler = transcriptionHandlerAdapter,
                enableInterruptions = true
            )

            Log.d(TAG, "FirebaseConversationService session started successfully")

        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to access audio resources", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Firebase session", e)
            throw e
        }
    }

    /**
     * Send a text message to the active conversation session.
     *
     * @param message Text message to send
     */
    @OptIn(PublicPreviewAPI::class)
    override suspend fun sendText(message: String) {
        val session = liveSession ?: throw IllegalStateException("Session not active. Call startSession() first.")

        try {
            session.sendTextRealtime(message)
            Log.d(TAG, "Text message sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
            throw e
        }
    }

    /**
     * Stop the current conversation session.
     *
     * Gracefully closes the WebSocket connection and cleans up resources.
     */
    @OptIn(PublicPreviewAPI::class)
    override fun stopSession() {
        try {
            if (liveSession != null) {
                runBlocking {
                    liveSession?.close()
                }
                liveSession = null
                Log.d(TAG, "Session stopped successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping session", e)
        }
    }

    /**
     * Clean up all resources.
     *
     * Stops the active session and releases the generative model.
     */
    override fun cleanup() {
        try {
            runBlocking {
                stopSession()
            }
            generativeModel = null
            Log.d(TAG, "FirebaseConversationService cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Helper: Convert Firebase FunctionCallPart to domain ToolCall.
     *
     * Extracts the tool name and arguments from the Firebase representation
     * and creates a domain-level tool call object.
     */
    private fun FunctionCallPart.toDomainToolCall(): ToolCall {
        // Parse arguments as Map<String, JsonElement>
        val argsMap = (args as? Map<String, JsonElement>) ?: emptyMap()

        return ToolCall(
            id = id ?: "",
            name = name,
            arguments = argsMap
        )
    }

    /**
     * Helper: Convert domain ToolResponse to Firebase FunctionResponsePart.
     *
     * Wraps the tool response in the Firebase response format, including
     * either the result or error message.
     */
    private fun ToolResponse.toFirebaseResponse(): FunctionResponsePart {
        val responseJson = buildJsonObject {
            if (error != null) {
                put("error", error)
            } else {
                put("result", result)
            }
        }

        return FunctionResponsePart(
            name = name,
            response = responseJson
        )
    }

}
