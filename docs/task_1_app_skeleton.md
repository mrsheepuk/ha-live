# **Task 0: Android App Skeleton (MVP v3)**

## **1. Objective**

To create a minimal viable product (MVP) for the Android app with a two-step configuration flow:

### **Step 1: Firebase Setup (BYOFP)**
1. On first launch, check if Firebase is configured
2. If not, display "Import google-services.json" button
3. User selects their google-services.json file via file picker
4. App parses and saves credentials to SharedPreferences
5. Firebase is initialized dynamically

### **Step 2: Home Assistant Setup**
1. After Firebase is configured, prompt for HA connection details
2. User enters HA base URL (e.g., `https://homeassistant.local:8123`)
3. User enters HA long-lived access token
4. App saves these to SharedPreferences
5. App establishes MCP SSE connection and performs initialization handshake

### **Step 3: Ready to Talk**
- Both Firebase and HA are configured
- MCP connection is established and initialized
- "Talk" button is available

This achieves both "Bring Your Own Firebase Project" (BYOFP) and "Bring Your Own Home Assistant" goals.

## **2. Core Architecture**

The file structure is similar, but the logic inside the files is different.

app/
├── src/main/
│   ├── java/uk/co/mrsheep/halive/
│   │   ├── core/
│   │   │   ├── **FirebaseConfig.kt** <-- (Parses JSON and uses SharedPreferences)
│   │   │   └── **HAConfig.kt** <-- (NEW: Stores HA URL and token)
│   │   ├── services/
│   │   │   ├── **McpClientManager.kt** <-- (NEW: SSE connection + MCP lifecycle)
│   │   │   ├── GeminiService.kt     (Stubbed for Task 1)
│   │   │   └── HomeAssistantRepository.kt (Wraps McpClientManager)
│   │   ├── ui/
│   │   │   ├── MainViewModel.kt     (Manages multi-step config states)
│   │   │   └── MainActivity.kt      (Handles file picking, text input & UI state)
│   │   └── HAGeminiApp.kt           (Application class, holds global MCP client)
│   ├── res/
│   │   └── layout/
│   │       └── activity_main.xml    (Dynamic UI based on configuration state)
│   └── AndroidManifest.xml        (Permissions: INTERNET, READ_EXTERNAL_STORAGE)
│
└── build.gradle.kts                 (Dependencies: Firebase, OkHttp SSE, etc.)

## **3. Key File Definitions**

### **HAGeminiApp.kt (Application Class)**

```kotlin
package uk.co.mrsheep.halive

import android.app.Application
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.services.McpClientManager
import uk.co.mrsheep.halive.services.HomeAssistantRepository

class HAGeminiApp : Application() {
    // Global MCP client - will be initialized after HA config
    var mcpClient: McpClientManager? = null
    var haRepository: HomeAssistantRepository? = null

    override fun onCreate() {
        super.onCreate()

        // Try to initialize Firebase on launch
        FirebaseConfig.initializeFirebase(this)

        // Note: MCP connection is NOT established here
        // It will be established in MainActivity after user configures HA
    }

    /**
     * Called by MainActivity after user provides HA credentials.
     * Establishes the MCP SSE connection and performs initialization handshake.
     */
    suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
        mcpClient = McpClientManager(haUrl, haToken)
        mcpClient?.initialize() // SSE connection + MCP handshake
        haRepository = HomeAssistantRepository(mcpClient!!)
    }

    /**
     * Called when app is closing to gracefully shut down MCP connection.
     */
    fun shutdownHomeAssistant() {
        mcpClient?.shutdown()
        mcpClient = null
        haRepository = null
    }
}
```

### **core/HAConfig.kt (NEW: Home Assistant Config Manager)**

```kotlin
package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

object HAConfig {
    private const val PREFS_NAME = "ha_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_BASE_URL, null) != null &&
               prefs.getString(KEY_TOKEN, null) != null
    }

    fun saveConfig(context: Context, baseUrl: String, token: String) {
        getPrefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun loadConfig(context: Context): Pair<String, String>? {
        val prefs = getPrefs(context)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
        val token = prefs.getString(KEY_TOKEN, null)

        return if (baseUrl != null && token != null) {
            Pair(baseUrl, token)
        } else {
            null
        }
    }

    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
```

### **core/FirebaseConfig.kt (Config Manager)**

```kotlin
package uk.co.mrsheep.halive.core

import android.content.Context  
import android.net.Uri  
import android.content.SharedPreferences  
import com.google.firebase.FirebaseApp  
import com.google.firebase.FirebaseOptions  
import org.json.JSONObject  
import java.io.BufferedReader  
import java.io.InputStreamReader

object FirebaseConfig {

    private const val PREFS_NAME = "firebase_creds"  
    private const val KEY_API = "api_key"  
    private const val KEY_APP_ID = "app_id"  
    private const val KEY_PROJECT_ID = "project_id"  
    private const val KEY_GCM_SENDER_ID = "gcm_sender_id"

    private fun getPrefs(context: Context): SharedPreferences {  
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)  
    }

    /**  
     * Tries to initialize Firebase if it's not already.  
     * Returns true if successful or already initialized.  
     */  
    fun initializeFirebase(context: Context): Boolean {  
        if (FirebaseApp.getApps(context).isNotEmpty()) {  
            return true // Already initialized  
        }

        val options = loadOptions(context)  
        if (options != null) {  
            try {  
                FirebaseApp.initializeApp(context, options)  
                return true  
            } catch (e: Exception) {  
                // Config is bad, clear it  
                clearConfig(context)  
                return false  
            }  
        }  
        return false // Not configured  
    }

    /**  
     * Reads a google-services.json file, parses it, and saves it to SharedPreferences.  
     */  
    fun saveConfigFromUri(context: Context, uri: Uri) {  
        val inputStream = context.contentResolver.openInputStream(uri)  
        val jsonString = inputStream.use {  
            BufferedReader(InputStreamReader(it)).readText()  
        }

        // Parse the google-services.json  
        val json = JSONObject(jsonString)  
        val projectInfo = json.getJSONObject("project_info")  
        val client = json.getJSONArray("client").getJSONObject(0)  
        val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")  
        val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")  
        val projectId = projectInfo.getString("project_id")  
        val gcmSenderId = projectInfo.getString("project_number")

        // Save to SharedPreferences  
        getPrefs(context).edit()  
            .putString(KEY_API, apiKey)  
            .putString(KEY_APP_ID, appId)  
            .putString(KEY_PROJECT_ID, projectId)  
            .putString(KEY_GCM_SENDER_ID, gcmSenderId)  
            .apply()  
    }  
      
    private fun loadOptions(context: Context): FirebaseOptions? {  
        val prefs = getPrefs(context)  
        val apiKey = prefs.getString(KEY_API, null)  
        val appId = prefs.getString(KEY_APP_ID, null)  
        val projectId = prefs.getString(KEY_PROJECT_ID, null)  
        val gcmSenderId = prefs.getString(KEY_GCM_SENDER_ID, null)

        if (apiKey == null || appId == null || projectId == null || gcmSenderId == null) {  
            return null // Not configured  
        }

        return FirebaseOptions.Builder()  
            .setApiKey(apiKey)  
            .setApplicationId(appId)  
            .setProjectId(projectId)  
            .setGcmSenderId(gcmSenderId)  
            .build()  
    }  
      
    private fun clearConfig(context: Context) {  
        getPrefs(context).edit().clear().apply()  
    }  
}
```

### **services/McpClientManager.kt (NEW: MCP Connection Manager)**

```kotlin
package uk.co.mrsheep.halive.services

// NOTE: This is a STUB for Task 0. Full implementation in Task 2.
// This class will handle:
// - Opening SSE connection to /api/mcp
// - MCP initialization handshake (initialize -> initialized)
// - Sending JSON-RPC requests and correlating responses
// - Graceful shutdown

class McpClientManager(
    private val haBaseUrl: String,
    private val haToken: String
) {
    /**
     * Phase 1: Initialize the MCP connection.
     * - Opens SSE connection
     * - Sends 'initialize' request
     * - Sends 'initialized' notification
     */
    suspend fun initialize(): Boolean {
        // TODO (Task 2): Implement SSE connection + handshake
        return true // Stub: always succeeds
    }

    /**
     * Phase 3: Gracefully shut down the connection.
     */
    fun shutdown() {
        // TODO (Task 2): Close SSE connection
    }

    // TODO (Task 2): Add getTools() method
    // TODO (Task 3): Add callTool() method
}
```

### **services/HomeAssistantRepository.kt (Wraps MCP Client)**

```kotlin
package uk.co.mrsheep.halive.services

// NOTE: This is a STUB for Task 0. Full implementation in Tasks 2 & 3.

class HomeAssistantRepository(
    private val mcpClient: McpClientManager
) {
    // TODO (Task 2): suspend fun getTools(): List<Tool>
    // TODO (Task 3): suspend fun executeTool(functionCall: FunctionCallPart): FunctionResponsePart
}
```

### **ui/MainViewModel.kt (State Holder)**

```kotlin
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
        // TODO (Task 1): Start Gemini Live session
    }

    fun onTalkButtonReleased() {
        _uiState.value = UiState.ReadyToTalk
        // TODO (Task 1): Stop Gemini Live session
    }
}
```

### **ui/MainActivity.kt (The View)**

package uk.co.mrsheep.halive.ui

import android.os.Bundle  
import android.view.MotionEvent  
import android.view.View  
import android.widget.Button  
import android.widget.TextView  
import androidx.activity.result.contract.ActivityResultContracts  
import androidx.activity.viewModels  
import androidx.appcompat.app.AppCompatActivity  
import androidx.lifecycle.lifecycleScope  
import uk.co.mrsheep.halive.R  
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var statusText: TextView  
    private lateinit var mainButton: Button

    // Activity Result Launcher for the file picker  
    private val selectConfigFileLauncher = registerForActivityResult(  
        ActivityResultContracts.OpenDocument()  
    ) { uri: Uri? ->  
        uri?.let {  
            viewModel.saveConfigFile(it)  
        }  
    }

    override fun onCreate(savedInstanceState: Bundle?) {  
        super.onCreate(savedInstanceState)  
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)  
        mainButton = findViewById(R.id.mainButton) // Renamed for multi-purpose

        // Observe the UI state from the ViewModel  
        lifecycleScope.launch {  
            viewModel.uiState.collect { state ->  
                updateUiForState(state)  
            }  
        }  
    }

    private fun updateUiForState(state: UiState) {  
        when (state) {  
            UiState.Loading -> {  
                mainButton.visibility = View.GONE  
                statusText.text = "Loading..."  
            }  
            UiState.ConfigNeeded -> {  
                mainButton.visibility = View.VISIBLE  
                mainButton.text = "Import google-services.json"  
                statusText.text = "Configuration needed"  
                mainButton.setOnClickListener {  
                    // Launch file picker  
                    selectConfigFileLauncher.launch(arrayOf("application/json"))  
                }  
                mainButton.setOnTouchListener(null) // Remove talk listener  
            }  
            UiState.ReadyToTalk -> {  
                mainButton.visibility = View.VISIBLE  
                mainButton.text = "TALK"  
                statusText.text = "Hold to Talk"  
                mainButton.setOnClickListener(null) // Remove import listener  
                mainButton.setOnTouchListener(talkListener)  
            }  
            UiState.Listening -> {  
                mainButton.visibility = View.VISIBLE  
                statusText.text = "Listening..."  
                // Listener is already active  
            }  
            is UiState.Error -> {  
                mainButton.visibility = View.GONE  
                statusText.text = state.message  
            }  
        }  
    }

    // Moved talk logic to a reusable listener  
    private val talkListener = View.OnTouchListener { _, event ->  
        when (event.action) {  
            MotionEvent.ACTION_DOWN -> {  
                viewModel.onTalkButtonPressed()  
                true  
            }  
            MotionEvent.ACTION_UP -> {  
                viewModel.onTalkButtonReleased()  
                true  
            }  
            else -> false  
        }  
    }  
}

### **activity_main.xml**

<!-- ... same layout ... -->  
    <!-- Renamed id from talkButton to mainButton -->  
    <Button  
        android:id="@+id/mainButton"   
        ... />  
<!-- ... same layout ... -->  