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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.asCoroutineDispatcher
import android.media.AudioAttributes
import android.media.AudioManager
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
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper
import uk.co.mrsheep.halive.services.camera.VideoSource
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

        // Audio configuration
        private const val SAMPLE_RATE = 24000
        private const val BYTES_PER_SAMPLE = 2 // 16-bit mono

        // Pre-buffer 100ms of audio before starting playback
        // This absorbs network/decode jitter
        private const val PRE_BUFFER_MS = 100
        private val PRE_BUFFER_BYTES = PRE_BUFFER_MS * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000

        // Total jitter buffer capacity: 30 seconds
        // Gemini generates audio faster than real-time, so we need to buffer
        // an entire response. 30s @ 24kHz 16-bit mono = 1.44MB - acceptable.
        private const val BUFFER_CAPACITY_MS = 30000
        private val BUFFER_CAPACITY_BYTES = BUFFER_CAPACITY_MS * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000

        // Playback chunk size (~20ms of audio)
        private const val PLAYBACK_CHUNK_MS = 20
        private val PLAYBACK_CHUNK_BYTES = PLAYBACK_CHUNK_MS * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000
    }

    private val client = GeminiLiveClient(apiKey)

    @SuppressLint("ThreadPoolCreation")
    val audioDispatcher =
        Executors.newCachedThreadPool(AudioThreadFactory()).asCoroutineDispatcher()

    private var sessionScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext).apply { cancel() }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private var isSessionActive = false

    // Recording (microphone input)
    private var microphoneHelper: MicrophoneHelper? = null
    /** Whether we own the microphoneHelper and should release it on close */
    private var ownsMicrophoneHelper: Boolean = true

    // Video capture (from any video source)
    private var videoSource: VideoSource? = null
    /** Whether video capture is currently active.
     *  Volatile for visibility - set on main thread, read from Default dispatcher. */
    @Volatile
    private var isVideoCapturing = false
    /** Job for the video recording coroutine - must be cancelled on stop */
    private var videoRecordingJob: Job? = null
    /**
     * Unique ID for the current capture session. Used to ignore frames from
     * old/cancelled capture sessions that may still be in-flight.
     * AtomicLong ensures thread-safe increment and visibility across dispatchers.
     */
    private val currentCaptureId = AtomicLong(0)

    // New audio pipeline components for playback
    private var jitterBuffer: JitterBuffer? = null
    private var decodeStage: AudioDecodeStage? = null
    private var playbackThread: AudioPlaybackThread? = null
    private var playbackAudioTrack: AudioTrack? = null

    val MIN_BUFFER_SIZE =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
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
        onTranscription: ((userTranscription: String?, modelTranscription: String?, isThought: Boolean) -> Unit)? = null,
        externalMicrophoneHelper: MicrophoneHelper? = null
    ) {
        if (sessionScope.isActive) {
            throw IllegalStateException("Session scope already active, cannot start")
        }

        sessionScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("LiveSession Network"))

        // Initialize recording (microphone)
        if (externalMicrophoneHelper != null) {
            microphoneHelper = externalMicrophoneHelper
            ownsMicrophoneHelper = false
            Log.d(TAG, "Using external MicrophoneHelper (handover mode)")
        } else {
            microphoneHelper = MicrophoneHelper.build()
            ownsMicrophoneHelper = true
            Log.d(TAG, "Created new MicrophoneHelper")
        }

        // Initialize new audio playback pipeline
        initializePlaybackPipeline()

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

            // Send any pre-buffered audio from wake word detection
            if (!ownsMicrophoneHelper) {
                val preBufferedAudio = microphoneHelper?.getBufferedAudio()
                if (preBufferedAudio != null && preBufferedAudio.isNotEmpty()) {
                    Log.d(TAG, "Sending ${preBufferedAudio.size} bytes of pre-buffered audio to Gemini")
                    sendAudioRealtime(preBufferedAudio)
                    microphoneHelper?.clearPreBuffer()
                }
            }

            // Start recording (sends audio to Gemini)
            recordUserAudio()
            Log.d(TAG, "Recording loop started")

            // Start the decode stage (processes incoming audio)
            decodeStage?.start()
            Log.d(TAG, "Decode stage started")

            // Start the playback thread (plays audio from jitter buffer)
            playbackThread?.start()
            Log.d(TAG, "Playback thread started")

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
        microphoneHelper
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

    /**
     * Send a video frame to the Gemini Live API.
     *
     * @param jpegData JPEG-encoded frame data (should be max 1024x1024)
     */
    private suspend fun sendVideoRealtime(jpegData: ByteArray) {
        val base64Video = Base64.encodeToString(jpegData, Base64.NO_WRAP)
        val message = ClientMessage(
            realtimeInput = RealtimeInput(
                video = MediaChunk(
                    mimeType = "image/jpeg",
                    data = base64Video
                )
            )
        )

        // Send to server
        val messageJson = json.encodeToString(ClientMessage.serializer(), message)
        client.send(messageJson)

        Log.d(TAG, "Sent video frame: ${jpegData.size} bytes")
    }

    /**
     * Start capturing and sending video frames from any video source.
     *
     * @param source The VideoSource instance to capture frames from
     */
    fun startVideoCapture(source: VideoSource) {
        if (isVideoCapturing) {
            Log.w(TAG, "Video capture already active")
            return
        }

        videoSource = source
        isVideoCapturing = true

        // Get a unique capture ID for this session. The closure captures this value,
        // so even if frames from a previous capture are still in-flight after cancellation,
        // they'll be ignored because their captured ID won't match currentCaptureId.
        val captureId = currentCaptureId.incrementAndGet()

        // Launch video recording coroutine and store the Job for cancellation
        // Note: We store the Job returned by launchIn directly, not a wrapper launch,
        // because launchIn creates a job parented to sessionScope, not to any outer launch
        videoRecordingJob = source.frameFlow
            .onEach { frame ->
                // Check capture ID to ignore frames from old/cancelled sessions
                if (captureId == currentCaptureId.get() && isVideoCapturing && isSessionActive) {
                    sendVideoRealtime(frame)
                }
            }
            .launchIn(sessionScope)

        Log.i(TAG, "Video capture started from source: ${source.sourceId} (captureId=$captureId)")
    }

    /**
     * Stop capturing and sending video frames.
     */
    fun stopVideoCapture() {
        if (!isVideoCapturing) return

        // Increment capture ID to invalidate any in-flight frames from the old session
        val invalidatedId = currentCaptureId.incrementAndGet()

        // Cancel the video recording coroutine to prevent interleaving when switching cameras
        videoRecordingJob?.cancel()
        videoRecordingJob = null
        isVideoCapturing = false
        videoSource = null

        Log.i(TAG, "Video capture stopped (captureId invalidated to $invalidatedId)")
    }

    /**
     * Initialize the new audio playback pipeline.
     *
     * Creates:
     * - AudioTrack for hardware playback
     * - JitterBuffer for absorbing network/decode timing variations
     * - AudioDecodeStage for async Base64 decoding
     * - AudioPlaybackThread for low-latency playback
     */
    private fun initializePlaybackPipeline() {
        // Create AudioTrack for playback
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 4x minimum buffer for AudioTrack internal buffering
        val trackBufferSize = minBufferSize * 4

        // Use the same audio session ID as the AudioRecord for proper echo cancellation.
        // The AcousticEchoCanceler attached to the AudioRecord needs to know what audio
        // is being played back so it can cancel it from the microphone input.
        val sessionId = microphoneHelper?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

        playbackAudioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            trackBufferSize,
            AudioTrack.MODE_STREAM,
            sessionId
        )
        Log.d(TAG, "AudioTrack created with buffer size: $trackBufferSize bytes, sessionId: $sessionId")

        // Create jitter buffer with pre-buffering
        jitterBuffer = JitterBuffer(
            capacity = BUFFER_CAPACITY_BYTES,
            preBufferThreshold = PRE_BUFFER_BYTES,
            sampleRate = SAMPLE_RATE,
            bytesPerSample = BYTES_PER_SAMPLE
        )
        Log.d(TAG, "JitterBuffer created: capacity=${BUFFER_CAPACITY_MS}ms, preBuffer=${PRE_BUFFER_MS}ms")

        // Create decode stage (writes decoded audio to jitter buffer)
        decodeStage = AudioDecodeStage(jitterBuffer!!)

        // Create playback thread (reads from jitter buffer, writes to AudioTrack)
        playbackThread = AudioPlaybackThread(
            audioTrack = playbackAudioTrack!!,
            jitterBuffer = jitterBuffer!!,
            chunkSizeBytes = PLAYBACK_CHUNK_BYTES,
            onAudioLevel = onAudioLevel,
            onUnderrun = { Log.w(TAG, "Audio playback underrun") }
        )
        Log.d(TAG, "Playback thread created with chunk size: ${PLAYBACK_CHUNK_MS}ms")
    }

    /**
     * Shutdown the audio playback pipeline.
     */
    private fun shutdownPlaybackPipeline() {
        // Stop playback thread first
        playbackThread?.shutdown()
        try {
            playbackThread?.join(1000) // Wait up to 1 second for clean shutdown
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for playback thread")
        }
        playbackThread = null

        // Shutdown decode stage
        decodeStage?.shutdown()
        decodeStage = null

        // Clear and release jitter buffer
        jitterBuffer?.clear()
        jitterBuffer = null

        // Release AudioTrack
        try {
            playbackAudioTrack?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack already stopped")
        }
        playbackAudioTrack?.release()
        playbackAudioTrack = null

        Log.d(TAG, "Playback pipeline shutdown complete")
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
     * - Audio chunks (inlineData with mime_type="audio/pcm") → queue for async decode
     * - Text parts → transcription callback
     */
    private fun handleContentMessage(
        message: ServerMessage.Content,
        onTranscription: ((String?, String?, Boolean) -> Unit)?
    ) {
        if (message.serverContent.inputTranscription != null || message.serverContent.outputTranscription != null) {
            Log.d(TAG, "Transcript received")
            onTranscription?.invoke(message.serverContent.inputTranscription?.text, message.serverContent.outputTranscription?.text, false)
        }
        if (message.serverContent.interrupted == true) {
            Log.d(TAG, "Turn interrupted")
            // Clear the jitter buffer to immediately stop playback
            jitterBuffer?.clear()
        } else {
            for (part in message.serverContent.modelTurn?.parts.orEmpty()) {
                if (part.inlineData != null && part.inlineData.mimeType.startsWith("audio/pcm")) {
                    // Queue for async decode (non-blocking)
                    // The decode stage will Base64 decode and write to jitter buffer
                    decodeStage?.queueAudio(part.inlineData.data)
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
     * Yields the MicrophoneHelper back for handover to another service.
     * Only works if MicrophoneHelper was provided externally (not owned by this session).
     * Returns null if we created our own MicrophoneHelper or session is not active.
     *
     * After calling this, the session will not release the MicrophoneHelper on close.
     */
    fun yieldMicrophoneHelper(): MicrophoneHelper? {
        if (ownsMicrophoneHelper) {
            Log.d(TAG, "yieldMicrophoneHelper: we own the MicrophoneHelper, cannot yield")
            return null
        }

        if (microphoneHelper == null) {
            Log.d(TAG, "yieldMicrophoneHelper: no MicrophoneHelper to yield")
            return null
        }

        Log.d(TAG, "Yielding MicrophoneHelper back for handover")
        val helper = microphoneHelper
        helper?.pauseRecording()
        microphoneHelper = null
        return helper
    }

    /**
     * Close the session and clean up all resources.
     *
     * This method:
     * 1. Marks session as inactive (stops loops)
     * 2. Shuts down the new audio playback pipeline
     * 3. Releases the audio recorder
     * 4. Closes WebSocket connection in a separate scope (so it isn't cancelled)
     * 5. Shuts down the client scope
     * 6. Shuts down the audio dispatcher thread pool
     * 7. Cancels all session coroutines
     *
     * Safe to call multiple times.
     */
    fun close() {
        Log.d(TAG, "Closing session")

        isSessionActive = false

        // Stop video capture (video source is managed externally, we just stop using it)
        stopVideoCapture()

        // Shutdown the new audio playback pipeline
        shutdownPlaybackPipeline()

        // Release recording resources (only if we own them)
        if (ownsMicrophoneHelper) {
            microphoneHelper?.release()
            microphoneHelper = null
            Log.d(TAG, "Released owned MicrophoneHelper")
        } else {
            // Don't null out microphoneHelper - leave it for yieldMicrophoneHelper() to return
            Log.d(TAG, "Not releasing MicrophoneHelper (external ownership, available for yield)")
        }

        // Launch close in a separate scope so it isn't cancelled by sessionScope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                client.close()
                client.shutdown()
                Log.d(TAG, "GeminiLiveClient closed and shut down")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GeminiLiveClient", e)
            }
        }

        // Shut down the thread pool used for recording
        try {
            (audioDispatcher as? java.io.Closeable)?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing audioDispatcher", e)
        }

        sessionScope.cancel()
        Log.d(TAG, "Session closed and resources released")
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