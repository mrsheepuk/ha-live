package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ConversationServicePreference
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import uk.co.mrsheep.halive.core.ExecutionMode
import uk.co.mrsheep.halive.core.OptimizationLevel
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow<SettingsState>(
        SettingsState.Loaded("", "", "", 0, false, "", "", false, false, "", 0.5f)
    )
    val settingsState: StateFlow<SettingsState> = _settingsState

    private val app = application as HAGeminiApp

    // This should be set from MainActivity when launching SettingsActivity
    var isChatActive: Boolean = false

    fun loadSettings() {
        viewModelScope.launch {
            val (haUrl, haToken) = HAConfig.loadConfig(getApplication()) ?: Pair("Not configured", "")
            val projectId = FirebaseConfig.getProjectId(getApplication()) ?: "Not configured"
            val geminiKey = GeminiConfig.getApiKey(getApplication()) ?: "Not configured"
            val profileCount = ProfileManager.getAllProfiles().size

            val canChooseService = ConversationServicePreference.canChoose(getApplication())
            val preferredService = ConversationServicePreference.getPreferred(getApplication())
            val serviceDisplayName = when (preferredService) {
                ConversationServicePreference.PreferredService.GEMINI_DIRECT -> "Gemini Direct API"
                ConversationServicePreference.PreferredService.FIREBASE -> "Firebase SDK"
            }

            val wakeWordSettings = WakeWordConfig.getSettings(getApplication())
            val executionModeDisplay = when (wakeWordSettings.executionMode) {
                ExecutionMode.SEQUENTIAL -> "Sequential"
                ExecutionMode.PARALLEL -> "Parallel"
            }
            val optimizationLevelDisplay = when (wakeWordSettings.optimizationLevel) {
                OptimizationLevel.NO_OPT -> "None"
                OptimizationLevel.BASIC_OPT -> "Basic"
                OptimizationLevel.EXTENDED_OPT -> "Extended"
                OptimizationLevel.ALL_OPT -> "All"
            }
            val wakeWordDetails = "Threshold: %.2f | Threads: %d | %s | %s".format(
                wakeWordSettings.threshold,
                wakeWordSettings.threadCount,
                executionModeDisplay,
                optimizationLevelDisplay
            )

            _settingsState.value = SettingsState.Loaded(
                haUrl = haUrl,
                haToken = haToken,
                firebaseProjectId = projectId,
                profileCount = profileCount,
                isReadOnly = isChatActive,
                geminiApiKey = geminiKey,
                conversationService = serviceDisplayName,
                canChooseService = canChooseService,
                wakeWordEnabled = wakeWordSettings.enabled,
                wakeWordDetails = wakeWordDetails,
                wakeWordThreshold = wakeWordSettings.threshold
            )
        }
    }

    fun testHAConnection() {
        viewModelScope.launch {
            _settingsState.value = SettingsState.TestingConnection

            var testMcpClient: McpClientManager? = null
            try {
                val (url, token) = HAConfig.loadConfig(getApplication())
                    ?: throw Exception("HA not configured")

                // Store credentials in app for later use
                app.initializeHomeAssistant(url, token)

                // Create temporary MCP connection for testing
                testMcpClient = McpClientManager(url, token)
                testMcpClient.connect()

                // Try to fetch tools
                val tools = testMcpClient.getTools()

                if (tools.isNotEmpty()) {
                    _settingsState.value = SettingsState.ConnectionSuccess("Found ${tools.size} tools")
                } else {
                    _settingsState.value = SettingsState.ConnectionFailed("No tools found")
                }
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed(e.message ?: "Connection failed")
            } finally {
                // Clean up temporary test connection
                testMcpClient?.shutdown()
            }

            // Reload settings to restore buttons
            loadSettings()
        }
    }

    fun changeFirebaseConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)

                // Kill the app - user must restart
                exitProcess(0)
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to update Firebase config: ${e.message}")
                loadSettings()
            }
        }
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            try {
                if (apiKey.isBlank()) {
                    _settingsState.value = SettingsState.ConnectionFailed("Gemini API key cannot be blank")
                    return@launch
                }

                GeminiConfig.saveApiKey(getApplication(), apiKey)
                loadSettings()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to save Gemini API key: ${e.message}")
                loadSettings()
            }
        }
    }

    fun clearGeminiApiKey() {
        viewModelScope.launch {
            try {
                GeminiConfig.clearConfig(getApplication())
                loadSettings()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to clear Gemini API key: ${e.message}")
                loadSettings()
            }
        }
    }

    fun switchConversationService() {
        viewModelScope.launch {
            try {
                val current = ConversationServicePreference.getPreferred(getApplication())
                val newPreference = when (current) {
                    ConversationServicePreference.PreferredService.GEMINI_DIRECT ->
                        ConversationServicePreference.PreferredService.FIREBASE
                    ConversationServicePreference.PreferredService.FIREBASE ->
                        ConversationServicePreference.PreferredService.GEMINI_DIRECT
                }

                ConversationServicePreference.setPreferred(getApplication(), newPreference)
                loadSettings()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to switch service: ${e.message}")
                loadSettings()
            }
        }
    }

    fun saveWakeWordSettings(settings: WakeWordSettings) {
        viewModelScope.launch {
            try {
                WakeWordConfig.saveSettings(getApplication(), settings)
                loadSettings()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to save wake word settings: ${e.message}")
                loadSettings()
            }
        }
    }
}
