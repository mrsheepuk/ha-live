package uk.co.mrsheep.halive.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import uk.co.mrsheep.halive.core.ExecutionMode
import uk.co.mrsheep.halive.core.OptimizationLevel
import uk.co.mrsheep.halive.core.QuickMessage
import uk.co.mrsheep.halive.core.QuickMessageConfig
import uk.co.mrsheep.halive.core.OAuthConfig
import uk.co.mrsheep.halive.core.SecureTokenStorage
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.core.HomeAssistantAuth
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow<SettingsState>(
        SettingsState.Loaded("", "", 0, false, "", "", false, false, "", 0.5f, false, false, false)
    )
    val settingsState: StateFlow<SettingsState> = _settingsState

    private val _quickMessages = MutableStateFlow<List<QuickMessage>>(emptyList())
    val quickMessages: StateFlow<List<QuickMessage>> = _quickMessages

    private val app = application as HAGeminiApp
    private val secureTokenStorage = SecureTokenStorage(application)
    private val haAuth = HomeAssistantAuth(application)

    // This should be set from MainActivity when launching SettingsActivity
    var isChatActive: Boolean = false

    // OAuth state tracking
    private var pendingOAuthState: String? = null
    private var pendingOAuthUrl: String = ""

    fun startOAuthFlow(haUrl: String): String {
        pendingOAuthUrl = haUrl.trimEnd('/')
        // Generate random state for CSRF protection
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        pendingOAuthState = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        return OAuthConfig.buildAuthUrl(pendingOAuthUrl, pendingOAuthState!!)
    }

    fun handleOAuthCallback(code: String, state: String?) {
        viewModelScope.launch {
            _settingsState.value = SettingsState.TestingConnection

            // Validate state to prevent CSRF
            if (state != pendingOAuthState) {
                _settingsState.value = SettingsState.ConnectionFailed("Invalid OAuth state - possible security issue")
                loadSettings()
                return@launch
            }

            try {
                val tokenManager = OAuthTokenManager(pendingOAuthUrl, secureTokenStorage)
                // Exchange code for tokens (tokens are saved internally)
                tokenManager.exchangeCodeForTokens(code)

                // Initialize app with OAuth token manager
                app.initializeHomeAssistantWithOAuth(pendingOAuthUrl, tokenManager)

                // Test connection
                val testMcpClient = McpClientManager(pendingOAuthUrl, tokenManager)
                testMcpClient.connect()
                val tools = testMcpClient.getTools()
                testMcpClient.shutdown()

                if (tools.isNotEmpty()) {
                    // Check for HACS integration now that HA is connected
                    val sharedConfig = app.fetchSharedConfig()
                    val integrationMsg = if (sharedConfig != null) {
                        " HA Live Config integration detected."
                    } else {
                        ""
                    }
                    _settingsState.value = SettingsState.ConnectionSuccess("Connected via OAuth! Found ${tools.size} tools.$integrationMsg")
                } else {
                    _settingsState.value = SettingsState.ConnectionFailed("Connected but no tools found")
                }
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("OAuth failed: ${e.message}")
            }

            loadSettings()
        }
    }

    fun handleOAuthError(error: String) {
        _settingsState.value = SettingsState.ConnectionFailed(error)
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val haUrl = haAuth.getHaUrl() ?: "Not configured"
            val authMethodDisplay = if (haAuth.isAuthenticated()) "OAuth (Browser Login)" else "Not authenticated"

            val geminiKey = GeminiConfig.getApiKey(getApplication()) ?: "Not configured"
            val profileCount = app.profileService.getAllProfiles().size

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

            val hasSharedKey = GeminiConfig.hasSharedKey()
            val isUsingSharedKey = GeminiConfig.isUsingSharedKey(getApplication())
            val sharedConfigAvailable = app.isSharedConfigAvailable()

            _settingsState.value = SettingsState.Loaded(
                haUrl = haUrl,
                authMethod = authMethodDisplay,
                profileCount = profileCount,
                isReadOnly = isChatActive,
                geminiApiKey = geminiKey,
                conversationService = serviceDisplayName,
                canChooseService = canChooseService,
                wakeWordEnabled = wakeWordSettings.enabled,
                wakeWordDetails = wakeWordDetails,
                wakeWordThreshold = wakeWordSettings.threshold,
                hasSharedKey = hasSharedKey,
                isUsingSharedKey = isUsingSharedKey,
                sharedConfigAvailable = sharedConfigAvailable
            )
        }
    }

    fun testHAConnection() {
        viewModelScope.launch {
            _settingsState.value = SettingsState.TestingConnection

            var testMcpClient: McpClientManager? = null
            try {
                val tokenManager = haAuth.getTokenManager()
                    ?: throw Exception("Not authenticated")
                val url = haAuth.getHaUrl()
                    ?: throw Exception("HA URL not configured")

                // Create temporary MCP connection for testing
                testMcpClient = McpClientManager(url, tokenManager)
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

    fun setUseSharedKey(useShared: Boolean) {
        viewModelScope.launch {
            GeminiConfig.setUseSharedKey(getApplication(), useShared)
            loadSettings()
        }
    }

    fun setSharedGeminiKey(apiKey: String) {
        viewModelScope.launch {
            try {
                val repo = app.sharedConfigRepo
                if (repo != null && repo.setGeminiKey(apiKey)) {
                    // Refresh shared config
                    app.fetchSharedConfig()
                    _settingsState.value = SettingsState.ConnectionSuccess("Shared API key updated")
                } else {
                    _settingsState.value = SettingsState.ConnectionFailed("Failed to update shared key")
                }
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed("Failed: ${e.message}")
            }
            loadSettings()
        }
    }
}
