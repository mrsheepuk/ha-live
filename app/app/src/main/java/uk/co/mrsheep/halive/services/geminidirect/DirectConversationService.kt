package uk.co.mrsheep.halive.services.geminidirect

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.conversation.ConversationService
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult
import uk.co.mrsheep.halive.services.geminidirect.protocol.FunctionCall
import uk.co.mrsheep.halive.services.geminidirect.protocol.FunctionResponse
import uk.co.mrsheep.halive.services.geminidirect.protocol.ToolDeclaration

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
class DirectConversationService(private val context: Context) :
    ConversationService {

    companion object {
        private const val TAG = "DirectConversationService"
    }

    // Session state
    private var session: GeminiLiveSession? = null
    private var toolDeclarations: List<ToolDeclaration>? = null
    private var systemPrompt: String? = null
    private var modelName: String? = null
    private var voiceName: String? = null
    private var toolExecutor: ToolExecutor? = null
    private var transcriptor: ((String?, String?, Boolean) -> Unit)? = null
    private var onAudioLevel: ((Float) -> Unit)? = null
    private var interruptable: Boolean = true

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
     * @param modelName Model to use (e.g., "gemini-2.0-flash-exp")
     * @param voiceName Voice to use (e.g., "Aoede")
     */
    override suspend fun initialize(
        tools: List<McpTool>,
        systemPrompt: String,
        modelName: String,
        voiceName: String,
        toolExecutor: ToolExecutor,
        transcriptor: ((String?, String?, Boolean) -> Unit)?,
        interruptable: Boolean,
        onAudioLevel: ((Float) -> Unit)?
    ) {
        try {
            Log.d(TAG, "Initializing DirectConversationService with ${tools.size} tools")

            // Transform MCP tools to protocol format
            this.toolDeclarations = GeminiLiveMCPToolTransformer.transform(tools)
            this.toolExecutor = toolExecutor
            this.transcriptor = transcriptor
            this.onAudioLevel = onAudioLevel

            // Store configuration for later use
            this.systemPrompt = systemPrompt
            this.modelName = modelName
            this.voiceName = voiceName
            this.interruptable = interruptable

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
     * @param audioHelper Optional AudioHelper for audio stream handover
     */
    override suspend fun startSession(audioHelper: AudioHelper?) {
        try {
            // Get API key from config
            val apiKey = GeminiConfig.getApiKey(context)
                ?: throw IllegalStateException("Gemini API key not configured. Please set it in settings.")

            Log.d(TAG, "Starting session with direct protocol")

            // Create session
            session = GeminiLiveSession(apiKey, context, onAudioLevel = onAudioLevel)

            val protocolToolCallHandler: suspend (FunctionCall) -> FunctionResponse = { call ->
                try {
                    Log.d(TAG, "Tool call received: ${call.name}")
                    toolExecutor?.callTool(call.name, call.toJsonArgs()).toProtocolResponse(call)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool call ${call.name}", e)
                    FunctionResponse(id = call.id, name = call.name, response = null)
                }
            }

            // Start the session with stored configuration
            session?.start(
                model = modelName ?: "models/gemini-2.0-flash-exp",
                systemPrompt = systemPrompt ?: "",
                tools = toolDeclarations ?: emptyList(),
                voiceName = voiceName ?: "Aoede",
                interruptable = interruptable,
                onToolCall = protocolToolCallHandler,
                onTranscription = transcriptor,
                externalAudioHelper = audioHelper
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
     * Yields the AudioHelper from the underlying GeminiLiveSession.
     */
    override fun yieldAudioHelper(): AudioHelper? {
        return session?.yieldAudioHelper()
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
            onAudioLevel = null
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
    private fun FunctionCall.toJsonArgs(): Map<String, JsonElement> {
        // Convert JsonElement args to Map<String, JsonElement>
       return args?.let { jsonElement ->
            if (jsonElement is JsonObject) {
                jsonElement.toMap()
            } else {
                emptyMap()
            }
        } ?: emptyMap()
    }

    /**
     * Convert a domain ToolResponse to a protocol FunctionResponse.
     *
     * @receiver The domain tool response
     * @return A protocol FunctionResponse ready to send to the API
     */
    private fun ToolCallResult?.toProtocolResponse(call: FunctionCall): FunctionResponse {
        // Build JSON response based on error
        val responseJson = if (this == null) {
            buildJsonObject {
                put("error", "No response")
            }
        } else if (isError == true) {
                val errorMessage = content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("\n")

                buildJsonObject {
                    put("error", errorMessage.ifEmpty { "Unknown error" })
                }
        } else {
            // Extract text content from MCP result
            val textContent = content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")

            buildJsonObject {
                put("result", json.parseToJsonElement(textContent))
            }
        }

        return FunctionResponse(
            id = call.id,
            name = call.name,
            response = responseJson
        )
    }
}