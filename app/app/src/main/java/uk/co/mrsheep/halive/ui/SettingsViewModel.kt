package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow<SettingsState>(
        SettingsState.Loaded("", "", "", 0, false)
    )
    val settingsState: StateFlow<SettingsState> = _settingsState

    private val app = application as HAGeminiApp

    // This should be set from MainActivity when launching SettingsActivity
    var isChatActive: Boolean = false

    fun loadSettings() {
        viewModelScope.launch {
            val (haUrl, haToken) = HAConfig.loadConfig(getApplication()) ?: Pair("Not configured", "")
            val projectId = FirebaseConfig.getProjectId(getApplication()) ?: "Not configured"
            val profileCount = ProfileManager.getAllProfiles().size

            _settingsState.value = SettingsState.Loaded(
                haUrl = haUrl,
                haToken = haToken,
                firebaseProjectId = projectId,
                profileCount = profileCount,
                isReadOnly = isChatActive
            )
        }
    }

    fun testHAConnection() {
        viewModelScope.launch {
            _settingsState.value = SettingsState.TestingConnection

            try {
                val (url, token) = HAConfig.loadConfig(getApplication())
                    ?: throw Exception("HA not configured")

                // Initialize temporary connection
                app.initializeHomeAssistant(url, token)

                // Try to fetch tools
                val tools = app.haRepository?.getTools()

                if (tools != null && tools.isNotEmpty()) {
                    _settingsState.value = SettingsState.ConnectionSuccess("Found ${tools.size} tools")
                } else {
                    _settingsState.value = SettingsState.ConnectionFailed("No tools found")
                }
            } catch (e: Exception) {
                _settingsState.value = SettingsState.ConnectionFailed(e.message ?: "Connection failed")
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
}
