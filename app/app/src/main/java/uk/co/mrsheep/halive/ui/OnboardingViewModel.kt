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
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _onboardingState = MutableStateFlow<OnboardingState>(OnboardingState.Step1ProviderSelection)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState

    private val app = application as HAGeminiApp

    private var selectedProvider: ConversationServicePreference.PreferredService? = null
    private var currentHAUrl: String = ""
    private var currentHAToken: String = ""

    fun startOnboarding() {
        viewModelScope.launch {
            // Check if ANY provider is already configured
            val hasFirebase = FirebaseConfig.isConfigured(getApplication())
            val hasGemini = GeminiConfig.isConfigured(getApplication())

            if (hasFirebase || hasGemini) {
                // Skip provider setup, go straight to HA
                _onboardingState.value = OnboardingState.Step3HomeAssistant
            } else {
                // New user, start with provider selection
                _onboardingState.value = OnboardingState.Step1ProviderSelection
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

    fun saveFirebaseConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)

                if (FirebaseConfig.initializeFirebase(getApplication())) {
                    // Success, move to step 3
                    _onboardingState.value = OnboardingState.Step3HomeAssistant
                } else {
                    _onboardingState.value = OnboardingState.FirebaseConfigInvalid(
                        "Failed to initialize Firebase. Please check your google-services.json file."
                    )
                }
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.FirebaseConfigInvalid(
                    e.message ?: "Invalid google-services.json file"
                )
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
            // Save the validated config
            HAConfig.saveConfig(getApplication(), currentHAUrl, currentHAToken)

            // Create default profile (NEW)
            ProfileManager.ensureDefaultProfileExists()

            // Move to final step
            _onboardingState.value = OnboardingState.Step4Complete
        }
    }

    fun completeOnboarding() {
        _onboardingState.value = OnboardingState.Finished
    }
}
