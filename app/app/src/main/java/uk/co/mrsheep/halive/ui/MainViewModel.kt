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
import uk.co.mrsheep.halive.services.BeepHelper
import uk.co.mrsheep.halive.services.GeminiMCPToolExecutor
import uk.co.mrsheep.halive.services.GeminiService
import uk.co.mrsheep.halive.services.GeminiSessionPreparer
import uk.co.mrsheep.halive.services.McpClientManager
import uk.co.mrsheep.halive.services.WakeWordService
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Transcription
import kotlinx.coroutines.delay
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

    // Auto-start state
    private val _shouldAttemptAutoStart = MutableStateFlow(false)
    val shouldAttemptAutoStart: StateFlow<Boolean> = _shouldAttemptAutoStart

    // Track if this is the first initialization (survives activity recreation, not process death)
    private var hasCheckedAutoStart = false

    private val app = application as HAGeminiApp
    private val geminiService = GeminiService()
    private lateinit var sessionPreparer: GeminiSessionPreparer

    // Wake word service for foreground detection
    private val wakeWordService = WakeWordService(application) {
        // Callback invoked when wake word is detected (already on Main thread)
        if (!isSessionActive) {
            Log.d(TAG, "Wake word detected! Auto-starting chat...")
            onChatButtonClicked()
        }
    }

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

            // Step 3: Store HA credentials and initialize API client (MCP connection created per-session)
            try {
                val (haUrl, haToken) = HAConfig.loadConfig(getApplication())!!
                app.initializeHomeAssistant(haUrl, haToken)
                _uiState.value = UiState.ReadyToTalk

                // Check if we should auto-start (only on first initialization)
                checkAutoStart()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to initialize HA config: ${e.message}")
            }
        }
    }

    /**
     * Start wake word listening if not in active chat.
     * Permission is checked by MainActivity before calling lifecycle methods.
     */
    private fun startWakeWordListening() {
        if (!isSessionActive) {
            wakeWordService.startListening()
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
     * Consume the auto-start intent flag.
     * Called by the UI after acting on the shouldAttemptAutoStart flag.
     */
    fun consumeAutoStartIntent() {
        _shouldAttemptAutoStart.value = false
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
        try {
            val profile = ProfileManager.getProfileById(currentProfileId)
            val defaultPrompt = SystemPromptConfig.getSystemPrompt(getApplication())
            sessionPreparer.prepareAndInitialize(profile, geminiService, defaultPrompt)
        } catch (e: Exception) {
            // Error is already logged by preparer
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
                // Stop wake word listening (release microphone)
                wakeWordService.stopListening()

                // Set state to Initializing while preparing the model
                _uiState.value = UiState.Initializing

                // Create fresh MCP connection for this session
                app.mcpClient = McpClientManager(app.haUrl!!, app.haToken!!)
                app.mcpClient?.initialize()
                app.toolExecutor = GeminiMCPToolExecutor(app.mcpClient!!)

                // Now sessionPreparer needs to be recreated with the new mcpClient
                sessionPreparer = GeminiSessionPreparer(
                    mcpClient = app.mcpClient!!,
                    haApiClient = app.haApiClient!!,
                    toolExecutor = app.toolExecutor!!,
                    onLogEntry = ::addToolLog
                )

                // Initialize Gemini with fresh tools and system prompt
                initializeGemini()

                // Only proceed to chat if initialization succeeded
                isSessionActive = true
                _uiState.value = UiState.ChatActive

                // Get the active profile for transcription settings and initial message
                val profile = ProfileManager.getProfileById(currentProfileId)

                // Create transcription handler based on profile setting
                val transcriptHandler: ((Transcription?, Transcription?) -> Unit)? =
                    if (profile?.enableTranscription == true) {
                        { userTranscription, modelTranscription ->
                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

                            // Log user transcription (only if not null/empty)
                            userTranscription?.text?.takeIf { it.isNotBlank() }?.let { text ->
                                addToolLog(
                                    ToolCallLog(
                                        timestamp = timestamp,
                                        toolName = "🎤 User",
                                        parameters = "",
                                        success = true,
                                        result = text
                                    )
                                )
                            }

                            // Log model transcription (only if not null/empty)
                            modelTranscription?.text?.takeIf { it.isNotBlank() }?.let { text ->
                                addToolLog(
                                    ToolCallLog(
                                        timestamp = timestamp,
                                        toolName = "🔊 Model",
                                        parameters = "",
                                        success = true,
                                        result = text
                                    )
                                )
                            }
                        }
                    } else null

                // Start the session, passing our Task 2 executor as the handler
                geminiService.startSession(
                    functionCallHandler = ::executeHomeAssistantTool,
                    transcriptHandler = transcriptHandler
                )

                // Play ready beep to indicate session is active
                BeepHelper.playReadyBeep(getApplication())

                // Send initial message to agent if configured
                profile?.initialMessageToAgent?.let { initialText ->
                    if (initialText.isNotBlank()) {
                        try {
                            geminiService.sendTextMessage(initialText)

                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())

                            // Log it to tool logs
                            addToolLog(
                                ToolCallLog(
                                    timestamp = timestamp,
                                    toolName = "System Startup",
                                    parameters = "Initial Message to Agent",
                                    success = true,
                                    result = "Sent: $initialText"
                                )
                            )
                        } catch (e: Exception) {
                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())

                            addToolLog(
                                ToolCallLog(
                                    timestamp = timestamp,
                                    toolName = "System Startup",
                                    parameters = "Initial Message to Agent",
                                    success = false,
                                    result = "Failed to send: ${e.message}"
                                )
                            )
                            // Don't fail the session, just log it
                        }
                    }
                }
            } catch (e: Exception) {
                // Clean up MCP connection if initialization failed
                app.mcpClient?.shutdown()
                app.mcpClient = null
                app.toolExecutor = null

                isSessionActive = false
                _uiState.value = UiState.Error("Failed to start session: ${e.message}")

                // Restart wake word listening on failure (reacquire microphone)
                startWakeWordListening()
            }
        }
    }

    private fun stopChat() {
        BeepHelper.playEndBeep(getApplication())
        try {
            geminiService.stopSession()
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to stop session: ${e.message}")
        }

        // Clean up MCP connection
        app.mcpClient?.shutdown()
        app.mcpClient = null
        app.toolExecutor = null

        isSessionActive = false
        _uiState.value = UiState.ReadyToTalk

        // Restart wake word listening (reacquire microphone)
        startWakeWordListening()
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
        // Intercept EndConversation tool
        if (call.name == "EndConversation") {
            return handleEndConversation(call)
        }

        _uiState.value = UiState.ExecutingAction(call.name)

        // Prepare parameters string for logging
        val paramsString = call.args.toString()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        // Execute the tool via MCP
        val result = try {
            val response = app.toolExecutor?.executeTool(call) ?: FunctionResponsePart(
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

    private suspend fun handleEndConversation(call: FunctionCallPart): FunctionResponsePart {
        // Extract reason parameter
        val reason = call.args["reason"]?.toString() ?: "Natural conclusion"

        // Create timestamp
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        // Log the tool call
        addToolLog(
            ToolCallLog(
                timestamp = timestamp,
                toolName = call.name,
                parameters = call.args.toString(),
                success = true,
                result = "Conversation ended: $reason"
            )
        )

        // Create the response first
        val response = FunctionResponsePart(
            name = call.name,
            response = buildJsonObject {
                put("success", true)
                put("message", "Conversation ended: $reason")
            },
            id = call.id
        )

        // Schedule stop after minimal delay for SDK to process the response
        viewModelScope.launch {
            delay(300) // Allow Firebase SDK to send the function response
            stopChat()
        }

        // Return success response immediately
        return response
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

    /**
     * Check if auto-start chat is enabled and set the flag.
     * Only checks once per ViewModel instance (survives activity recreation but not process death).
     */
    private fun checkAutoStart() {
        // Only check once per ViewModel instance (app cold start)
        if (hasCheckedAutoStart) return
        hasCheckedAutoStart = true

        // Check if active profile has auto-start enabled
        val profile = ProfileManager.getProfileById(currentProfileId)
        if (profile?.autoStartChat == true) {
            _shouldAttemptAutoStart.value = true
        }
    }

    /**
     * Called when MainActivity enters the foreground (onResume).
     * Starts wake word listening if in ready state and permission is granted.
     */
    fun onActivityResume() {
        if (_uiState.value == UiState.ReadyToTalk) {
            startWakeWordListening()
        }
    }

    /**
     * Called when MainActivity leaves the foreground (onPause).
     * Stops wake word listening to enforce foreground-only behavior.
     */
    fun onActivityPause() {
        wakeWordService.stopListening()
    }

    override fun onCleared() {
        super.onCleared()
        geminiService.cleanup()
        wakeWordService.destroy()
    }
}

