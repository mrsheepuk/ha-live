package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.SystemPromptConfig
import uk.co.mrsheep.halive.services.GeminiService
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Define the different states our UI can be in
sealed class UiState {
    object Loading : UiState()
    object FirebaseConfigNeeded : UiState()  // Need google-services.json
    object HAConfigNeeded : UiState()        // Need HA URL + token
    object ReadyToTalk : UiState()           // Everything initialized, ready to start chat
    object Initializing : UiState()          // Initializing Gemini model when starting chat
    object ChatActive : UiState()            // Chat session is active (listening or executing)
    data class ExecutingAction(val tool: String) : UiState()       // Executing a Home Assistant action
    data class Error(val message: String) : UiState()
}

// Represents a tool call log entry
data class ToolCallLog(
    val timestamp: String,
    val toolName: String,
    val parameters: String,
    val success: Boolean,
    val result: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    var currentProfileId: String = ""
    val profiles = ProfileManager.profiles // Expose for UI

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val _toolLogs = MutableStateFlow<List<ToolCallLog>>(emptyList())
    val toolLogs: StateFlow<List<ToolCallLog>> = _toolLogs

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt

    private val app = application as HAGeminiApp
    private val geminiService = GeminiService()

    // Track whether a chat session is currently active
    private var isSessionActive = false

    init {
        // Load the active profile
        val activeProfile = ProfileManager.getLastUsedOrDefaultProfile()
        if (activeProfile != null) {
            currentProfileId = activeProfile.id
            _systemPrompt.value = activeProfile.getCombinedPrompt()
        }

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

            // Step 3: Initialize MCP connection (Gemini will be initialized when user starts chat)
            try {
                val (haUrl, haToken) = HAConfig.loadConfig(getApplication())!!
                app.initializeHomeAssistant(haUrl, haToken)
                _uiState.value = UiState.ReadyToTalk
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect to HA: ${e.message}")
            }
        }
    }

    /**
     * Retry initialization after a failure.
     * Called by the UI when the user taps the retry button.
     */
    fun retryInitialization() {
        checkConfiguration()
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

                // Try to initialize MCP connection (Gemini will be initialized when user starts chat)
                app.initializeHomeAssistant(baseUrl, token)

                // Ready for user to start chat (Gemini initialization will happen on Start Chat)
                _uiState.value = UiState.ReadyToTalk
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect: ${e.message}")
                // Clear bad config
                HAConfig.clearConfig(getApplication())
            }
        }
    }

    /**
     * Initialize the Gemini model with tools from MCP.
     */
    private suspend fun initializeGemini() {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        try {
            // Fetch and transform tools from Home Assistant MCP server
            val tools = app.haRepository?.getTools() ?: emptyList()

            // Use the system prompt from the current profile
            val profile = ProfileManager.getProfileById(currentProfileId)

            // Determine the system prompt to use
            val systemPrompt = if (profile != null) {
                // Check if we should include live context
                if (profile.includeLiveContext) {
                    // Fetch live context from Home Assistant
                    val liveContextText = try {
                        // Create a FunctionCallPart for GetLiveContext
                        val getLiveContextCall = FunctionCallPart(
                            name = "GetLiveContext",
                            args = emptyMap()
                        )
                        val response = app.haRepository?.executeTool(getLiveContextCall)
                        response?.response?.let { jsonObj ->
                            // Navigate: outer "result" -> inner "result" -> text content
                            jsonObj.jsonObject["result"]
                                ?.jsonObject?.get("result")
                                ?.jsonPrimitive?.content ?: ""
                        } ?: ""
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch live context: ${e.message}")
                        // Log the error to the tool log
                        addToolLog(
                            ToolCallLog(
                                timestamp = timestamp,
                                toolName = "System Startup",
                                parameters = "GetLiveContext",
                                success = false,
                                result = "Failed to fetch live context: ${e.message}"
                            )
                        )
                        "" // Continue without live context on error
                    }

                    // Build combined prompt manually with live context in its own section
                    """<system_prompt>
${profile.systemPrompt}
</system_prompt>

<personality>
${profile.personality}
</personality>

<background_info>
${profile.backgroundInfo}
</background_info>

<initial_live_context>
$liveContextText
</initial_live_context>""".trimIndent()
                } else {
                    // Use profile's standard combined prompt
                    profile.getCombinedPrompt()
                }
            } else {
                SystemPromptConfig.getSystemPrompt(getApplication())
            }

            val model = profile?.model ?: SystemPromptConfig.DEFAULT_MODEL
            val voice = profile?.voice ?: SystemPromptConfig.DEFAULT_VOICE

            // Log the full generated system prompt to the tool log
            addToolLog(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "Model: $model, Voice: $voice",
                    success = true,
                    result = "Generated System Prompt:\n\n$systemPrompt"
                )
            )

            // Initialize the Gemini model
            geminiService.initializeModel(tools, systemPrompt, model, voice)
            // Note: UI state is managed by the caller (e.g., startChat)
        } catch (e: Exception) {
            // Log initialization error to tool log
            addToolLog(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "Gemini Initialization",
                    success = false,
                    result = "Failed to initialize Gemini: ${e.message}\n${e.stackTraceToString()}"
                )
            )
            // Throw exception for caller to handle
            throw e
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
        viewModelScope.launch {
            try {
                // Set state to Initializing while preparing the model
                _uiState.value = UiState.Initializing

                // Initialize Gemini with fresh tools and system prompt
                initializeGemini()

                // Only proceed to chat if initialization succeeded
                isSessionActive = true
                _uiState.value = UiState.ChatActive

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
        try {
            geminiService.stopSession()
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to stop session: ${e.message}")
        }
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
     * It directly connects the Gemini `functionCall` to the MCP executor.
     */
    private suspend fun executeHomeAssistantTool(call: FunctionCallPart): FunctionResponsePart {
        _uiState.value = UiState.ExecutingAction(call.name)

        // Prepare parameters string for logging
        val paramsString = call.args.toString()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        // Execute the tool via MCP
        val result = try {
            val response = app.haRepository?.executeTool(call) ?: FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("error", "Repository not initialized")
                },
                id = call.id
            )

            // Log successful call
            val isSuccess = !response.response.toString().contains("\"error\"")
            addToolLog(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = call.name,
                    parameters = paramsString,
                    success = isSuccess,
                    result = response.response.toString()
                )
            )

            response
        } catch (e: Exception) {
            val errorResponse = FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("error", "Failed to execute: ${e.message}")
                },
                id = call.id
            )

            // Log failed call
            addToolLog(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = call.name,
                    parameters = paramsString,
                    success = false,
                    result = "Exception: ${e.message}"
                )
            )

            errorResponse
        }

        _uiState.value = UiState.ChatActive // Return to normal chat-active state
        return result
    }

    private fun addToolLog(log: ToolCallLog) {
        _toolLogs.value = _toolLogs.value + log
    }

    /**
     * Update the system prompt (only allowed when chat is not active)
     */
    fun updateSystemPrompt(newPrompt: String) {
        if (!isSessionActive) {
            _systemPrompt.value = newPrompt
        }
    }

    /**
     * Save the current system prompt to config
     */
    fun saveSystemPrompt() {
        SystemPromptConfig.saveSystemPrompt(getApplication(), _systemPrompt.value)
    }

    /**
     * Reset system prompt to default
     */
    fun resetSystemPromptToDefault() {
        if (!isSessionActive) {
            SystemPromptConfig.resetToDefault(getApplication())
            _systemPrompt.value = SystemPromptConfig.getSystemPrompt(getApplication())
        }
    }

    /**
     * Switch to a different profile
     */
    fun switchProfile(profileId: String) {
        if (isSessionActive) {
            // Show toast in UI
            return
        }

        val profile = ProfileManager.getProfileById(profileId) ?: return
        currentProfileId = profileId
        _systemPrompt.value = profile.getCombinedPrompt()
        ProfileManager.markProfileAsUsed(profileId)

        // Model will be initialized fresh when user clicks Start Chat
    }

    /**
     * Public method to expose session state
     */
    fun isSessionActive(): Boolean = isSessionActive

    override fun onCleared() {
        super.onCleared()
        geminiService.cleanup()
    }
}

