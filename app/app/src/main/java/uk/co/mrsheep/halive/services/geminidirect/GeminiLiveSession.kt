package uk.co.mrsheep.halive.services.geminidirect

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.Process
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import uk.co.mrsheep.halive.services.geminidirect.protocol.AudioTranscriptionConfig
import uk.co.mrsheep.halive.services.geminidirect.protocol.RealtimeInputConfig
import java.util.concurrent.TimeoutException
import uk.co.mrsheep.halive.services.geminidirect.protocol.ClientContent
import uk.co.mrsheep.halive.services.geminidirect.protocol.ClientMessage
import uk.co.mrsheep.halive.services.geminidirect.protocol.Content
import uk.co.mrsheep.halive.services.geminidirect.protocol.FunctionCall
import uk.co.mrsheep.halive.services.geminidirect.protocol.FunctionResponse
import uk.co.mrsheep.halive.services.geminidirect.protocol.GenerationConfig
import uk.co.mrsheep.halive.services.geminidirect.protocol.MediaChunk
import uk.co.mrsheep.halive.services.geminidirect.protocol.PrebuiltVoiceConfig
import uk.co.mrsheep.halive.services.geminidirect.protocol.ProactivtyConfig
import uk.co.mrsheep.halive.services.geminidirect.protocol.RealtimeInput
import uk.co.mrsheep.halive.services.geminidirect.protocol.ServerMessage
import uk.co.mrsheep.halive.services.geminidirect.protocol.SetupMessage
import uk.co.mrsheep.halive.services.geminidirect.protocol.SpeechConfig
import uk.co.mrsheep.halive.services.geminidirect.protocol.TextPart
import uk.co.mrsheep.halive.services.geminidirect.protocol.ToolDeclaration
import uk.co.mrsheep.halive.services.geminidirect.protocol.ToolResponse
import uk.co.mrsheep.halive.services.geminidirect.protocol.Turn
import uk.co.mrsheep.halive.services.geminidirect.protocol.VoiceConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

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
    private val context: Context,
    private val onAudioLevel: ((Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GeminiLiveSession"
        private const val SETUP_TIMEOUT_MS = 10000L
    }

    private val client = GeminiLiveClient(apiKey)

    @SuppressLint("ThreadPoolCreation")
    val audioDispatcher =
        Executors.newCachedThreadPool(AudioThreadFactory()).asCoroutineDispatcher()

    private var sessionScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext).apply { cancel() }
    private var audioScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext).apply { cancel() }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val playBackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var isSessionActive = false
    private var audioHelper: AudioHelper? = null

    val MIN_BUFFER_SIZE =
        AudioTrack.getMinBufferSize(
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
        interruptable: Boolean = true,
        onToolCall: suspend (FunctionCall) -> FunctionResponse,
        onTranscription: ((userTranscription: String?, modelTranscription: String?, isThought: Boolean) -> Unit)? = null
    ) {
        if (sessionScope.isActive || audioScope.isActive) {
            throw IllegalStateException("Audio / session scope already active, cannot start")
        }


        sessionScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("LiveSession Network"))
        audioScope = CoroutineScope(audioDispatcher + CoroutineName("LiveSession Audio"))
        audioHelper = AudioHelper.build()

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
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName),
                            ),
                            // TODO: Make language code configurable
                            languageCode = "en-US"
                        ),
                        // rejected by API in setup, probably not supported yet:
                        //enableAffectiveDialog = true,
                    ),
                    systemInstruction = Content(
                        role = null,
                        parts = listOf(TextPart(systemPrompt))
                    ),
                    tools = tools.takeIf { it.isNotEmpty() },
                    // rejected by API in setup, probably not supported yet:
                    //proactivity = ProactivtyConfig(proactiveAudio = true),
                    inputAudioTranscription = if (onTranscription != null) AudioTranscriptionConfig() else null,
                    outputAudioTranscription = if (onTranscription != null) AudioTranscriptionConfig() else null,
                    realtimeInputConfig = if (!interruptable) {
                        RealtimeInputConfig(activityHandling = "NO_INTERRUPTION")
                    } else {
                        null
                    }
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
            Log.d(TAG, "Message handling loop started")

            // Step 4: Wait for SetupComplete message (timeout after 10 seconds)
            val setupCompleted = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                setupCompleteDeferred.await()
                true
            }
            if (setupCompleted == null) {
                throw TimeoutException("Setup did not complete within ${SETUP_TIMEOUT_MS}ms")
            }
            Log.d(TAG, "Setup completed successfully")

            recordUserAudio()
            Log.d(TAG, "Recording loop started")
            listenForModelPlayback()
            Log.d(TAG, "Audio playback started")

            Log.i(TAG, "Gemini Live session started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            close()
            throw e
        }
    }

    /** Listen to the user's microphone and send the data to the model. */
    private fun recordUserAudio() {
        // Buffer the recording so we can keep recording while data is sent to the server
        audioHelper
            ?.listenToRecording()
            ?.buffer(UNLIMITED)
            ?.flowOn(audioDispatcher)
            ?.accumulateUntil(MIN_BUFFER_SIZE)
            ?.onEach {
                sendAudioRealtime(it)
                // delay uses a different scheduler in the backend, so it's "stickier" in its enforcement
                // when compared to yield.
                delay(0)
            }
            ?.launchIn(sessionScope)
    }

    private suspend fun sendAudioRealtime(audio: ByteArray) {
        val base64Audio = Base64.encodeToString(audio, Base64.NO_WRAP)
        val message = ClientMessage(
            realtimeInput = RealtimeInput(
                audio = MediaChunk(
                    mimeType = "audio/pcm",
                    data = base64Audio
                )
            )
        )

        // Send to server
        val messageJson = json.encodeToString(ClientMessage.serializer(), message)
        client.send(messageJson)

        Log.v(TAG, "Sent audio chunk: ${audio.size} bytes")
    }

    private fun calculateRmsLevel(data: ByteArray): Float {
        if (data.size < 2) return 0f
        var sum = 0.0
        for (i in 0 until data.size - 1 step 2) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sum += signedSample * signedSample
        }
        val rms = kotlin.math.sqrt(sum / (data.size / 2))
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun listenForModelPlayback() {
        audioScope.launch {
            Log.d(TAG, "starting audio playback")
            // Channel iterator automatically suspends when empty (no busy-wait)
            for (playbackData in playBackQueue) {
                onAudioLevel?.invoke(calculateRmsLevel(playbackData))
                audioHelper?.playAudio(playbackData)
            }
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
        onTranscription: ((String?, String?, Boolean) -> Unit)? = null,
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
        onTranscription: ((String?, String?, Boolean) -> Unit)?
    ) {
        if (message.serverContent.inputTranscription != null || message.serverContent.outputTranscription != null) {
            Log.d(TAG, "Transcript received")
            onTranscription?.invoke(message.serverContent.inputTranscription?.text, message.serverContent.outputTranscription?.text, false)
        }
        if (message.serverContent.interrupted == true) {
            Log.d(TAG, "Turn interrupted")
            // Drain the channel to clear queued audio
            while (playBackQueue.tryReceive().isSuccess) { /* drain */ }
        } else {
            for (part in message.serverContent.modelTurn?.parts.orEmpty()) {
                if (part.inlineData != null && part.inlineData.mimeType.startsWith("audio/pcm")) {
                    val audioBytes = Base64.decode(part.inlineData.data, Base64.NO_WRAP)
                    playBackQueue.send(audioBytes)
                }
                if (part.text != null) {
                    onTranscription?.invoke(null, part.text, true)
                }
            }
        }
        // Log turn completion
        if (message.serverContent.turnComplete == true) {
            Log.d(TAG, "Turn completed")
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
                            parts = listOf(TextPart(text)),
                        )
                    ),
                    turnComplete = true,
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
     * 6. Shuts down audio thread pool
     *
     * Safe to call multiple times.
     */
    fun close() {
        Log.d(TAG, "Closing session")

        isSessionActive = false


        audioScope.cancel()
        playBackQueue.close()

        audioHelper?.release()
        audioHelper = null

        // Close WebSocket (non-blocking)
        sessionScope.launch {
            try {
                client.cleanup()
                Log.d(TAG, "GeminiLiveClient cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up GeminiLiveClient", e)
            }
        }

        // Cancel all coroutines in session scope
        sessionScope.cancel()
        Log.d(TAG, "Session scope cancelled")

        // Shutdown audio thread pool to prevent thread leaks
        try {
            (audioDispatcher.executor as? ExecutorService)?.shutdown()
            Log.d(TAG, "Audio dispatcher thread pool shut down")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down audio dispatcher", e)
        }

        Log.i(TAG, "Session closed")
    }
}

internal class AudioThreadFactory : ThreadFactory {
    private val threadCount = AtomicLong()
    private val policy: ThreadPolicy = audioPolicy()

    override fun newThread(task: Runnable?): Thread? {
        val thread =
            DEFAULT.newThread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                StrictMode.setThreadPolicy(policy)
                task?.run()
            }
        thread.name = "Audio Thread #${threadCount.andIncrement}"
        return thread
    }

    companion object {
        val DEFAULT: ThreadFactory = Executors.defaultThreadFactory()

        private fun audioPolicy(): ThreadPolicy {
            val builder = ThreadPolicy.Builder().detectNetwork()
            return builder.penaltyLog().build()
        }
    }
}

internal fun Flow<ByteArray>.accumulateUntil(
    minSize: Int,
    emitLeftOvers: Boolean = false
): Flow<ByteArray> = flow {
    val remaining =
        fold(ByteArrayOutputStream()) { buffer, it ->
            buffer.apply {
                write(it, 0, it.size)
                if (size() >= minSize) {
                    emit(toByteArray())
                    reset()
                }
            }
        }

    if (emitLeftOvers && remaining.size() > 0) {
        emit(remaining.toByteArray())
    }
}