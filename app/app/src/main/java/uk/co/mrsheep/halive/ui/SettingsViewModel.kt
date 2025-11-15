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
import uk.co.mrsheep.halive.services.McpClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow<SettingsState>(
        SettingsState.Loaded("", "", "", 0, false, "")
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

            _settingsState.value = SettingsState.Loaded(
                haUrl = haUrl,
                haToken = haToken,
                firebaseProjectId = projectId,
                profileCount = profileCount,
                isReadOnly = isChatActive,
                geminiApiKey = geminiKey
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
                testMcpClient.initialize()

                // Try to fetch tools
                val tools = testMcpClient.getTools()

                if (tools.tools.isNotEmpty()) {
                    _settingsState.value = SettingsState.ConnectionSuccess("Found ${tools.tools.size} tools")
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
                _settingsState.value = SettingsState.ConnectionSuccess("Gemini API key saved successfully")
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
                _settingsState.value = SettingsState.ConnectionSuccess("Gemini API key cleared successfully")
                loadSettings()
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed to clear Gemini API key: ${e.message}")
                loadSettings()
            }
        }
    }
}
