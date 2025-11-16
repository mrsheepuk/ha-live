package uk.co.mrsheep.halive.services.geminifirebase

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.conversation.ConversationService
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult

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
class FirebaseConversationService(private val context: Context) :
    ConversationService {

    private var generativeModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null
    private var toolExecutor: ToolExecutor? = null
    private var transcriptor: ((String?, String?, Boolean) -> Unit)? = null


    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    companion object {
        private const val TAG = "FirebaseConversationService"
    }

    /**
     * Initialize the service with MCP tools and configuration.
     *
     * Transforms MCP tools to Firebase format, creates local tools, and initializes
     * the Gemini Live model with combined tools and system prompt.
     *
     * @param tools Raw tools from Home Assistant MCP server
     * @param systemPrompt System instructions for the AI
     * @param modelName Model to use (e.g., "models/gemini-2.0-flash-exp")
     * @param voiceName Voice to use (e.g., "Aoede", "Leda")
     */
    @OptIn(PublicPreviewAPI::class)
    override suspend fun initialize(
        tools: List<McpTool>,
        systemPrompt: String,
        modelName: String,
        voiceName: String,
        toolExecutor: ToolExecutor,
        transcriptor: ((String?, String?, Boolean) -> Unit)?
    ) {
        try {
            // Transform MCP tools to Firebase format
            val mcpToolsList = FirebaseMCPToolTransformer.transform(tools)

            this.toolExecutor = toolExecutor
            this.transcriptor = transcriptor

            // Initialize the generative model
            generativeModel = Firebase.ai.liveModel(
                modelName = modelName,
                systemInstruction = content { text(systemPrompt) },
                tools = mcpToolsList,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.Companion.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                }
            )

            Log.d(TAG, "FirebaseConversationService initialized with $modelName, " +
                    "${mcpToolsList.size} total tools")

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
    override suspend fun startSession() {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized. Call initialize() first.")

        try {
            // Connect to Gemini, creating the session
            liveSession = model.connect()

            // Wrap suspend function in runBlocking since SDK expects a regular function
            val functionCallHandlerAdapter: (FunctionCallPart) -> FunctionResponsePart = { firebaseCall ->
                try {
                    runBlocking {
                        toolExecutor?.callTool(firebaseCall.name, firebaseCall.args).toFirebaseResponse(firebaseCall)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling tool call: ${firebaseCall.name}", e)
                    FunctionResponsePart(name = firebaseCall.name, id = firebaseCall.id, response = buildJsonObject { put("error", "Tool execution failed: ${e.message}") })
                }
            }

            // Transcription handler - converts Firebase Transcription to domain TranscriptInfo
            val transcriptionHandlerAdapter: ((Transcription?, Transcription?) -> Unit)? =
                if (transcriptor != null) {
                    { userTranscript: Transcription?, modelTranscript: Transcription? ->
                            transcriptor?.invoke(userTranscript?.text, modelTranscript?.text, false)
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
     * Helper: Convert domain ToolResponse to Firebase FunctionResponsePart.
     *
     * Wraps the tool response in the Firebase response format, including
     * either the result or error message.
     */
    private fun ToolCallResult?.toFirebaseResponse(call: FunctionCallPart): FunctionResponsePart {
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

        return FunctionResponsePart(
            name = call.name,
            id = call.id,
            response = responseJson
        )
    }

}