package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.SystemPromptConfig
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.services.BeepHelper
import uk.co.mrsheep.halive.services.SessionPreparer
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import uk.co.mrsheep.halive.services.conversation.ConversationService
import uk.co.mrsheep.halive.services.conversation.ConversationServiceFactory
import uk.co.mrsheep.halive.services.WakeWordService
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.core.TranscriptionEntry
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
import uk.co.mrsheep.halive.services.AppToolExecutor
import uk.co.mrsheep.halive.services.LocalTool
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.mcp.McpInputSchema
import uk.co.mrsheep.halive.services.mcp.McpProperty
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult
import uk.co.mrsheep.halive.services.mcp.ToolContent
import kotlin.String
import uk.co.mrsheep.halive.core.DummyToolsConfig
import uk.co.mrsheep.halive.services.MockToolExecutor

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

class MainViewModel(application: Application) : AndroidViewModel(application), AppLogger {

    companion object {
        private const val TAG = "MainViewModel"
    }

    var currentProfileId: String = ""
    val profiles = ProfileManager.profiles // Expose for UI

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val _toolLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val toolLogs: StateFlow<List<LogEntry>> = _toolLogs

    private val _transcriptionLogs = MutableStateFlow<List<TranscriptionEntry>>(emptyList())
    val transcriptionLogs: StateFlow<List<TranscriptionEntry>> = _transcriptionLogs

    // Auto-start state
    private val _shouldAttemptAutoStart = MutableStateFlow(false)
    val shouldAttemptAutoStart: StateFlow<Boolean> = _shouldAttemptAutoStart

    // Wake word enabled state
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled

    // Transcription expanded state
    private val _transcriptionExpanded = MutableStateFlow(false)
    val transcriptionExpanded: StateFlow<Boolean> = _transcriptionExpanded

    // Track if this is the first initialization (survives activity recreation, not process death)
    private var hasCheckedAutoStart = false

    private val app = application as HAGeminiApp

    private var toolExecutor: ToolExecutor? = null
    private var mcpClient: McpClientManager? = null
    private lateinit var conversationService: ConversationService

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
        val activeProfile = ProfileManager.getActiveOrFirstProfile()
        if (activeProfile != null) {
            currentProfileId = activeProfile.id
        }

        // Load wake word preference
        _wakeWordEnabled.value = WakeWordConfig.isEnabled(getApplication())

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
        if (!_wakeWordEnabled.value) {
            Log.d(TAG, "Wake word disabled, skipping")
            return
        }
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

    fun onChatButtonClicked() {
        if (isSessionActive) {
            // Stop the chat session
            stopChat()
        } else {
            // Start the chat session
            startChat()
        }
    }

    private fun setUIState(uiState: UiState): Unit {
        _uiState.value = uiState
    }

    private fun startChat() {
        // Initialize Gemini with fresh tools and system prompt
        val profile = ProfileManager.getProfileById(currentProfileId)
        if (profile == null) {
            _uiState.value = UiState.Error("No profile set, choose a profile before starting")
            return
        }
        viewModelScope.launch {
            try {
                // Stop wake word listening (release microphone)
                wakeWordService.stopListening()

                // Set state to Initializing while preparing the model
                _uiState.value = UiState.Initializing

                conversationService = ConversationServiceFactory.create(getApplication())

                // Create fresh MCP connection for this session
                val mcp = McpClientManager(app.haUrl!!, app.haToken!!)
                mcp.connect()
                mcpClient = mcp

                val localTools = getLocalTools()

                // Determine base executor (wrap MCP with MockToolExecutor if dummy tools enabled)
                val baseExecutor: ToolExecutor = if (DummyToolsConfig.isEnabled(getApplication())) {
                    // Build passthrough tools list: GetDateTime, GetLiveContext, and all local tools
                    val passthroughTools = buildList {
                        add("GetDateTime")
                        add("GetLiveContext")
                        addAll(localTools.keys)  // All local tool names (e.g., EndConversation)
                    }
                    MockToolExecutor(
                        realExecutor = mcp,
                        passthroughTools = passthroughTools
                    )
                } else {
                    mcp
                }

                // Wrap the base executor (which may be mocked) with AppToolExecutor
                toolExecutor = AppToolExecutor(
                    toolExecutor = baseExecutor,
                    logger = this@MainViewModel,
                    setUIState = { uiState: UiState -> _uiState.value = uiState },
                    localTools = localTools,
                )

                // Now sessionPreparer needs to be recreated with the new mcpClient
                val sessionPreparer = SessionPreparer(
                    toolExecutor = toolExecutor!!,
                    haApiClient = app.haApiClient!!,
                    logger = this@MainViewModel,
                    localTools = localTools.keys,
                )

                sessionPreparer.prepareAndInitialize(profile, conversationService)

                isSessionActive = true
                _uiState.value = UiState.ChatActive
                conversationService.startSession()
                // Play ready beep to indicate session is active
                BeepHelper.playReadyBeep(getApplication())

                // Send initial message to agent if configured
                profile.initialMessageToAgent.let { initialText ->
                    if (initialText.isNotBlank()) {
                        try {
                            conversationService.sendText(initialText)

                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())

                            // Log it to tool logs
                            addLogEntry(
                                LogEntry(
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

                            addLogEntry(
                                LogEntry(
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
                // Log the full exception details to tool log for debugging
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())

                addLogEntry(
                    LogEntry(
                        timestamp = timestamp,
                        toolName = "Session Start Error",
                        parameters = "Failed to start conversation session",
                        success = false,
                        result = "Exception: ${e.javaClass.simpleName}\n" +
                                "Message: ${e.message}\n\n" +
                                "Stack trace:\n${e.stackTraceToString()}"
                    )
                )

                // Clean up MCP connection if initialization failed
                toolExecutor = null
                mcpClient?.shutdown()
                mcpClient = null


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
            conversationService.stopSession()
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to stop session: ${e.message}")
        }

        // Clean up MCP connection
        toolExecutor = null
        mcpClient?.shutdown()
        mcpClient = null

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
     * Toggle wake word detection on/off.
     * Called by UI when user toggles the switch.
     */
    fun toggleWakeWord(enabled: Boolean) {
        WakeWordConfig.setEnabled(getApplication(), enabled)
        _wakeWordEnabled.value = enabled

        if (!enabled) {
            // User turned it OFF -> stop listening immediately
            wakeWordService.stopListening()
        } else if (_uiState.value == UiState.ReadyToTalk) {
            // User turned it ON and we're ready -> start listening
            startWakeWordListening()
        }
    }

    fun toggleTranscriptionExpanded() {
        _transcriptionExpanded.value = !_transcriptionExpanded.value
    }

    override fun addLogEntry(log: LogEntry) {
        _toolLogs.value += log
    }

    override fun addModelTranscription(chunk: String, isThought: Boolean) {
        _transcriptionLogs.value += TranscriptionEntry(
            spokenBy = if (isThought) TranscriptionSpeaker.MODELTHOUGHT else TranscriptionSpeaker.MODEL,
            chunk = chunk,
        )
    }

    override fun addUserTranscription(chunk: String) {
        _transcriptionLogs.value += TranscriptionEntry(
            spokenBy = TranscriptionSpeaker.USER,
            chunk = chunk,
        )
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
        ProfileManager.setActiveProfile(profileId)
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
     * Also reloads the active profile in case it was changed in ProfileManagementActivity.
     */
    fun onActivityResume() {
        // Reload active profile (in case it was changed in ProfileManagementActivity)
        val activeProfile = ProfileManager.getActiveOrFirstProfile()
        if (activeProfile != null && activeProfile.id != currentProfileId) {
            currentProfileId = activeProfile.id
        }

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
        conversationService.cleanup()
        wakeWordService.destroy()
    }

    private fun getLocalTools(): Map<String, LocalTool> {
        return mapOf(
            "EndConversation" to LocalTool(
                definition = McpTool(
                    name = "EndConversation",
                    description = """
                    Immediately ends the conversation. 
                    
                    Use when the conversation has come to its natural end, for example, if the user says 'thanks' with no obvious follow up. 
                    
                    Wish the user goodbye before calling this tool so they know the conversation is finished.
                    """.trimIndent(),
                    inputSchema = McpInputSchema(
                        type = "object",
                        properties = mapOf("reason" to McpProperty(
                            description = "Brief reason why this conversation has come to an end"
                        ))
                    ),
                ),
                execute = { name: String, arguments: Map<String, JsonElement> ->
                    val reason = arguments["reason"]?.jsonPrimitive?.content ?: "Natural conclusion"

                    viewModelScope.launch {
                        // Allow conversation service to send the function response
                        delay(300)
                        stopChat()
                    }

                    // Return success response immediately
                    ToolCallResult(
                        isError = false,
                        content = listOf(ToolContent(type = "text", text = "Conversation ended: $reason"))
                    )
                }
            )
        )
    }
}

