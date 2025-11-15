package uk.co.mrsheep.halive.services

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeoutException
import uk.co.mrsheep.halive.services.audio.GeminiAudioManager
import uk.co.mrsheep.halive.services.protocol.ClientContent
import uk.co.mrsheep.halive.services.protocol.ClientMessage
import uk.co.mrsheep.halive.services.protocol.Content
import uk.co.mrsheep.halive.services.protocol.FunctionCall
import uk.co.mrsheep.halive.services.protocol.FunctionResponse
import uk.co.mrsheep.halive.services.protocol.GenerationConfig
import uk.co.mrsheep.halive.services.protocol.MediaChunk
import uk.co.mrsheep.halive.services.protocol.PrebuiltVoiceConfig
import uk.co.mrsheep.halive.services.protocol.RealtimeInput
import uk.co.mrsheep.halive.services.protocol.ServerMessage
import uk.co.mrsheep.halive.services.protocol.ServerPart
import uk.co.mrsheep.halive.services.protocol.SetupMessage
import uk.co.mrsheep.halive.services.protocol.SpeechConfig
import uk.co.mrsheep.halive.services.protocol.TextPart
import uk.co.mrsheep.halive.services.protocol.ToolDeclaration
import uk.co.mrsheep.halive.services.protocol.ToolResponse
import uk.co.mrsheep.halive.services.protocol.Turn
import uk.co.mrsheep.halive.services.protocol.VoiceConfig

/**
 * GeminiLiveSession orchestrates the Gemini Live API WebSocket connection,
 * audio I/O, and tool execution.
 *
 * Architecture: 3 concurrent coroutines
 * 1. Recording Loop: Captures microphone audio → base64 encode → send to API
 * 2. Message Handling Loop: Receive messages → handle audio/transcription/tools
 * 3. Playback: Handled internally by GeminiAudioManager
 *
 * Lifecycle:
 * 1. Create instance
 * 2. Call start() with configuration
 * 3. Use sendText() to send messages during conversation
 * 4. Call close() to clean up
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "GeminiLiveSession"
        private const val SETUP_TIMEOUT_MS = 10000L
    }

    private val client = GeminiLiveClient(apiKey)
    private val audioManager = GeminiAudioManager()

    // Session-scoped coroutines with Dispatchers.Default for CPU-bound work
    private val sessionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private var playbackChannel: Channel<ByteArray>? = null
    private var isSessionActive = false

    /**
     * Start the Gemini Live session.
     *
     * This method:
     * 1. Establishes WebSocket connection to Gemini Live API
     * 2. Sends setup message with model, config, tools, and system prompt
     * 3. Waits for setupComplete confirmation from server
     * 4. Starts audio playback channel
     * 5. Launches recording loop coroutine
     * 6. Launches message handling loop coroutine
     *
     * @param model Gemini model name (e.g., "models/gemini-2.0-flash-exp")
     * @param systemPrompt System instruction for the model
     * @param tools List of tool declarations available to the model
     * @param voiceName Prebuilt voice name (e.g., "Aoede")
     * @param onToolCall Callback to execute tool calls. Called for each function call
     *                   with the call details, expected to return the execution result.
     * @param onTranscription Optional callback for transcription events (user input, model output)
     * @throws IllegalStateException if connection fails or setup doesn't complete
     * @throws TimeoutException if setup doesn't complete within timeout
     */
    suspend fun start(
        model: String,
        systemPrompt: String,
        tools: List<ToolDeclaration>,
        voiceName: String,
        onToolCall: suspend (FunctionCall) -> FunctionResponse,
        onTranscription: ((userTranscription: String?, modelTranscription: String?) -> Unit)? = null
    ) {
        try {
            Log.d(TAG, "Starting Gemini Live session with model: $model")
            isSessionActive = true

            // Step 1: Connect to Gemini Live API
            if (!client.connect()) {
                throw IllegalStateException(
                    "Failed to connect to Gemini Live API WebSocket. " +
                    "This could be due to:\n" +
                    "1. Network connectivity issues\n" +
                    "2. Invalid API key\n" +
                    "3. Connection timeout (5 seconds)\n" +
                    "4. Firewall blocking the connection\n" +
                    "Check logcat for detailed error from GeminiLiveClient"
                )
            }
            Log.d(TAG, "WebSocket connected")

            // Step 2: Send setup message
            val setupMessage = ClientMessage(
                setup = SetupMessage(
                    model = "models/$model",
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName)
                            )
                        )
                    ),
                    systemInstruction = Content(
                        role = null,
                        parts = listOf(TextPart(systemPrompt))
                    ),
                    tools = tools.takeIf { it.isNotEmpty() }
                )
            )

            val setupJson = json.encodeToString(ClientMessage.serializer(), setupMessage)
            client.send(setupJson)
            Log.d(TAG, "Setup message sent with ${tools.size} tools")

            // Step 3: Start message handling loop FIRST (before waiting for setup)
            // This ensures we don't miss any messages
            val setupCompleteDeferred = CompletableDeferred<Boolean>()

            sessionScope.launch {
                messageHandlingLoop(onToolCall, onTranscription, setupCompleteDeferred)
            }
            Log.d(TAG, "Message handling loop coroutine launched")

            // Step 4: Wait for SetupComplete message (timeout after 10 seconds)
            val setupCompleted = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                setupCompleteDeferred.await()
                true
            }

            if (setupCompleted == null) {
                throw TimeoutException("Setup did not complete within ${SETUP_TIMEOUT_MS}ms")
            }
            Log.d(TAG, "Setup completed successfully")

            // Step 5: Start audio playback
            playbackChannel = audioManager.startPlayback()
            Log.d(TAG, "Audio playback started")

            // Step 6: Launch recording loop
            sessionScope.launch {
                recordingLoop()
            }
            Log.d(TAG, "Recording loop coroutine launched")

            Log.i(TAG, "Gemini Live session started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            close()
            throw e
        }
    }

    /**
     * Recording loop coroutine (Coroutine 1).
     *
     * Continuously captures audio from the microphone via GeminiAudioManager,
     * encodes to base64 (NO_WRAP), and sends to the API via RealtimeInputMessage.
     *
     * Runs until the session is closed or an error occurs.
     */
    private suspend fun recordingLoop() {
        try {
            Log.d(TAG, "Recording loop started")

            audioManager.startRecording().collect { audioBytes ->
                if (!isSessionActive) {
                    Log.d(TAG, "Session inactive, stopping recording loop")
                    return@collect
                }

                // Encode to base64 using NO_WRAP (no line breaks)
                val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                // Build RealtimeInputMessage
                val message = ClientMessage(
                    realtimeInput = RealtimeInput(
                        mediaChunks = listOf(
                            MediaChunk(
                                mimeType = "audio/pcm",
                                data = base64Audio
                            )
                        )
                    )
                )

                // Send to server
                val messageJson = json.encodeToString(ClientMessage.serializer(), message)
                client.send(messageJson)

                Log.v(TAG, "Sent audio chunk: ${audioBytes.size} bytes")
            }

        } catch (e: Exception) {
            if (isSessionActive) {
                Log.e(TAG, "Recording loop error", e)
            }
        } finally {
            Log.d(TAG, "Recording loop ended")
        }
    }

    /**
     * Message handling loop coroutine (Coroutine 2).
     *
     * Consumes server messages from the WebSocket and handles:
     * - ServerMessage.Content: Extracts audio (inlineData) and transcriptions
     * - ServerMessage.ToolCall: Executes tool calls via callback, sends response
     * - ServerMessage.SetupComplete: Already processed in start()
     * - ServerMessage.ToolCallCancellation: Logs cancellation
     *
     * Runs until the session is closed or an error occurs.
     */
    private suspend fun messageHandlingLoop(
        onToolCall: suspend (FunctionCall) -> FunctionResponse,
        onTranscription: ((String?, String?) -> Unit)? = null,
        setupCompleteDeferred: CompletableDeferred<Boolean>? = null
    ) {
        try {
            Log.d(TAG, "Message handling loop started")

            client.messages().collect { message ->
                if (!isSessionActive) {
                    Log.d(TAG, "Session inactive, stopping message handling loop")
                    return@collect
                }

                Log.d(TAG, "Message received: ${message.javaClass.simpleName}")

                when (message) {
                    is ServerMessage.Content -> {
                        handleContentMessage(message, onTranscription)
                    }

                    is ServerMessage.ToolCall -> {
                        handleToolCallMessage(message, onToolCall)
                    }

                    is ServerMessage.SetupComplete -> {
                        Log.d(TAG, "SetupComplete received")
                        setupCompleteDeferred?.complete(true)
                    }

                    is ServerMessage.ToolCallCancellation -> {
                        Log.d(TAG, "Tool call cancelled: id=${message.toolCallCancellation.id}")
                    }
                }
            }

        } catch (e: Exception) {
            if (isSessionActive) {
                Log.e(TAG, "Message handling loop error", e)
            }
        } finally {
            Log.d(TAG, "Message handling loop ended")
        }
    }

    /**
     * Handle ServerMessage.Content.
     *
     * Extracts:
     * - Audio chunks (inlineData with mime_type="audio/pcm") → decode base64, queue for playback
     * - Text parts → transcription callback
     */
    private suspend fun handleContentMessage(
        message: ServerMessage.Content,
        onTranscription: ((String?, String?) -> Unit)?
    ) {
        message.serverContent.modelTurn?.parts?.forEach { part ->
            when (part) {
                is ServerPart.InlineDataPart -> {
                    // Audio response from model
                    if (part.inlineData.mimeType == "audio/pcm") {
                        try {
                            // Decode base64 audio
                            val audioBytes = Base64.decode(part.inlineData.data, Base64.NO_WRAP)
                            Log.v(TAG, "Decoded audio chunk: ${audioBytes.size} bytes")

                            // Queue for playback
                            playbackChannel?.send(audioBytes)
                            Log.v(TAG, "Queued audio for playback")

                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding audio chunk", e)
                        }
                    }
                }

                is ServerPart.Text -> {
                    // Text transcription from model
                    Log.d(TAG, "Received text from model: ${part.text.take(100)}")
                    onTranscription?.invoke(null, part.text)
                }
            }
        }

        // Log turn completion
        if (message.serverContent.turnComplete == true) {
            Log.d(TAG, "Turn completed")
        }

        if (message.serverContent.interrupted == true) {
            Log.d(TAG, "Turn interrupted")
        }
    }

    /**
     * Handle ServerMessage.ToolCall.
     *
     * For each function call in the message:
     * 1. Execute via onToolCall callback
     * 2. Build ToolResponseMessage with result
     * 3. Send response back to server
     *
     * If execution fails, sends error response.
     */
    private suspend fun handleToolCallMessage(
        message: ServerMessage.ToolCall,
        onToolCall: suspend (FunctionCall) -> FunctionResponse
    ) {
        message.toolCall.functionCalls?.forEach { functionCall ->
            try {
                Log.d(TAG, "Executing tool call: id=${functionCall.id}, name=${functionCall.name}")

                // Execute the tool
                val response = onToolCall(functionCall)

                // Send response back to server
                val responseMessage = ClientMessage(
                    toolResponse = ToolResponse(
                        functionResponses = listOf(response)
                    )
                )

                val responseJson = json.encodeToString(ClientMessage.serializer(), responseMessage)
                client.send(responseJson)

                Log.d(TAG, "Tool response sent: id=${response.id}")

            } catch (e: Exception) {
                Log.e(TAG, "Error executing tool ${functionCall.name}", e)

                // Send error response
                try {
                    val errorResponse = ClientMessage(
                        toolResponse = ToolResponse(
                            functionResponses = listOf(
                                FunctionResponse(
                                    id = functionCall.id,
                                    name = functionCall.name,
                                    response = null // Tool executor should handle error
                                )
                            )
                        )
                    )

                    val errorJson = json.encodeToString(ClientMessage.serializer(), errorResponse)
                    client.send(errorJson)

                } catch (sendError: Exception) {
                    Log.e(TAG, "Could not send error response", sendError)
                }
            }
        }
    }

    /**
     * Send a text message to the model.
     *
     * This can be called at any time during an active session.
     * The message will be sent via ClientContentMessage.
     *
     * @param text The text to send
     */
    suspend fun sendText(text: String) {
        if (!isSessionActive) {
            Log.w(TAG, "sendText called but session is not active")
            return
        }

        try {
            Log.d(TAG, "Sending text: $text")

            val message = ClientMessage(
                clientContent = ClientContent(
                    turns = listOf(
                        Turn(
                            role = "user",
                            parts = listOf(TextPart(text))
                        )
                    )
                )
            )

            val messageJson = json.encodeToString(ClientMessage.serializer(), message)
            client.send(messageJson)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending text", e)
        }
    }

    /**
     * Close the session and clean up all resources.
     *
     * This method:
     * 1. Marks session as inactive (stops loops)
     * 2. Closes playback channel
     * 3. Cleans up audio manager (stops recording/playback)
     * 4. Closes WebSocket connection
     * 5. Cancels all session coroutines
     *
     * Safe to call multiple times.
     */
    fun close() {
        Log.d(TAG, "Closing session")

        isSessionActive = false

        // Close playback channel first
        playbackChannel?.close()
        Log.d(TAG, "Playback channel closed")

        // Clean up audio manager
        try {
            audioManager.cleanup()
            Log.d(TAG, "Audio manager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up audio manager", e)
        }

        // Close WebSocket (non-blocking)
        sessionScope.launch {
            try {
                client.close()
                Log.d(TAG, "WebSocket closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing WebSocket", e)
            }
        }

        // Cancel all coroutines in session scope
        sessionScope.cancel()
        Log.d(TAG, "Session scope cancelled")

        Log.i(TAG, "Session closed")
    }
}
