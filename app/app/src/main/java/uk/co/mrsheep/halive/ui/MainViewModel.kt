package uk.co.mrsheep.halive.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.annotation.RequiresPermission
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
    object ReadyToTalk : UiState()           // Everything initialized
    object Listening : UiState()
    object ExecutingAction : UiState()       // Executing a Home Assistant action
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val app = application as HAGeminiApp
    private val geminiService = GeminiService()

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

            val systemPrompt = "You are a helpful home assistant. You can control devices and answer questions about the user's home."

            // Initialize the Gemini model
            geminiService.initializeModel(tools, systemPrompt)

            _uiState.value = UiState.ReadyToTalk
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to initialize Gemini: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onTalkButtonPressed() {
        _uiState.value = UiState.Listening
        viewModelScope.launch {
            try {
                // Start the session, passing our Task 2 executor as the handler
                geminiService.startSession(
                    functionCallHandler = ::executeHomeAssistantTool
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to start session: ${e.message}")
            }
        }
    }

    fun onTalkButtonReleased() {
        geminiService.stopSession()
        _uiState.value = UiState.ReadyToTalk
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
