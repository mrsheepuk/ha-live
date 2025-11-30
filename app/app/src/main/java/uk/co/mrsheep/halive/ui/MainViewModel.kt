package uk.co.mrsheep.halive.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.services.LiveSessionService
import uk.co.mrsheep.halive.services.WakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.core.QuickMessage
import uk.co.mrsheep.halive.core.QuickMessageConfig
import uk.co.mrsheep.halive.core.TranscriptionEntry

// Define the different states our UI can be in
sealed class UiState {
    object Loading : UiState()
    object ProviderConfigNeeded : UiState()  // Need Gemini API key configured
    object HAConfigNeeded : UiState()        // Need HA URL + token
    object ReadyToTalk : UiState()           // Everything initialized, ready to start chat
    object Initializing : UiState()          // Initializing Gemini model when starting chat
    object ChatActive : UiState()            // Chat session is active (listening or executing)
    data class ExecutingAction(val tool: String) : UiState()       // Executing a Home Assistant action
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    var currentProfileId: String = ""
    val profiles = ProfileManager.profiles // Expose for UI

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    // State flows from LiveSessionService (mirrored here for UI)
    private val _toolLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val toolLogs: StateFlow<List<LogEntry>> = _toolLogs

    private val _transcriptionLogs = MutableStateFlow<List<TranscriptionEntry>>(emptyList())
    val transcriptionLogs: StateFlow<List<TranscriptionEntry>> = _transcriptionLogs

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Auto-start state
    private val _shouldAttemptAutoStart = MutableStateFlow(false)
    val shouldAttemptAutoStart: StateFlow<Boolean> = _shouldAttemptAutoStart

    // Wake word enabled state
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled

    // Track if user has ever started a chat in this session (for layout transition)
    private val _hasEverChatted = MutableStateFlow(false)
    val hasEverChatted: StateFlow<Boolean> = _hasEverChatted

    // Track if this is the first initialization (survives activity recreation, not process death)
    private var hasCheckedAutoStart = false

    private val app = application as HAGeminiApp

    // LiveSessionService and binding
    private var liveSessionService: LiveSessionService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LiveSessionService.LocalBinder
            liveSessionService = binder.getService()
            serviceBound = true

            // Start collecting flows from the service
            collectServiceFlows()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            liveSessionService = null
            serviceBound = false
        }
    }

    // Wake word service for foreground detection
    private val wakeWordService = WakeWordService(application) {
        // Callback invoked when wake word is detected (already on Main thread)
        liveSessionService?.let { service ->
            if (!service.isSessionActive.value) {
                Log.d(TAG, "Wake word detected! Auto-starting chat...")
                onChatButtonClicked()
            }
        } ?: run {
            Log.d(TAG, "Wake word detected! Auto-starting chat...")
            onChatButtonClicked()
        }
    }

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

            // Step 1: Check if Gemini API is configured
            if (!GeminiConfig.isConfigured(getApplication())) {
                _uiState.value = UiState.ProviderConfigNeeded
                return@launch
            }

            // Step 2: Check Home Assistant OAuth authentication
            if (!HAConfig.isConfigured(getApplication())) {
                _uiState.value = UiState.HAConfigNeeded
                return@launch
            }

            // Step 3: Initialize API client using OAuth token manager (MCP connection created per-session)
            try {
                val tokenManager = app.getTokenManager()
                val haUrl = HAConfig.getHaUrl(getApplication())

                if (tokenManager == null || haUrl == null) {
                    _uiState.value = UiState.HAConfigNeeded
                    return@launch
                }

                app.initializeHomeAssistantWithOAuth(haUrl, tokenManager)
                _uiState.value = UiState.ReadyToTalk

                // Check if we should auto-start (only on first initialization)
                checkAutoStart()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to initialize HA: ${e.message}")
            }
        }
    }

    /**
     * Collects flows from the LiveSessionService and mirrors them in local state flows.
     */
    private fun collectServiceFlows() {
        liveSessionService?.let { service ->
            // Collect transcription logs
            viewModelScope.launch {
                service.transcriptionLogs.collect { logs ->
                    _transcriptionLogs.value = logs
                }
            }

            // Collect tool logs
            viewModelScope.launch {
                service.toolLogs.collect { logs ->
                    _toolLogs.value = logs
                }
            }

            // Collect audio level
            viewModelScope.launch {
                service.audioLevel.collect { level ->
                    _audioLevel.value = level
                }
            }

            // Collect connection state
            viewModelScope.launch {
                service.connectionState.collect { state ->
                    _uiState.value = state
                }
            }

            // Collect session active state for wake word coordination
            viewModelScope.launch {
                var wasSessionActive = false
                service.isSessionActive.collect { isActive ->
                    if (!isActive && wasSessionActive) {
                        // Session ended (transitioned from active to inactive)
                        // Handle audio helper handover
                        val returnedHelper = liveSessionService?.yieldAudioHelper()

                        if (returnedHelper != null) {
                            Log.d(TAG, "Got AudioHelper back from session, resuming wake word")
                            wakeWordService.resumeWith(returnedHelper)
                        } else {
                            Log.d(TAG, "No AudioHelper from session, starting wake word fresh")
                            startWakeWordListening()
                        }
                    } else if (isActive) {
                        // Session started, stop wake word
                        wakeWordService.stopListening()
                    }
                    // Update tracking state
                    wasSessionActive = isActive
                }
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
        val isActive = liveSessionService?.isSessionActive?.value ?: false
        if (!isActive) {
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
     * Refresh configuration after OAuth login from onboarding.
     * Called when returning from OnboardingActivity after successful OAuth.
     */
    fun refreshConfigurationAfterOAuth() {
        checkConfiguration()
    }

    fun onChatButtonClicked() {
        val isActive = liveSessionService?.isSessionActive?.value ?: false
        if (isActive) {
            // Stop the chat session
            stopChat()
        } else {
            // Start the chat session
            startChat()
        }
    }

    private fun startChat() {
        // Get the profile
        val profile = ProfileManager.getProfileById(currentProfileId)
        if (profile == null) {
            _uiState.value = UiState.Error("No profile set, choose a profile before starting")
            return
        }

        viewModelScope.launch {
            try {
                // Yield AudioHelper from wake word service for seamless handover
                val audioHelper = wakeWordService.yieldAudioHelper()
                if (audioHelper != null) {
                    Log.d(TAG, "Got AudioHelper from wake word service for handover")
                } else {
                    Log.d(TAG, "No AudioHelper from wake word service (not listening or unavailable)")
                }

                // Start the foreground service
                val serviceIntent = Intent(getApplication(), LiveSessionService::class.java)
                getApplication<Application>().startForegroundService(serviceIntent)

                // Bind to the service
                val bindIntent = Intent(getApplication(), LiveSessionService::class.java)
                getApplication<Application>().bindService(
                    bindIntent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )

                // Wait for service to be bound
                var attempts = 0
                while (!serviceBound && attempts < 50) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }

                if (!serviceBound) {
                    _uiState.value = UiState.Error("Failed to bind to session service")
                    return@launch
                }

                // Start the session in the service with audio helper for handover
                liveSessionService?.startSession(profile, externalAudioHelper = audioHelper)

            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to start session: ${e.message}")
                // Restart wake word listening on failure
                startWakeWordListening()
            }
        }
    }

    private fun stopChat() {
        liveSessionService?.stopSession()
        // Service will stop itself and update flows
        // Unbind from service
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
                serviceBound = false
                liveSessionService = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service: ${e.message}")
            }
        }
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

    /**
     * Mark that the user has activated chat in this session.
     * Called to trigger the one-time layout transition from centered to top-aligned.
     */
    fun markHasChatted() {
        _hasEverChatted.value = true
    }

    /**
     * Reset the chat state flag.
     * Called when clearing transcription to allow the next chat to animate the layout transition.
     */
    fun resetChatState() {
        _hasEverChatted.value = false
    }

    /**
     * Clear all transcription logs
     */
    fun clearTranscriptionLogs() {
        liveSessionService?.clearTranscriptionLogs()
        _transcriptionLogs.value = emptyList()
    }

    /**
     * Switch to a different profile
     */
    fun switchProfile(profileId: String) {
        if (isSessionActive()) {
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
    fun isSessionActive(): Boolean = liveSessionService?.isSessionActive?.value ?: false

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

    /**
     * Reloads wake word settings from SharedPreferences.
     * Called when returning from SettingsActivity to apply configuration changes.
     */
    fun reloadWakeWordSettings() {
        wakeWordService.reloadSettings()
    }

    /**
     * Send a quick message to the conversation service.
     * Similar to initial message to agent but triggered by user clicking a quick message chip.
     */
    fun sendQuickMessage(message: String) {
        if (!isSessionActive()) {
            Log.d(TAG, "Attempted to send quick message but session is not active")
            return
        }

        liveSessionService?.sendText(message)
    }

    /**
     * Get all enabled quick messages from configuration.
     */
    fun getEnabledQuickMessages(): List<QuickMessage> {
        return QuickMessageConfig(getApplication()).getEnabledQuickMessages()
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind from service if still bound
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service in onCleared: ${e.message}")
            }
        }
        wakeWordService.destroy()
    }
}

