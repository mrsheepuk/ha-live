package uk.co.mrsheep.halive.services.conversation

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.services.GeminiLiveSession
import uk.co.mrsheep.halive.services.GeminiProtocolToolTransformer
import uk.co.mrsheep.halive.services.mcp.McpToolsListResult
import uk.co.mrsheep.halive.services.protocol.FunctionCall
import uk.co.mrsheep.halive.services.protocol.FunctionResponse
import uk.co.mrsheep.halive.services.protocol.ToolDeclaration

/**
 * Implements ConversationService for the direct Gemini Live protocol.
 *
 * This service bypasses the Firebase SDK and communicates directly with the Gemini Live API
 * via WebSocket, enabling more control and avoiding internal constructor issues.
 *
 * Responsibility:
 * - Transform MCP tools to protocol format
 * - Manage the Gemini Live session lifecycle
 * - Bridge between domain-level tool calls/responses and protocol-level types
 * - Handle transcription updates
 */
class DirectConversationService(private val context: Context) : ConversationService {

    companion object {
        private const val TAG = "DirectConversationService"
    }

    // Session state
    private var session: GeminiLiveSession? = null
    private var toolDeclarations: List<ToolDeclaration>? = null
    private var systemPrompt: String? = null
    private var modelName: String? = null
    private var voiceName: String? = null

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * Initialize the service with tools and configuration.
     *
     * Transforms MCP tools to protocol format and stores configuration for later use
     * when startSession() is called.
     *
     * @param mcpTools Raw tools from Home Assistant MCP server
     * @param systemPrompt System instructions for the AI
     * @param modelName Model to use (e.g., "models/gemini-2.0-flash-exp")
     * @param voiceName Voice to use (e.g., "Aoede")
     */
    override suspend fun initialize(
        mcpTools: McpToolsListResult,
        systemPrompt: String,
        modelName: String,
        voiceName: String
    ) {
        try {
            Log.d(TAG, "Initializing DirectConversationService with ${mcpTools.tools.size} tools")

            // Transform MCP tools to protocol format
            toolDeclarations = GeminiProtocolToolTransformer.transform(mcpTools)

            // Store configuration for later use
            this.systemPrompt = systemPrompt
            this.modelName = modelName
            this.voiceName = voiceName

            Log.d(
                TAG,
                "DirectConversationService initialized with ${toolDeclarations?.size ?: 0} tools"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing DirectConversationService", e)
            throw e
        }
    }

    /**
     * Start a conversation session.
     *
     * Establishes a connection to the Gemini Live API and starts the audio conversation.
     * Tool calls from the model are handled via the onToolCall callback.
     *
     * @param onToolCall Callback when AI wants to call a tool
     * @param onTranscript Optional callback for transcription updates
     */
    override suspend fun startSession(
        onToolCall: suspend (ToolCall) -> ToolResponse,
        onTranscript: ((TranscriptInfo) -> Unit)?
    ) {
        try {
            // Get API key from config
            val apiKey = GeminiConfig.getApiKey(context)
                ?: throw IllegalStateException("Gemini API key not configured. Please set it in settings.")

            Log.d(TAG, "Starting session with direct protocol")

            // Create session
            session = GeminiLiveSession(apiKey, context)

            // Define protocol tool call handler that converts to domain types
            val protocolToolCallHandler: suspend (FunctionCall) -> FunctionResponse = { protocolCall ->
                try {
                    Log.d(TAG, "Tool call received: ${protocolCall.name}")

                    // Convert protocol FunctionCall to domain ToolCall
                    val domainToolCall = protocolCall.toDomainToolCall()

                    // Call the domain-level handler
                    val domainResponse = onToolCall(domainToolCall)

                    // Convert domain ToolResponse back to protocol FunctionResponse
                    domainResponse.toProtocolResponse()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool call ${protocolCall.name}", e)
                    FunctionResponse(
                        id = protocolCall.id,
                        name = protocolCall.name,
                        response = null
                    )
                }
            }

            // Define transcription callback that converts protocol to domain types
            val protocolTranscriptionHandler: ((String?, String?) -> Unit)? =
                if (onTranscript != null) {
                    { userText: String?, modelText: String? ->
                        try {
                            onTranscript(TranscriptInfo(userText, modelText))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling transcription", e)
                        }
                    }
                } else {
                    null
                }

            // Start the session with stored configuration
            session?.start(
                model = modelName ?: "models/gemini-2.0-flash-exp",
                systemPrompt = systemPrompt ?: "",
                tools = toolDeclarations ?: emptyList(),
                voiceName = voiceName ?: "Aoede",
                onToolCall = protocolToolCallHandler,
                onTranscription = protocolTranscriptionHandler
            )

            Log.d(TAG, "Direct protocol session started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            session?.close()
            session = null
            throw e
        }
    }

    /**
     * Send a text message to the AI during an active session.
     *
     * @param message Text message to send
     */
    override suspend fun sendText(message: String) {
        try {
            val activeSession = session
                ?: throw IllegalStateException("Session not active")

            activeSession.sendText(message)
            Log.d(TAG, "Text message sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message", e)
            throw e
        }
    }

    /**
     * Stop the current session.
     */
    override fun stopSession() {
        try {
            session?.close()
            session = null
            Log.d(TAG, "Session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping session", e)
        }
    }

    /**
     * Clean up resources.
     */
    override fun cleanup() {
        try {
            session?.close()
            session = null
            toolDeclarations = null
            systemPrompt = null
            modelName = null
            voiceName = null
            Log.d(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Convert a protocol FunctionCall to a domain ToolCall.
     *
     * @receiver The protocol function call
     * @return A domain ToolCall with the tool name and arguments
     */
    private fun FunctionCall.toDomainToolCall(): ToolCall {
        // Convert JsonElement args to Map<String, JsonElement>
        val argsMap = args?.let { jsonElement ->
            if (jsonElement is JsonObject) {
                jsonElement.toMap()
            } else {
                emptyMap()
            }
        } ?: emptyMap()

        return ToolCall(
            id = id,
            name = name,
            arguments = argsMap
        )
    }

    /**
     * Convert a domain ToolResponse to a protocol FunctionResponse.
     *
     * @receiver The domain tool response
     * @return A protocol FunctionResponse ready to send to the API
     */
    private fun ToolResponse.toProtocolResponse(): FunctionResponse {
        // Build JSON response based on error
        val responseJson = if (error != null) {
            buildJsonObject {
                put("error", error)
            }
        } else {
            buildJsonObject {
                put("result", result)
            }
        }

        return FunctionResponse(
            id = id,
            name = name,
            response = responseJson
        )
    }
}
