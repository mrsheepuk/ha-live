package uk.co.mrsheep.halive.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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
    suspend fun startSession(
        // The ViewModel passes a lambda that knows how to call the repository
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart
    ) {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        try {
            // 1. Connect to Gemini, creating the session
            liveSession = model.connect()

            // Note: The actual Live API integration with audio conversation
            // is not fully available in the current SDK version.
            // This is a placeholder structure based on the task specification.
            // When the SDK is updated, we'll use:
            // liveSession?.startAudioConversation(
            //     functionCallHandler = functionCallHandler
            // )

            // 2. Start a coroutine to listen for responses
            listenForModelResponses(functionCallHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            throw e
        }
    }

    /**
     * A long-running coroutine to process messages from the live session
     */
    private suspend fun listenForModelResponses(
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart
    ) {
        try {
            liveSession?.collect { response ->
                // Process the stream of responses from Gemini

                // A) Handle transcribed text from the user
                // (This would come from the Live API when fully integrated)
                response.text?.let {
                    _transcribedUserText.value = it
                }

                // B) Handle the model's audio response
                // (This would come from the Live API audio stream)
                // For now, this is a placeholder
                // response.audio?.let { audioChunk ->
                //     _isSpeaking.value = true
                //     audioPlayer?.play()
                //     audioPlayer?.write(audioChunk, 0, audioChunk.size)
                // }

                // C) Handle text parts of the model's final response
                response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                    when (part) {
                        is com.google.firebase.ai.type.TextPart -> {
                            _modelResponseText.value = part.text
                        }
                        is FunctionCallPart -> {
                            // Execute the function call
                            val functionResponse = functionCallHandler(part)
                            // Send response back to the session
                            // (This would be part of the Live API integration)
                            Log.d(TAG, "Function call executed: ${part.name}")
                        }
                    }
                }

                // D) Handle end of speech
                if (response.candidates?.firstOrNull()?.finishReason != null) {
                    _isSpeaking.value = false
                    audioPlayer?.stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in listenForModelResponses", e)
            _isSpeaking.value = false
            audioPlayer?.stop()
        }
    }

    /**
     * Called by ViewModel when the user releases the "talk" button.
     */
    fun stopSession() {
        try {
            // Stop the live session
            // liveSession?.stopAudioConversation()
            // liveSession?.disconnect()
            liveSession = null

            // Clear audio
            audioPlayer?.pause()
            audioPlayer?.flush()

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
