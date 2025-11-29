package uk.co.mrsheep.halive.ui

import android.app.Application
import android.util.Base64
import android.util.Log
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
    companion object {
        private const val TAG = "OnboardingViewModel"
    }

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
            // Always start with HA OAuth first (reordered flow)
            // Check for shared Gemini key after HA connection is established
            ConversationServicePreference.setPreferred(getApplication(), ConversationServicePreference.PreferredService.GEMINI_DIRECT)
            _onboardingState.value = OnboardingState.Step2HomeAssistant
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
                    // Tools check succeeded - now check for shared config
                    _onboardingState.value = OnboardingState.CheckingSharedConfig
                    val sharedConfig = app.fetchSharedConfig()

                    when {
                        sharedConfig == null -> {
                            // No integration - proceed with local setup (need Gemini key if not set)
                            Log.d(TAG, "No shared config found - no integration installed")
                            if (!GeminiConfig.isConfigured(getApplication())) {
                                _onboardingState.value = OnboardingState.NoSharedConfig
                            } else {
                                ProfileManager.ensureDefaultProfileExists()
                                _onboardingState.value = OnboardingState.Step3Complete
                            }
                        }
                        sharedConfig.geminiApiKey != null -> {
                            // Shared config with API key - all set!
                            Log.d(TAG, "Shared config found with API key")
                            ProfileManager.ensureDefaultProfileExists()
                            _onboardingState.value = OnboardingState.SharedConfigFound(
                                hasApiKey = true,
                                profileCount = sharedConfig.profiles.size
                            )
                        }
                        else -> {
                            // Integration installed but no shared key
                            Log.d(TAG, "Shared config found without API key")
                            _onboardingState.value = OnboardingState.SharedConfigFound(
                                hasApiKey = false,
                                profileCount = sharedConfig.profiles.size
                            )
                        }
                    }
                } else {
                    _onboardingState.value = OnboardingState.OAuthError("No tools found - is the MCP server enabled?")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OAuth callback failed", e)
                _onboardingState.value = OnboardingState.OAuthError(e.message ?: "OAuth failed")
            }
        }
    }

    fun handleOAuthError(error: String) {
        _onboardingState.value = OnboardingState.OAuthError(error)
    }

    /**
     * Save the Gemini API key to the shared config in Home Assistant.
     * Called when user provides API key on the shared config step.
     */
    fun setSharedGeminiKey(apiKey: String) {
        viewModelScope.launch {
            try {
                val repo = app.sharedConfigRepo
                if (repo != null) {
                    Log.d(TAG, "Saving shared Gemini key")
                    val success = repo.setGeminiKey(apiKey)
                    if (success) {
                        // Refresh config after setting key
                        app.fetchSharedConfig()
                        ProfileManager.ensureDefaultProfileExists()
                        _onboardingState.value = OnboardingState.Step3Complete
                    } else {
                        _onboardingState.value = OnboardingState.GeminiKeyInvalid("Failed to save shared key")
                    }
                } else {
                    _onboardingState.value = OnboardingState.GeminiKeyInvalid("Shared config repository not initialized")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set shared Gemini key", e)
                _onboardingState.value = OnboardingState.GeminiKeyInvalid(e.message ?: "Failed to save key")
            }
        }
    }

    /**
     * Continue with local setup by showing the Gemini key entry step.
     * User chose to skip shared config and set up locally.
     */
    fun continueWithLocalSetup() {
        Log.d(TAG, "Continuing with local setup")
        // Check if Gemini is already configured
        if (GeminiConfig.isConfigured(getApplication())) {
            // Already have Gemini key, go straight to complete
            ProfileManager.ensureDefaultProfileExists()
            _onboardingState.value = OnboardingState.Step3Complete
        } else {
            // Need to enter Gemini key locally
            _onboardingState.value = OnboardingState.Step1GeminiConfig
        }
    }

    /**
     * Skip to completion step.
     * User confirmed they have shared config with API key or chose to complete without further setup.
     */
    fun skipToComplete() {
        Log.d(TAG, "Skipping to complete step")
        ProfileManager.ensureDefaultProfileExists()
        _onboardingState.value = OnboardingState.Step3Complete
    }
}
