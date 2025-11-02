package uk.co.mrsheep.halive.services

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(PublicPreviewAPI::class)
class GeminiService {

    private var generativeModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null

    // Outputs for the UI
    private val _transcribedUserText = MutableStateFlow("")
    val transcribedUserText: StateFlow<String> = _transcribedUserText.asStateFlow()

    private val _modelResponseText = MutableStateFlow("")
    val modelResponseText: StateFlow<String> = _modelResponseText.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Audio player for the model's voice
    private var audioPlayer: AudioTrack? = null

    /**
     * Called by ViewModel on app launch, *after* Task 1 is complete.
     */
    @OptIn(PublicPreviewAPI::class)
    fun initializeModel(tools: List<Tool>, systemPrompt: String) {
        // 1. Configure the model
        generativeModel = Firebase.ai.liveModel(
            modelName = "gemini-live-2.5-flash-preview",
            systemInstruction = content { text(systemPrompt) },
            tools = tools
        )

        // 2. Initialize the AudioTrack for playback
        // Configuration for PCM, 24kHz, 16-bit mono based on Gemini API output
        val sampleRate = 24000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioPlayer = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.d(TAG, "GeminiService initialized with ${tools.size} tools")
    }

    /**
     * Called by ViewModel when the user presses the "talk" button.
     * The handler is the *key* to connecting Task 2.
     */
    @OptIn(PublicPreviewAPI::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startSession(
        // The ViewModel passes a lambda that knows how to call the repository
        functionCallHandler: (FunctionCallPart) -> FunctionResponsePart
    ) {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        try {
            // 1. Connect to Gemini, creating the session
            liveSession = model.connect()

            // 2. Start audio conversation with function call handler
            liveSession?.startAudioConversation(
                functionCallHandler = functionCallHandler
            )

            // 3. Start listening for responses
            listenForModelResponses()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            throw e
        }
    }

    /**
     * A long-running coroutine to process messages from the live session
     */
    @OptIn(PublicPreviewAPI::class)
    private suspend fun listenForModelResponses() {
        try {
            liveSession?.receive()?.collect { message ->
                // Process the stream of messages from Gemini
                when (message) {
                    is com.google.firebase.ai.type.LiveServerContent -> {
                        // Handle content from the model
                        message.content?.parts?.forEach { part ->
                            when (part) {
                                is com.google.firebase.ai.type.TextPart -> {
                                    _modelResponseText.value = part.text
                                    _isSpeaking.value = true
                                }
                            }
                        }

                        // Handle turn completion
                        if (message.turnComplete) {
                            _isSpeaking.value = false
                        }
                    }
                    is com.google.firebase.ai.type.LiveServerSetupComplete -> {
                        Log.d(TAG, "Live session setup complete")
                    }
                    is com.google.firebase.ai.type.LiveServerToolCall -> {
                        // This shouldn't happen when using startAudioConversation
                        // with a functionCallHandler, but log it just in case
                        Log.d(TAG, "Received tool call request: ${message.functionCalls.size} calls")
                    }
                    is com.google.firebase.ai.type.LiveServerToolCallCancellation -> {
                        Log.d(TAG, "Tool call cancellation: ${message.functionIds}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in listenForModelResponses", e)
            _isSpeaking.value = false
        }
    }

    /**
     * Called by ViewModel when the user releases the "talk" button.
     */
    @OptIn(PublicPreviewAPI::class)
    fun stopSession() {
        try {
            // Stop the audio conversation and close the session
            liveSession?.stopAudioConversation()
            liveSession?.stopReceiving()
            liveSession = null

            // Reset state
            _transcribedUserText.value = ""
            _modelResponseText.value = ""
            _isSpeaking.value = false

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
        audioPlayer?.release()
        audioPlayer = null
    }

    companion object {
        private const val TAG = "GeminiService"
    }
}
