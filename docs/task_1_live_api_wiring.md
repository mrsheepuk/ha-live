# **Task 3: Gemini Live API Integration**

## **1. Objective**

To implement the "final wiring." This task focuses on the GeminiService stubbed in Task 0. This service will be responsible for owning and managing the LiveSession with the Gemini Live API.

It will:

1. Initialize the Gemini GenerativeModel with the tool list from **Task 1**.  
2. Start and stop the LiveSession, which includes managing the microphone input and audio output.  
3. Receive function_call events from Gemini and pass them to the MainViewModel, which will use **Task 2** to execute them.  
4. Receive streaming audio *from* Gemini and provide it to the Android AudioTrack system for low-latency playback.

## **2. Core Implementation: GeminiService.kt**

This class will be expanded from its "stub" state to be a fully stateful service.

// In services/GeminiService.kt

// (Imports for Firebase AI Logic, Coroutines, AudioTrack)  
import com.google.firebase.ai.generativeai.GenerativeModel  
import com.google.firebase.ai.generativeai.LiveSession  
import com.google.firebase.ai.generativeai.type.Tool  
import com.google.firebase.ai.generativeai.type.FunctionCallPart  
import com.google.firebase.ai.generativeai.type.FunctionResponsePart  
import kotlinx.coroutines.flow.MutableStateFlow  
import kotlinx.coroutines.flow.StateFlow  
import android.media.AudioTrack  
// ... other imports

class GeminiService {

    private var generativeModel: GenerativeModel? = null  
    private var liveSession: LiveSession? = null  
      
    // Outputs for the UI  
    val transcribedUserText: StateFlow<String> = MutableStateFlow("")  
    val modelResponseText: StateFlow<String> = MutableStateFlow("")  
    val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)  
      
    // Audio player for the model's voice  
    private lateinit var audioPlayer: AudioTrack // (Will be initialized)

    /**  
     * Called by ViewModel on app launch, *after* Task 1 is complete.  
     */  
    fun initializeModel(tools: List<Tool>, systemPrompt: String) {  
        // 1. Configure the model  
        generativeModel = Firebase.ai.generativeModel(  
            modelName = "gemini-live-2.5-flash-preview", // The required model for Live API  
            systemInstruction = content { text(systemPrompt) },  
            tools = tools // <-- The result of Task 1  
        )  
          
        // 2. Initialize the AudioTrack for playback  
        // (Configuration for PCM, 24kHz, 16-bit, etc. based on API output)  
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
              
            // 2. Start the built-in audio conversation  
            // This function handles the microphone for us AND  
            // passes us a handler for function calls.  
            liveSession?.startAudioConversation(  
                functionCallHandler = functionCallHandler // <-- Plugs in Task 2  
            )

            // 3. Start a *separate* coroutine to listen for responses  
            listenForModelResponses()

        } catch (e: Exception) {  
            // Handle connection errors  
        }  
    }

    /**  
     * A long-running coroutine to process messages from the live session  
     */  
    private suspend fun listenForModelResponses() {  
        liveSession?.receive()?.collect { response ->  
            // Process the stream of responses from Gemini  
              
            // A) Handle transcribed text from the user  
            response.text?.let {  
                transcribedUserText.value = it  
            }  
              
            // B) Handle the model's audio response  
            response.audio?.let { audioChunk ->  
                // This is streaming audio. Play it *immediately*.  
                isSpeaking.value = true  
                audioPlayer.play()  
                audioPlayer.write(audioChunk, 0, audioChunk.size)  
            }  
              
            // C) Handle text parts of the model's final response  
            response.content?.parts?.forEach { part ->  
                if (part is TextPart) {  
                    modelResponseText.value = part.text  
                }  
            }  
              
            // D) Handle end of speech  
            if (response.isTerminal) {  
                isSpeaking.value = false  
                audioPlayer.stop()  
            }  
        }  
    }

    /**  
     * Called by ViewModel when the user releases the "talk" button.  
     */  
    fun stopSession() {  
        liveSession?.stopAudioConversation()  
        liveSession?.disconnect()  
        liveSession = null  
        audioPlayer.pause() // Clear any pending audio  
        transcribedUserText.value = ""  
        modelResponseText.value = ""  
    }  
}

## **3. Core Orchestration: MainViewModel.kt**

This class is the "orchestrator" that uses all the other components.

// In ui/MainViewModel.kt

class MainViewModel(  
    private val geminiService: GeminiService,  
    private val homeAssistantRepository: HomeAssistantRepository  
) : ViewModel() {

    // (Expose UI state flows from GeminiService and local state)  
    val uiState: StateFlow<UiState> = ...

    init {  
        // Start the app's initialization logic  
        initializeApp()  
    }

    private fun initializeApp() {  
        viewModelScope.launch {  
            try {  
                // TASK 1: Fetch and transform tools  
                val systemPrompt = "You are a helpful home assistant."  
                val tools = homeAssistantRepository.fetchTools() // (From Task 1)  
                  
                // TASK 3: Initialize the Gemini model  
                geminiService.initializeModel(tools, systemPrompt)  
                  
                uiState.value = UiState.READY_TO_TALK  
            } catch (e: Exception) {  
                uiState.value = UiState.ERROR("Failed to initialize: ${e.message}")  
            }  
        }  
    }  
      
    fun onTalkButtonPressed() {  
        uiState.value = UiState.LISTENING  
        viewModelScope.launch {  
            // Start the session, passing our Task 2 executor as the handler  
            geminiService.startSession(  
                functionCallHandler = ::executeHomeAssistantTool  
            )  
        }  
    }  
      
    fun onTalkButtonReleased() {  
        geminiService.stopSession()  
        uiState.value = UiState.READY_TO_TALK  
    }  
      
    /**  
     * This is the function that is passed to `geminiService`.  
     * It directly connects the Gemini `functionCall` to our Task 2 executor.  
     */  
    private suspend fun executeHomeAssistantTool(call: FunctionCallPart): FunctionResponsePart {  
        uiState.value = UiState.EXECUTING_ACTION  
          
        // TASK 2: Execute the tool  
        val result = homeAssistantRepository.executeTool(call) // (From Task 2)  
          
        uiState.value = UiState.LISTENING // Return to listening state  
        return result  
    }  
}

## **4. Key Challenges & Decisions**

* **Audio Playback:** This is the most complex *new* part of this task. The Gemini Live API sends raw PCM audio chunks. We cannot use MediaPlayer. We **must** use the lower-level Android AudioTrack class, configured with the correct sample rate (e.g., 24kHz), encoding (16-bit PCM), and channel (mono) to play this audio stream as it arrives.  
* **State Management:** The UI state is complex. The user might release the button (onTalkButtonReleased) while Gemini is in the middle of a functionCall. The logic must be robust to handle this (e.g., stopSession should gracefully cancel the functionCallHandler if possible, or wait for it to complete).  
* **Function Handler as a Lambda:** The decision to pass ::executeHomeAssistantTool as a function reference (suspend (FunctionCallPart) -> FunctionResponsePart) is deliberate. It completely decouples the GeminiService from the HomeAssistantRepository, which is excellent for testing and separation of concerns.