package uk.co.mrsheep.halive.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ConversationServicePreference
import uk.co.mrsheep.halive.core.OAuthConfig
import uk.co.mrsheep.halive.core.SecureTokenStorage
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import uk.co.mrsheep.halive.services.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _onboardingState = MutableStateFlow<OnboardingState>(OnboardingState.Step2ProviderConfig)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState

    private val app = application as HAGeminiApp

    private var selectedProvider: ConversationServicePreference.PreferredService? = null
    private var currentHAUrl: String = ""
    private var currentHAToken: String = ""
    private var pendingOAuthState: String? = null
    private lateinit var secureTokenStorage: SecureTokenStorage

    init {
        secureTokenStorage = SecureTokenStorage(application)
    }

    fun startOnboarding() {
        viewModelScope.launch {
            // Check if Gemini config is already set up
            val hasGemini = GeminiConfig.isConfigured(getApplication())

            if (hasGemini) {
                // Skip provider setup, go straight to HA
                _onboardingState.value = OnboardingState.Step3HomeAssistant
            } else {
                // New user, auto-select Gemini Direct and show config
                selectedProvider = ConversationServicePreference.PreferredService.GEMINI_DIRECT
                ConversationServicePreference.setPreferred(getApplication(), ConversationServicePreference.PreferredService.GEMINI_DIRECT)
                _onboardingState.value = OnboardingState.Step2ProviderConfig
            }
        }
    }

    fun selectProvider(provider: ConversationServicePreference.PreferredService) {
        selectedProvider = provider
        // Save preference immediately
        ConversationServicePreference.setPreferred(getApplication(), provider)
        // Move to config step
        _onboardingState.value = OnboardingState.Step2ProviderConfig
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            _onboardingState.value = OnboardingState.ValidatingGeminiKey

            try {
                // Basic validation
                if (apiKey.isBlank() || apiKey.length < 20) {
                    _onboardingState.value = OnboardingState.GeminiKeyInvalid("Invalid API key format")
                    return@launch
                }

                // Save to config
                GeminiConfig.saveApiKey(getApplication(), apiKey)

                // Move to HA step
                _onboardingState.value = OnboardingState.Step3HomeAssistant
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.GeminiKeyInvalid(e.message ?: "Failed to save API key")
            }
        }
    }

    fun testHAConnection(url: String, token: String) {
        viewModelScope.launch {
            _onboardingState.value = OnboardingState.TestingConnection

            var testMcpClient: McpClientManager? = null
            try {
                // Store temporarily
                currentHAUrl = url
                currentHAToken = token

                // Store credentials in app for later use
                app.initializeHomeAssistant(url, token)

                // Create temporary MCP connection for testing
                testMcpClient = McpClientManager(url, token)
                testMcpClient.connect()

                // Try to fetch tools
                val tools = testMcpClient.getTools()

                if (tools.isNotEmpty()) {
                    _onboardingState.value = OnboardingState.ConnectionSuccess("Connected successfully!")
                } else {
                    _onboardingState.value = OnboardingState.ConnectionFailed("No tools found")
                }
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.ConnectionFailed(e.message ?: "Connection failed")
            } finally {
                // Clean up temporary test connection
                testMcpClient?.shutdown()
            }
        }
    }

    fun saveHAConfigAndContinue() {
        viewModelScope.launch {
            // Only save to HAConfig if using legacy token (not OAuth)
            // OAuth tokens are already saved in SecureTokenStorage
            if (currentHAToken.isNotEmpty()) {
                HAConfig.saveConfig(getApplication(), currentHAUrl, currentHAToken)
            }

            // Create default profile (NEW)
            ProfileManager.ensureDefaultProfileExists()

            // Move to final step
            _onboardingState.value = OnboardingState.Step4Complete
        }
    }

    fun completeOnboarding() {
        _onboardingState.value = OnboardingState.Finished
    }

    fun startOAuthFlow(haUrl: String): String {
        currentHAUrl = haUrl.trimEnd('/')
        // Generate random state for CSRF protection
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        pendingOAuthState = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        return OAuthConfig.buildAuthUrl(currentHAUrl, pendingOAuthState!!)
    }

    fun handleOAuthCallback(code: String, state: String?) {
        viewModelScope.launch {
            _onboardingState.value = OnboardingState.TestingConnection

            // Validate state to prevent CSRF
            if (state != pendingOAuthState) {
                _onboardingState.value = OnboardingState.ConnectionFailed("Invalid OAuth state - possible CSRF attack")
                return@launch
            }

            try {
                val tokenManager = OAuthTokenManager(currentHAUrl, secureTokenStorage)
                // Exchange code for tokens (tokens are saved internally by exchangeCodeForTokens)
                tokenManager.exchangeCodeForTokens(code)

                // Initialize app with OAuth token manager
                app.initializeHomeAssistantWithOAuth(currentHAUrl, tokenManager)

                // Test connection
                val testMcpClient = McpClientManager(currentHAUrl, TokenProvider.OAuth(tokenManager))
                testMcpClient.connect()
                val tools = testMcpClient.getTools()
                testMcpClient.shutdown()

                if (tools.isNotEmpty()) {
                    _onboardingState.value = OnboardingState.ConnectionSuccess("Connected successfully!")
                } else {
                    _onboardingState.value = OnboardingState.ConnectionFailed("No tools found")
                }
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.ConnectionFailed(e.message ?: "OAuth failed")
            }
        }
    }

    fun handleOAuthError(error: String) {
        _onboardingState.value = OnboardingState.ConnectionFailed(error)
    }
}
