package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import uk.co.mrsheep.halive.core.ExecutionMode
import uk.co.mrsheep.halive.core.OptimizationLevel
import uk.co.mrsheep.halive.core.QuickMessage
import uk.co.mrsheep.halive.core.QuickMessageConfig
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow<SettingsState>(
        SettingsState.Loaded("", "", 0, false, "", "", false, false, "", 0.5f)
    )
    val settingsState: StateFlow<SettingsState> = _settingsState

    private val _quickMessages = MutableStateFlow<List<QuickMessage>>(emptyList())
    val quickMessages: StateFlow<List<QuickMessage>> = _quickMessages

    private val app = application as HAGeminiApp

    // This should be set from MainActivity when launching SettingsActivity
    var isChatActive: Boolean = false

    fun loadSettings() {
        viewModelScope.launch {
            val (haUrl, haToken) = HAConfig.loadConfig(getApplication()) ?: Pair("Not configured", "")
            val geminiKey = GeminiConfig.getApiKey(getApplication()) ?: "Not configured"
            val profileCount = ProfileManager.getAllProfiles().size

            val canChooseService = false
            val serviceDisplayName = "Gemini Direct API"

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

    fun loadQuickMessages() {
        viewModelScope.launch {
            try {
                val config = QuickMessageConfig(getApplication())
                _quickMessages.value = config.getQuickMessages()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to load quick messages: ${e.message}")
            }
        }
    }

    fun addQuickMessage(qm: QuickMessage) {
        viewModelScope.launch {
            try {
                val config = QuickMessageConfig(getApplication())
                config.addQuickMessage(qm)
                loadQuickMessages()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to add quick message: ${e.message}")
            }
        }
    }

    fun updateQuickMessage(qm: QuickMessage) {
        viewModelScope.launch {
            try {
                val config = QuickMessageConfig(getApplication())
                config.updateQuickMessage(qm)
                loadQuickMessages()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to update quick message: ${e.message}")
            }
        }
    }

    fun deleteQuickMessage(id: String) {
        viewModelScope.launch {
            try {
                val config = QuickMessageConfig(getApplication())
                config.deleteQuickMessage(id)
                loadQuickMessages()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to delete quick message: ${e.message}")
            }
        }
    }

    fun toggleQuickMessageEnabled(id: String) {
        viewModelScope.launch {
            try {
                val config = QuickMessageConfig(getApplication())
                val messages = config.getQuickMessages()
                val message = messages.find { it.id == id }
                if (message != null) {
                    val updatedMessage = message.copy(enabled = !message.enabled)
                    config.updateQuickMessage(updatedMessage)
                    loadQuickMessages()
                }
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to toggle quick message: ${e.message}")
            }
        }
    }
}
