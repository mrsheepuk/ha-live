package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Define the different states our UI can be in
sealed class UiState {
    object Loading : UiState()
    object FirebaseConfigNeeded : UiState()  // Need google-services.json
    object HAConfigNeeded : UiState()        // Need HA URL + token
    object ReadyToTalk : UiState()           // Everything initialized
    object Listening : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val app = application as HAGeminiApp

    init {
        checkConfiguration()
    }

    /**
     * Check what needs to be configured.
     */
    private fun checkConfiguration() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading

            // Step 1: Check Firebase
            if (FirebaseApp.getApps(getApplication()).isEmpty()) {
                _uiState.value = UiState.FirebaseConfigNeeded
                return@launch
            }

            // Step 2: Check Home Assistant
            if (!HAConfig.isConfigured(getApplication())) {
                _uiState.value = UiState.HAConfigNeeded
                return@launch
            }

            // Step 3: Initialize MCP connection
            try {
                val (haUrl, haToken) = HAConfig.loadConfig(getApplication())!!
                app.initializeHomeAssistant(haUrl, haToken)
                _uiState.value = UiState.ReadyToTalk
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect to HA: ${e.message}")
            }
        }
    }

    /**
     * Called by MainActivity when the user selects a Firebase config file.
     */
    fun saveFirebaseConfigFile(uri: Uri) {
        viewModelScope.launch {
            try {
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)

                // Try to initialize Firebase with the new config
                if (FirebaseConfig.initializeFirebase(getApplication())) {
                    // Move to next step: HA config
                    checkConfiguration()
                } else {
                    _uiState.value = UiState.Error("Invalid Firebase config file.")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    /**
     * Called by MainActivity when user provides HA credentials.
     */
    fun saveHAConfig(baseUrl: String, token: String) {
        viewModelScope.launch {
            try {
                // Validate inputs (basic check)
                if (baseUrl.isBlank() || token.isBlank()) {
                    _uiState.value = UiState.Error("URL and token cannot be empty")
                    return@launch
                }

                // Save config
                HAConfig.saveConfig(getApplication(), baseUrl, token)

                // Try to initialize MCP connection
                app.initializeHomeAssistant(baseUrl, token)

                _uiState.value = UiState.ReadyToTalk
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to connect: ${e.message}")
                // Clear bad config
                HAConfig.clearConfig(getApplication())
            }
        }
    }

    fun onTalkButtonPressed() {
        _uiState.value = UiState.Listening
        // TODO (Task 2): Start Gemini Live session
    }

    fun onTalkButtonReleased() {
        _uiState.value = UiState.ReadyToTalk
        // TODO (Task 2): Stop Gemini Live session
    }
}
