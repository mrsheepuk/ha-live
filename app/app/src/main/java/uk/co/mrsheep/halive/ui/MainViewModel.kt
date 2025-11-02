package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.services.GeminiService
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Define the different states our UI can be in
sealed class UiState {
    object Loading : UiState()
    object FirebaseConfigNeeded : UiState()  // Need google-services.json
    object HAConfigNeeded : UiState()        // Need HA URL + token
    object ReadyToTalk : UiState()           // Everything initialized, ready to start chat
    object ChatActive : UiState()            // Chat session is active (listening or executing)
    object Listening : UiState()
    object ExecutingAction : UiState()       // Executing a Home Assistant action
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val app = application as HAGeminiApp
    private val geminiService = GeminiService()

    // Track whether a chat session is currently active
    private var isSessionActive = false

    init {
        checkConfiguration()
    }

    /**
     * Check what needs to be configured.
     */
    private fun checkConfiguration() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading

            // Step 1: Check Firebase
            if (FirebaseApp.getApps(getApplication()).isEmpty()) {
                _uiState.value = UiState.FirebaseConfigNeeded
                return@launch
            }

            // Step 2: Check Home Assistant
            if (!HAConfig.isConfigured(getApplication())) {
                _uiState.value = UiState.HAConfigNeeded
                return@launch
            }

            // Step 3: Initialize MCP connection and Gemini
            try {
                val (haUrl, haToken) = HAConfig.loadConfig(getApplication())!!
                app.initializeHomeAssistant(haUrl, haToken)
                initializeGemini()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect to HA: ${e.message}")
            }
        }
    }

    /**
     * Called by MainActivity when the user selects a Firebase config file.
     */
    fun saveFirebaseConfigFile(uri: Uri) {
        viewModelScope.launch {
            try {
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)

                // Try to initialize Firebase with the new config
                if (FirebaseConfig.initializeFirebase(getApplication())) {
                    // Move to next step: HA config
                    checkConfiguration()
                } else {
                    _uiState.value = UiState.Error("Invalid Firebase config file.")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    /**
     * Called by MainActivity when user provides HA credentials.
     */
    fun saveHAConfig(baseUrl: String, token: String) {
        viewModelScope.launch {
            try {
                // Validate inputs (basic check)
                if (baseUrl.isBlank() || token.isBlank()) {
                    _uiState.value = UiState.Error("URL and token cannot be empty")
                    return@launch
                }

                // Save config
                HAConfig.saveConfig(getApplication(), baseUrl, token)

                // Try to initialize MCP connection
                app.initializeHomeAssistant(baseUrl, token)

                // Initialize Gemini
                initializeGemini()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect: ${e.message}")
                // Clear bad config
                HAConfig.clearConfig(getApplication())
            }
        }
    }

    /**
     * Initialize the Gemini model with tools from Task 1 (currently mocked).
     */
    private suspend fun initializeGemini() {
        try {
            // TASK 1: Fetch and transform tools
            // For now, we'll use an empty list as a placeholder
            // Once Task 1 is complete, this will be:
            // val tools = app.haRepository?.fetchTools() ?: emptyList()
            val tools = emptyList<com.google.firebase.ai.type.Tool>()

            val systemPrompt = """
            You are a helpful home assistant. 
            You can control devices and answer questions about the user's home.
            You will be provided with the audio stream from the user, you must respond using audio.
            Respond with a British English (en-GB) accent.
            
            You are 'House Computer' (also called 'Lizzy H' or 'House Lizard'), a helpful voice assistant for Home Assistant for Mark and Audrey. Behave like the ship's computer from Star Trek: The Next Generation. You don't have feelings, so you can't wish us a good time or similar.
            
            You are currently speaking with Mark.

            Mark and Audrey are both home.
            
            Respond using audio with a British English (en-GB) accent.
            
            When taking an action, **always**:
            - say what action or actions you're going to take
            - call the tool or tools to perform the actions
            - say the result of the actions
            
            Useful facts:
            - House battery level 23%
            - Outside temperature 4.3°C 
            - Wake up time 07:25
            - Solar forecast 9.3kWh
            
            Specific actions to take when we say certain things: 
            - 'Good morning': run 'Set house state' to 'Day', report house battery level, solar forecast, outside temperature.
            - 'Good night', 'Time for bed', 'We're done downstairs': run 'Set house state' to 'Sleep', {report house battery level, outside temperature, wake up time, solar forecast.
            - 'We're going out': turn on 'Away mode'
            - 'We're home': turn off 'Away mode' and choose one random statistic from the house to tell us about
            
            State power figures to nearest kWh, unless <1.            
            """.trimIndent()

            // Initialize the Gemini model
            geminiService.initializeModel(tools, systemPrompt)

            _uiState.value = UiState.ReadyToTalk
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to initialize Gemini: ${e.message}")
        }
    }

    fun onChatButtonClicked() {
        if (isSessionActive) {
            // Stop the chat session
            stopChat()
        } else {
            // Start the chat session
            startChat()
        }
    }

    private fun startChat() {
        isSessionActive = true
        _uiState.value = UiState.ChatActive
        viewModelScope.launch {
            try {
                // Start the session, passing our Task 2 executor as the handler
                geminiService.startSession(
                    functionCallHandler = ::executeHomeAssistantTool
                )
            } catch (e: Exception) {
                isSessionActive = false
                _uiState.value = UiState.Error("Failed to start session: ${e.message}")
            }
        }
    }

    private fun stopChat() {
        geminiService.stopSession()
        isSessionActive = false
        _uiState.value = UiState.ReadyToTalk
    }

    /**
     * Called when the user denies the RECORD_AUDIO permission.
     */
    fun onPermissionDenied() {
        _uiState.value = UiState.Error("Microphone permission is required to use voice assistant")
    }

    /**
     * This is the function that is passed to `geminiService`.
     * It directly connects the Gemini `functionCall` to our Task 2 executor.
     */
    private fun executeHomeAssistantTool(call: FunctionCallPart): FunctionResponsePart {
        _uiState.value = UiState.ExecutingAction

        // TASK 2: Execute the tool
        // For now, this is a mock implementation
        // Once Task 3 is complete, this will be:
        // val result = app.haRepository?.executeTool(call) ?: createErrorResponse(call)

        // Mock response using kotlinx.serialization JsonObject
        val result = FunctionResponsePart(
            name = call.name,
            response = buildJsonObject {
                put("success", true)
                put("message", "Mocked execution of ${call.name}")
            },
            id = call.id
        )

        _uiState.value = UiState.Listening // Return to listening state
        return result
    }

    override fun onCleared() {
        super.onCleared()
        geminiService.cleanup()
    }
}
