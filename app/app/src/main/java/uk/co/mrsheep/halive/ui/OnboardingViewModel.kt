package uk.co.mrsheep.halive.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ConversationServicePreference
import uk.co.mrsheep.halive.core.OAuthConfig
import uk.co.mrsheep.halive.core.SecureTokenStorage
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _onboardingState = MutableStateFlow<OnboardingState>(OnboardingState.Step1GeminiConfig)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState

    private val app = application as HAGeminiApp

    private var currentHAUrl: String = ""
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
                _onboardingState.value = OnboardingState.Step2HomeAssistant
            } else {
                // New user, auto-select Gemini Direct and show config
                ConversationServicePreference.setPreferred(getApplication(), ConversationServicePreference.PreferredService.GEMINI_DIRECT)
                _onboardingState.value = OnboardingState.Step1GeminiConfig
            }
        }
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
                _onboardingState.value = OnboardingState.Step2HomeAssistant
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.GeminiKeyInvalid(e.message ?: "Failed to save API key")
            }
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
            // Validate state to prevent CSRF
            if (state != pendingOAuthState) {
                _onboardingState.value = OnboardingState.OAuthError("Invalid OAuth state - possible CSRF attack")
                return@launch
            }

            try {
                val tokenManager = OAuthTokenManager(currentHAUrl, secureTokenStorage)
                // Exchange code for tokens (tokens are saved internally by exchangeCodeForTokens)
                tokenManager.exchangeCodeForTokens(code)

                // Initialize app with OAuth token manager
                app.initializeHomeAssistantWithOAuth(currentHAUrl, tokenManager)

                // Test connection
                val testMcpClient = McpClientManager(currentHAUrl, tokenManager)
                testMcpClient.connect()
                val tools = testMcpClient.getTools()
                testMcpClient.shutdown()

                if (tools.isNotEmpty()) {
                    // Create default profile
                    ProfileManager.ensureDefaultProfileExists()
                    // Move to completion step
                    _onboardingState.value = OnboardingState.Step3Complete
                } else {
                    _onboardingState.value = OnboardingState.OAuthError("No tools found - is the MCP server enabled?")
                }
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.OAuthError(e.message ?: "OAuth failed")
            }
        }
    }

    fun handleOAuthError(error: String) {
        _onboardingState.value = OnboardingState.OAuthError(error)
    }
}
