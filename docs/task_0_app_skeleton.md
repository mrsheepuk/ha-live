# **Task 0: Android App Skeleton (MVP v2)**

## **1. Objective**

To create a minimal viable product (MVP) for the Android app. This version will **not** require the user to rebuild. Instead, it will:

1. On first launch, check if it's configured.  
2. If not configured, it will display an "Import google-services.json" button.  
3. The user will use the Android file picker to select the google-services.json file they downloaded from their **own Firebase project**.  
4. The app will parse this file, save the necessary credentials to SharedPreferences, and then initialize Firebase dynamically.  
5. On all subsequent launches, the app will find the saved credentials and initialize automatically, showing the "Talk" button.

This achieves the "Bring Your Own Firebase Project" (BYOFP) goal in a user-friendly, "geek-friendly" way.

## **2. Core Architecture**

The file structure is similar, but the logic inside the files is different.

app/  
├── src/main/  
│   ├── java/com/example/halive/  
│   │   ├── core/  
│   │   │   └── **FirebaseConfig.kt** <-- (NOW parses JSON and uses SharedPreferences)  
│   │   ├── services/  
│   │   │   ├── GeminiService.kt     (Stubbed)  
│   │   │   └── HomeAssistantRepository.kt (Stubbed)  
│   │   ├── ui/  
│   │   │   ├── MainViewModel.kt     (Manages UI state: Loading, ConfigNeeded, Ready)  
│   │   │   └── MainActivity.kt      (Handles file picking & UI state)  
│   │   └── HAGeminiApp.kt           (Application class, tries to init)  
│   ├── res/  
│   │   └── layout/  
│   │       └── activity_main.xml    (Button text/visibility will change based on state)  
│   └── AndroidManifest.xml        (Permissions)  
│  
└── build.gradle.kts                 (Dependencies)

## **3. Key File Definitions**

### **HAGeminiApp.kt (Application Class)**

package com.example.halive

import android.app.Application  
import com.example.halive.core.FirebaseConfig  
import com.google.firebase.FirebaseApp

class HAGeminiApp : Application() {  
    override fun onCreate() {  
        super.onCreate()  
          
        // Try to initialize Firebase on launch.  
        // If config is not saved, this will just be skipped.  
        // MainActivity is responsible for forcing the user to configure.  
        FirebaseConfig.initializeFirebase(this)  
    }  
}

### **core/FirebaseConfig.kt (Config Manager)**

package com.example.halive.core

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

### **ui/MainViewModel.kt (State Holder)**

package com.example.halive.ui

import android.app.Application  
import android.net.Uri  
import androidx.lifecycle.AndroidViewModel  
import androidx.lifecycle.viewModelScope  
import com.example.halive.core.FirebaseConfig  
import com.google.firebase.FirebaseApp  
import kotlinx.coroutines.flow.MutableStateFlow  
import kotlinx.coroutines.flow.StateFlow  
import kotlinx.coroutines.launch

// Define the different states our UI can be in  
sealed class UiState {  
    object Loading : UiState()  
    object ConfigNeeded : UiState()  
    object ReadyToTalk : UiState()  
    object Listening : UiState()  
    data class Error(val message: String) : UiState()  
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)  
    val uiState: StateFlow<UiState> = _uiState

    // Stubs for later tasks  
    // private val geminiService = GeminiService()  
    // private val homeAssistantRepository = HomeAssistantRepository()

    init {  
        checkIfConfigured()  
    }

    private fun checkIfConfigured() {  
        viewModelScope.launch {  
            _uiState.value = UiState.Loading  
            // Check if Firebase was successfully initialized on app start  
            if (FirebaseApp.getApps(getApplication()).isEmpty()) {  
                _uiState.value = UiState.ConfigNeeded  
            } else {  
                // In a real app, we'd also init GeminiService, etc.  
                _uiState.value = UiState.ReadyToTalk  
            }  
        }  
    }

    /**  
     * Called by MainActivity when the user selects a file.  
     */  
    fun saveConfigFile(uri: Uri) {  
        viewModelScope.launch {  
            try {  
                FirebaseConfig.saveConfigFromUri(getApplication(), uri)  
                // Now try to initialize with the new config  
                if (FirebaseConfig.initializeFirebase(getApplication())) {  
                    _uiState.value = UiState.ReadyToTalk  
                } else {  
                    _uiState.value = UiState.Error("Invalid config file.")  
                }  
            } catch (e: Exception) {  
                _uiState.value = UiState.Error("Failed to read file: ${e.message}")  
            }  
        }  
    }

    fun onTalkButtonPressed() {  
        _uiState.value = UiState.Listening  
        // TODO (Task 3): geminiService.startSession(...)  
    }

    fun onTalkButtonReleased() {  
        _uiState.value = UiState.ReadyToTalk  
        // TODO (Task 3): geminiService.stopSession()  
    }  
}

### **ui/MainActivity.kt (The View)**

package com.example.halive.ui

import android.os.Bundle  
import android.view.MotionEvent  
import android.view.View  
import android.widget.Button  
import android.widget.TextView  
import androidx.activity.result.contract.ActivityResultContracts  
import androidx.activity.viewModels  
import androidx.appcompat.app.AppCompatActivity  
import androidx.lifecycle.lifecycleScope  
import com.example.halive.R  
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