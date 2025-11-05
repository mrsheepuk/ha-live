package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.SystemPromptConfig
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _onboardingState = MutableStateFlow<OnboardingState>(OnboardingState.Step1Firebase)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState

    private val app = application as HAGeminiApp

    private var currentHAUrl: String = ""
    private var currentHAToken: String = ""

    fun startOnboarding() {
        // Check what's already configured
        viewModelScope.launch {
            if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                // Firebase already done, skip to HA
                _onboardingState.value = OnboardingState.Step2HomeAssistant
            } else {
                _onboardingState.value = OnboardingState.Step1Firebase
            }
        }
    }

    fun saveFirebaseConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)

                if (FirebaseConfig.initializeFirebase(getApplication())) {
                    // Success, move to step 2
                    _onboardingState.value = OnboardingState.Step2HomeAssistant
                } else {
                    // TODO: Show error
                }
            } catch (e: Exception) {
                // TODO: Show error
            }
        }
    }

    fun testHAConnection(url: String, token: String) {
        viewModelScope.launch {
            _onboardingState.value = OnboardingState.TestingConnection

            try {
                // Store temporarily
                currentHAUrl = url
                currentHAToken = token

                // Initialize connection
                app.initializeHomeAssistant(url, token)

                // Try to fetch tools
                val tools = app.haRepository?.getTools()

                if (tools != null && tools.isNotEmpty()) {
                    _onboardingState.value = OnboardingState.ConnectionSuccess("Connected successfully!")
                } else {
                    _onboardingState.value = OnboardingState.ConnectionFailed("No tools found")
                }
            } catch (e: Exception) {
                _onboardingState.value = OnboardingState.ConnectionFailed(e.message ?: "Connection failed")
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
            _onboardingState.value = OnboardingState.Step3Complete
        }
    }

    fun completeOnboarding() {
        _onboardingState.value = OnboardingState.Finished
    }
}
