# Phase 1 Implementation Plan: Settings & Onboarding

**Parent Document**: [task-settings-and-profiles.md](./task-settings-and-profiles.md)

## Overview

Phase 1 creates the foundation for the Settings & Profile system by:
- Building a dedicated OnboardingActivity for first-time setup
- Creating a SettingsActivity for configuration management
- Enabling read-only Settings access during active chat
- Adding menu navigation to MainActivity

## Files to Create

### New Activities
```
app/src/main/java/uk/co/mrsheep/halive/ui/
├── OnboardingActivity.kt (NEW)
├── SettingsActivity.kt (NEW)
└── SettingsViewModel.kt (NEW)
```

### New Layouts
```
app/src/main/res/layout/
├── activity_onboarding.kt (NEW)
├── activity_settings.kt (NEW)
└── onboarding_step_*.xml (NEW - 3 step layouts)
```

### New Menu Resources
```
app/src/main/res/menu/
└── main_menu.xml (NEW)
```

### New String Resources
```
app/src/main/res/values/
└── strings.xml (UPDATE - add new strings)
```

## Files to Modify

### Existing Code
```
app/src/main/java/uk/co/mrsheep/halive/
├── ui/MainActivity.kt (MODIFY - add menu, remove setup flow)
├── ui/MainViewModel.kt (MODIFY - expose chat state, simplify config check)
├── core/HAConfig.kt (MODIFY - add validation helpers)
└── core/FirebaseConfig.kt (MODIFY - add validation helpers)
```

### Existing Layouts
```
app/src/main/res/layout/
└── activity_main.xml (MODIFY - remove config containers)
```

---

## Detailed Implementation

### 1. OnboardingActivity

**Purpose**: Handle first-time setup flow (Firebase → HA → Profile creation)

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/OnboardingActivity.kt`

```kotlin
package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    // Step containers
    private lateinit var step1Container: View
    private lateinit var step2Container: View
    private lateinit var step3Container: View

    // Progress indicator
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // Step 1: Firebase
    private lateinit var firebaseButton: Button

    // Step 2: HA Config
    private lateinit var haUrlInput: EditText
    private lateinit var haTokenInput: EditText
    private lateinit var haTestButton: Button
    private lateinit var haContinueButton: Button

    // Step 3: Complete
    private lateinit var completeButton: Button

    private val selectConfigFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.saveFirebaseConfig(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        initViews()
        observeState()

        // Start with step 1
        viewModel.startOnboarding()
    }

    private fun initViews() {
        // Progress
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)

        // Step containers
        step1Container = findViewById(R.id.step1Container)
        step2Container = findViewById(R.id.step2Container)
        step3Container = findViewById(R.id.step3Container)

        // Step 1
        firebaseButton = findViewById(R.id.firebaseButton)
        firebaseButton.setOnClickListener {
            selectConfigFileLauncher.launch(arrayOf("application/json"))
        }

        // Step 2
        haUrlInput = findViewById(R.id.haUrlInput)
        haTokenInput = findViewById(R.id.haTokenInput)
        haTestButton = findViewById(R.id.haTestButton)
        haContinueButton = findViewById(R.id.haContinueButton)

        haTestButton.setOnClickListener {
            val url = haUrlInput.text.toString()
            val token = haTokenInput.text.toString()
            viewModel.testHAConnection(url, token)
        }

        haContinueButton.setOnClickListener {
            viewModel.saveHAConfigAndContinue()
        }

        // Step 3
        completeButton = findViewById(R.id.completeButton)
        completeButton.setOnClickListener {
            viewModel.completeOnboarding()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.onboardingState.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: OnboardingState) {
        when (state) {
            OnboardingState.Step1Firebase -> {
                progressText.text = "Step 1 of 3"
                progressBar.progress = 33
                showStep(1)
            }
            OnboardingState.Step2HomeAssistant -> {
                progressText.text = "Step 2 of 3"
                progressBar.progress = 66
                showStep(2)
            }
            is OnboardingState.TestingConnection -> {
                haTestButton.isEnabled = false
                haTestButton.text = "Testing..."
            }
            is OnboardingState.ConnectionSuccess -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✓ Connection successful"
                haContinueButton.isEnabled = true
            }
            is OnboardingState.ConnectionFailed -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✗ Test Connection"
                // Show error message
                // TODO: Add error TextView to layout
            }
            OnboardingState.Step3Complete -> {
                progressText.text = "Step 3 of 3"
                progressBar.progress = 100
                showStep(3)
            }
            is OnboardingState.Finished -> {
                // Navigate to MainActivity
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private fun showStep(step: Int) {
        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
    }
}

// Onboarding states
sealed class OnboardingState {
    object Step1Firebase : OnboardingState()
    object Step2HomeAssistant : OnboardingState()
    object TestingConnection : OnboardingState()
    data class ConnectionSuccess(val message: String) : OnboardingState()
    data class ConnectionFailed(val error: String) : OnboardingState()
    object Step3Complete : OnboardingState()
    object Finished : OnboardingState()
}
```

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/OnboardingViewModel.kt`

```kotlin
package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
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

            // Create default profile (if not exists)
            // TODO: This will be implemented in Phase 2
            // For now, just ensure SystemPromptConfig has default

            // Move to final step
            _onboardingState.value = OnboardingState.Step3Complete
        }
    }

    fun completeOnboarding() {
        _onboardingState.value = OnboardingState.Finished
    }
}
```

---

### 2. SettingsActivity

**Purpose**: Allow configuration management (HA config, Firebase config, profile management button)

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/SettingsActivity.kt`

```kotlin
package uk.co.mrsheep.halive.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    // Profile section
    private lateinit var manageProfilesButton: Button
    private lateinit var profileSummaryText: TextView

    // HA section
    private lateinit var haUrlText: TextView
    private lateinit var haTokenText: TextView
    private lateinit var haEditButton: Button
    private lateinit var haTestButton: Button

    // Firebase section
    private lateinit var firebaseProjectIdText: TextView
    private lateinit var firebaseChangeButton: Button

    // Read-only overlay
    private lateinit var readOnlyOverlay: View
    private lateinit var readOnlyMessage: TextView

    private val selectConfigFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.changeFirebaseConfig(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        observeState()

        viewModel.loadSettings()
    }

    private fun initViews() {
        // Profile section
        manageProfilesButton = findViewById(R.id.manageProfilesButton)
        profileSummaryText = findViewById(R.id.profileSummaryText)

        manageProfilesButton.setOnClickListener {
            // TODO: Phase 3 - Launch ProfileManagementActivity
        }

        // HA section
        haUrlText = findViewById(R.id.haUrlText)
        haTokenText = findViewById(R.id.haTokenText)
        haEditButton = findViewById(R.id.haEditButton)
        haTestButton = findViewById(R.id.haTestButton)

        haEditButton.setOnClickListener {
            showHAEditDialog()
        }

        haTestButton.setOnClickListener {
            viewModel.testHAConnection()
        }

        // Firebase section
        firebaseProjectIdText = findViewById(R.id.firebaseProjectIdText)
        firebaseChangeButton = findViewById(R.id.firebaseChangeButton)

        firebaseChangeButton.setOnClickListener {
            showFirebaseChangeDialog()
        }

        // Read-only overlay
        readOnlyOverlay = findViewById(R.id.readOnlyOverlay)
        readOnlyMessage = findViewById(R.id.readOnlyMessage)
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.settingsState.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: SettingsState) {
        when (state) {
            is SettingsState.Loaded -> {
                // Update UI with current config
                haUrlText.text = state.haUrl
                haTokenText.text = "••••••••" // Masked token
                firebaseProjectIdText.text = state.firebaseProjectId
                profileSummaryText.text = "Profile management coming in Phase 2"

                // Show/hide read-only overlay
                if (state.isReadOnly) {
                    readOnlyOverlay.visibility = View.VISIBLE
                    readOnlyMessage.text = "Stop chat to modify settings"
                } else {
                    readOnlyOverlay.visibility = View.GONE
                }
            }
            is SettingsState.TestingConnection -> {
                haTestButton.isEnabled = false
                haTestButton.text = "Testing..."
            }
            is SettingsState.ConnectionSuccess -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✓ Test Connection"
                showSuccessDialog("Connection successful!")
            }
            is SettingsState.ConnectionFailed -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✗ Test Connection"
                showErrorDialog(state.error)
            }
        }
    }

    private fun showHAEditDialog() {
        // TODO: Create custom dialog layout with EditTexts
        // For now, placeholder
        AlertDialog.Builder(this)
            .setTitle("Edit Home Assistant Config")
            .setMessage("Dialog implementation coming...")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFirebaseChangeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Change Firebase Config")
            .setMessage("Changing Firebase config will restart the app. Continue?")
            .setPositiveButton("Continue") { _, _ ->
                selectConfigFileLauncher.launch(arrayOf("application/json"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Success")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// Settings states
sealed class SettingsState {
    data class Loaded(
        val haUrl: String,
        val haToken: String,
        val firebaseProjectId: String,
        val profileCount: Int,
        val isReadOnly: Boolean
    ) : SettingsState()
    object TestingConnection : SettingsState()
    data class ConnectionSuccess(val message: String) : SettingsState()
    data class ConnectionFailed(val error: String) : SettingsState()
}
```

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/SettingsViewModel.kt`

```kotlin
package uk.co.mrsheep.halive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.HAConfig
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
            val projectId = "TODO: Get from Firebase" // TODO: Add method to FirebaseConfig
            val profileCount = 0 // TODO: Phase 2

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
```

---

### 3. MainActivity Modifications

**Changes needed**:
1. Add options menu with "Settings" item
2. Remove Firebase and HA config UI (move to OnboardingActivity)
3. Check if configured on launch → launch OnboardingActivity if not
4. Pass chat state to SettingsActivity

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/MainActivity.kt`

**Changes**:

```kotlin
// Add to imports
import android.view.Menu
import android.view.MenuItem

// Remove these views (no longer needed on main screen)
// private lateinit var haConfigContainer: View
// private lateinit var systemPromptContainer: View
// private lateinit var systemPromptInput: EditText
// private lateinit var savePromptButton: Button
// private lateinit var resetPromptButton: Button

// Add after viewModel declaration
private fun checkConfigurationAndLaunch() {
    // Check if app is configured
    if (FirebaseApp.getApps(this).isEmpty() || !HAConfig.isConfigured(this)) {
        // Launch onboarding
        val intent = Intent(this, OnboardingActivity::class.java)
        startActivity(intent)
        finish()
        return
    }
}

// Add in onCreate, before setContentView
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Check configuration first
    checkConfigurationAndLaunch()

    setContentView(R.layout.activity_main)
    // ... rest of onCreate
}

// Add menu methods
override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.main_menu, menu)
    return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_settings -> {
            val intent = Intent(this, SettingsActivity::class.java)
            // Pass chat active state
            intent.putExtra("isChatActive", viewModel.isSessionActive())
            startActivity(intent)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

// Update checkConfiguration method
private fun checkConfiguration() {
    viewModelScope.launch {
        _uiState.value = UiState.Loading

        // Configuration check now handled by checkConfigurationAndLaunch
        // This method now only initializes after onboarding is complete

        try {
            val (haUrl, haToken) = HAConfig.loadConfig(getApplication())!!
            app.initializeHomeAssistant(haUrl, haToken)
            initializeGemini()
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to connect to HA: ${e.message}")
        }
    }
}
```

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/MainViewModel.kt`

**Changes**:

```kotlin
// Add public method to expose session state
fun isSessionActive(): Boolean = isSessionActive

// Simplify checkConfiguration - remove Firebase/HA config steps
// (Already shown in code above)
```

---

### 4. Layout Files

#### activity_onboarding.xml

**File**: `app/src/main/res/layout/activity_onboarding.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="24dp">

    <!-- Header -->
    <TextView
        android:id="@+id/titleText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Welcome to HA Gemini"
        android:textSize="24sp"
        android:textStyle="bold"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="16dp" />

    <!-- Progress indicator -->
    <TextView
        android:id="@+id/progressText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Step 1 of 3"
        android:textSize="14sp"
        app:layout_constraintTop_toBottomOf="@id/titleText"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <ProgressBar
        android:id="@+id/progressBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:progress="33"
        android:max="100"
        app:layout_constraintTop_toBottomOf="@id/progressText"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <!-- Step 1: Firebase -->
    <LinearLayout
        android:id="@+id/step1Container"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:visibility="visible"
        app:layout_constraintTop_toBottomOf="@id/progressBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginTop="32dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Step 1: Firebase Configuration"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginBottom="16dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Import your google-services.json file to enable Gemini AI."
            android:textSize="14sp"
            android:layout_marginBottom="24dp" />

        <Button
            android:id="@+id/firebaseButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Import google-services.json"
            android:textSize="16sp" />
    </LinearLayout>

    <!-- Step 2: Home Assistant -->
    <LinearLayout
        android:id="@+id/step2Container"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/progressBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginTop="32dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Step 2: Home Assistant"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginBottom="16dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Enter your Home Assistant URL and access token."
            android:textSize="14sp"
            android:layout_marginBottom="16dp" />

        <EditText
            android:id="@+id/haUrlInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Home Assistant URL"
            android:inputType="textUri"
            android:layout_marginBottom="8dp" />

        <EditText
            android:id="@+id/haTokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Long-lived access token"
            android:inputType="text"
            android:layout_marginBottom="16dp" />

        <Button
            android:id="@+id/haTestButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Test Connection"
            android:layout_marginBottom="8dp" />

        <Button
            android:id="@+id/haContinueButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Continue"
            android:enabled="false" />
    </LinearLayout>

    <!-- Step 3: Complete -->
    <LinearLayout
        android:id="@+id/step3Container"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/progressBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginTop="32dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Setup Complete!"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginBottom="16dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Your app is now configured and ready to use.\n\nA default profile has been created. You can customize it later in Settings."
            android:textSize="14sp"
            android:layout_marginBottom="24dp" />

        <Button
            android:id="@+id/completeButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Get Started"
            android:textSize="16sp" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

#### activity_settings.xml

**File**: `app/src/main/res/layout/activity_settings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <!-- Profiles Section -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Profiles"
                android:textSize="18sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp"
                android:layout_marginTop="8dp" />

            <TextView
                android:id="@+id/profileSummaryText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Profile management coming in Phase 2"
                android:textSize="14sp"
                android:layout_marginBottom="8dp" />

            <Button
                android:id="@+id/manageProfilesButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Manage Profiles"
                android:enabled="false"
                android:layout_marginBottom="24dp" />

            <!-- Divider -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="#CCCCCC"
                android:layout_marginBottom="16dp" />

            <!-- Home Assistant Section -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Home Assistant"
                android:textSize="18sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="URL:"
                android:textSize="12sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/haUrlText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="http://192.168.1.1:8123"
                android:textSize="14sp"
                android:layout_marginBottom="8dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Token:"
                android:textSize="12sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/haTokenText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="••••••••"
                android:textSize="14sp"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginBottom="24dp">

                <Button
                    android:id="@+id/haEditButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Edit"
                    android:layout_marginEnd="4dp" />

                <Button
                    android:id="@+id/haTestButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Test Connection"
                    android:layout_marginStart="4dp" />
            </LinearLayout>

            <!-- Divider -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="#CCCCCC"
                android:layout_marginBottom="16dp" />

            <!-- Firebase Section -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Firebase"
                android:textSize="18sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Project ID:"
                android:textSize="12sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/firebaseProjectIdText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="my-project-123"
                android:textSize="14sp"
                android:layout_marginBottom="12dp" />

            <Button
                android:id="@+id/firebaseChangeButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Change Firebase Config"
                android:layout_marginBottom="24dp" />

        </LinearLayout>
    </ScrollView>

    <!-- Read-only overlay -->
    <FrameLayout
        android:id="@+id/readOnlyOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#80000000"
        android:visibility="gone"
        android:clickable="true"
        android:focusable="true">

        <TextView
            android:id="@+id/readOnlyMessage"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="Stop chat to modify settings"
            android:textSize="18sp"
            android:textColor="#FFFFFF"
            android:background="#DD000000"
            android:padding="16dp" />
    </FrameLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

#### main_menu.xml

**File**: `app/src/main/res/menu/main_menu.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_settings"
        android:title="Settings"
        android:icon="@android:drawable/ic_menu_preferences"
        app:showAsAction="ifRoom" />
</menu>
```

#### activity_main.xml modifications

**File**: `app/src/main/res/layout/activity_main.xml`

**Remove these sections**:
- `systemPromptContainer` (entire LinearLayout)
- `haConfigContainer` (entire LinearLayout)

**Keep**:
- `statusText`
- `mainButton`
- `toolLogContainer`

The resulting layout should be much simpler.

---

### 5. String Resources

**File**: `app/src/main/res/values/strings.xml`

**Add**:

```xml
<!-- Onboarding -->
<string name="onboarding_title">Welcome to HA Gemini</string>
<string name="onboarding_step_1_title">Step 1: Firebase Configuration</string>
<string name="onboarding_step_1_desc">Import your google-services.json file to enable Gemini AI.</string>
<string name="onboarding_step_2_title">Step 2: Home Assistant</string>
<string name="onboarding_step_2_desc">Enter your Home Assistant URL and access token.</string>
<string name="onboarding_step_3_title">Setup Complete!</string>
<string name="onboarding_step_3_desc">Your app is now configured and ready to use.\n\nA default profile has been created. You can customize it later in Settings.</string>

<!-- Settings -->
<string name="settings_title">Settings</string>
<string name="settings_profiles_section">Profiles</string>
<string name="settings_ha_section">Home Assistant</string>
<string name="settings_firebase_section">Firebase</string>
<string name="settings_read_only_message">Stop chat to modify settings</string>
```

---

## Implementation Tasks (Checklist)

### Task Group 1: Create New Files

- [ ] Create `OnboardingActivity.kt`
- [ ] Create `OnboardingViewModel.kt`
- [ ] Create `SettingsActivity.kt`
- [ ] Create `SettingsViewModel.kt`
- [ ] Create `activity_onboarding.xml`
- [ ] Create `activity_settings.xml`
- [ ] Create `main_menu.xml`
- [ ] Add strings to `strings.xml`

### Task Group 2: Modify Existing Files

- [ ] Update `MainActivity.kt`:
  - [ ] Add `checkConfigurationAndLaunch()` method
  - [ ] Add menu methods (`onCreateOptionsMenu`, `onOptionsItemSelected`)
  - [ ] Remove HA/Firebase config UI code
  - [ ] Remove system prompt UI code
- [ ] Update `MainViewModel.kt`:
  - [ ] Add `isSessionActive()` public method
  - [ ] Simplify `checkConfiguration()` method
- [ ] Update `activity_main.xml`:
  - [ ] Remove `systemPromptContainer`
  - [ ] Remove `haConfigContainer`
  - [ ] Clean up constraints

### Task Group 3: Core Config Helpers (Optional but recommended)

- [ ] Add validation helpers to `HAConfig.kt`:
  - [ ] `isValidUrl(url: String): Boolean`
  - [ ] `isValidToken(token: String): Boolean`
- [ ] Add getter to `FirebaseConfig.kt`:
  - [ ] `getProjectId(context: Context): String?`

### Task Group 4: AndroidManifest Updates

- [ ] Add `OnboardingActivity` to `AndroidManifest.xml`
- [ ] Add `SettingsActivity` to `AndroidManifest.xml`

### Task Group 5: Testing

- [ ] Test first-time flow: Clean install → Onboarding → Main
- [ ] Test Settings access while chat inactive
- [ ] Test Settings read-only mode while chat active
- [ ] Test HA Test Connection button
- [ ] Test Firebase config change (app restarts)
- [ ] Test back button navigation
- [ ] Test settings menu from MainActivity

---

## Testing Checklist

### First-Time User Flow
- [ ] Clean install shows OnboardingActivity
- [ ] Step 1: Firebase file picker works
- [ ] Step 1: Invalid JSON shows error
- [ ] Step 2: HA URL/token validation works
- [ ] Step 2: Test Connection successfully fetches tools
- [ ] Step 2: Test Connection shows error for bad credentials
- [ ] Step 3: "Get Started" navigates to MainActivity
- [ ] MainActivity shows correct ready state

### Settings During Inactive Chat
- [ ] Menu button visible in MainActivity
- [ ] Tapping menu → Settings opens SettingsActivity
- [ ] HA URL/token displayed correctly (token masked)
- [ ] Firebase project ID displayed
- [ ] Edit HA Config button works
- [ ] Test Connection button works
- [ ] Change Firebase Config shows confirmation dialog
- [ ] Back button returns to MainActivity

### Settings During Active Chat
- [ ] Menu accessible during chat
- [ ] Settings shows read-only overlay
- [ ] Read-only message displayed
- [ ] All buttons disabled/blocked
- [ ] Back button still works

### Edge Cases
- [ ] App restart after Firebase config change
- [ ] Bad HA credentials → error handling
- [ ] Network timeout → error handling
- [ ] Onboarding back button handling (shouldn't allow back to empty state)

---

## Notes

### Phase 1 Limitations (By Design)

1. **No profile management yet**: Manage Profiles button is disabled with message "Coming in Phase 2"
2. **No profile dropdown on main screen**: This comes in Phase 3 after profile system is built
3. **System prompt editing removed**: Will return as part of profile editing in Phase 3
4. **HA Edit dialog is placeholder**: Full implementation can be added as needed

### Dependencies on Future Phases

- **Phase 2** will create the Profile data model and ProfileManager
- **Phase 3** will add profile UI and integrate with MainActivity
- Settings screen prepared with "Manage Profiles" section for Phase 2 integration

### Code Reuse Opportunities

- Onboarding HA config step reuses same logic as Settings HA editor
- Both activities can share dialog layouts (create `dialog_ha_edit.xml` if desired)

---

## Success Criteria

Phase 1 is complete when:

✅ First-time users complete onboarding successfully
✅ MainActivity no longer has configuration UI
✅ Settings screen accessible via menu
✅ Settings shows read-only mode during active chat
✅ HA Test Connection works
✅ Firebase config change restarts app
✅ All tests pass
✅ Code is clean and documented

**Estimated effort**: 8-12 hours of development + 2-4 hours testing

---

## Next Phase Preview

**Phase 2** will focus on:
- Profile data model (`Profile.kt`)
- ProfileManager for CRUD operations
- Unit tests for ProfileManager
- Auto-create default profile on first run
- Enable "Manage Profiles" button in Settings

This Phase 1 implementation creates the foundation for a clean, maintainable settings system that will support the profile management features in subsequent phases.
