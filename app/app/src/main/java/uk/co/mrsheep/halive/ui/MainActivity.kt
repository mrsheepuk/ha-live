package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.ui.AudioVisualizerView
import uk.co.mrsheep.halive.ui.VisualizerState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var toolbar: Toolbar
    private lateinit var profileSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var toolLogText: TextView
    private lateinit var retryButton: Button
    private lateinit var audioVisualizer: AudioVisualizerView

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

    // Activity Result Launcher for the file picker
    private val selectConfigFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.saveFirebaseConfigFile(it)
        }
    }

    // Activity Result Launcher for audio permission
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.onChatButtonClicked()
        } else {
            // Permission denied, show error state
            viewModel.onPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check configuration first
        checkConfigurationAndLaunch()

        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        profileSpinner = findViewById(R.id.profileSpinner)
        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        toolLogText = findViewById(R.id.toolLogText)
        retryButton = findViewById(R.id.retryButton)
        audioVisualizer = findViewById(R.id.audioVisualizer)

        retryButton.setOnClickListener {
            viewModel.retryInitialization()
        }

        // Observe the UI state from the ViewModel
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUiForState(state)
            }
        }

        // Observe tool logs from the ViewModel
        lifecycleScope.launch {
            viewModel.toolLogs.collect { logs ->
                updateToolLogs(logs)
            }
        }

        // Observe profiles and update spinner
        lifecycleScope.launch {
            viewModel.profiles.collect { profiles ->
                updateProfileSpinner(profiles)
            }
        }

        // Observe auto-start intent
        lifecycleScope.launch {
            viewModel.shouldAttemptAutoStart.collect { shouldAutoStart ->
                if (shouldAutoStart) {
                    handleAutoStart()
                    viewModel.consumeAutoStartIntent()
                }
            }
        }
    }

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

    private fun updateUiForState(state: UiState) {
        when (state) {
            UiState.Loading -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.visibility = View.GONE
                retryButton.visibility = View.GONE
                statusText.text = "Loading..."
            }
            UiState.FirebaseConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.visibility = View.GONE
                statusText.text = "Please complete onboarding"
            }
            UiState.HAConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.visibility = View.GONE
                statusText.text = "Please complete onboarding"
            }
            UiState.Initializing -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                profileSpinner.isEnabled = false
                mainButton.visibility = View.GONE
                retryButton.visibility = View.GONE
                statusText.text = "Initializing..."
            }
            UiState.ReadyToTalk -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                profileSpinner.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Start Chat"
                statusText.text = "Ready to chat"
                mainButton.setOnTouchListener(null) // Remove touch listener
                mainButton.setOnClickListener(chatButtonClickListener)
            }
            UiState.ChatActive -> {
                audioVisualizer.setState(VisualizerState.ACTIVE)
                profileSpinner.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Chat active - listening..."
                // Listener is already active
            }
            is UiState.ExecutingAction -> {
                audioVisualizer.setState(VisualizerState.EXECUTING)
                profileSpinner.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Executing ${state.tool}..."
            }
            is UiState.Error -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                statusText.text = state.message
            }
        }
    }

    private fun updateProfileSpinner(profiles: List<Profile>) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            profiles.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter

        // Select the active profile
        val activeIndex = profiles.indexOfFirst { it.id == viewModel.currentProfileId }

        // Handle selection changes
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedProfile = profiles[position]

                // Ignore if this is the currently selected profile (happens on initial setup)
                if (selectedProfile.id == viewModel.currentProfileId) {
                    return
                }

                // Check if chat is active before switching
                if (viewModel.isSessionActive()) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.profile_switch_during_chat_error,
                        Toast.LENGTH_SHORT
                    ).show()
                    // Revert to the current profile
                    val currentIndex = profiles.indexOfFirst { it.id == viewModel.currentProfileId }
                    if (currentIndex >= 0) {
                        profileSpinner.setSelection(currentIndex)
                    }
                    return
                }

                viewModel.switchProfile(selectedProfile.id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set selection after listener is attached
        if (activeIndex >= 0) {
            profileSpinner.setSelection(activeIndex)
        }
    }

    // Chat button click listener for toggle functionality
    private val chatButtonClickListener = View.OnClickListener {
        // Check audio permission before starting
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onChatButtonClicked()
        } else {
            // Request permission
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleAutoStart() {
        // Ensure we're in the ready state
        if (viewModel.uiState.value != UiState.ReadyToTalk) {
            return
        }

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Small delay to ensure UI is fully settled
            lifecycleScope.launch {
                kotlinx.coroutines.delay(300)
                viewModel.onChatButtonClicked()
            }
        } else {
            // Don't request permission automatically - just inform user
            Toast.makeText(
                this,
                "Auto-start requires microphone permission. Please grant permission and restart the app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateToolLogs(logs: List<ToolCallLog>) {
        if (logs.isEmpty()) {
            toolLogText.text = "Tool call log will appear here..."
            return
        }

        // Format logs in reverse order (newest first)
        val formattedLogs = logs.reversed().joinToString("\n\n") { log ->
            val statusIcon = if (log.success) "✓" else "✗"
            val statusColor = if (log.success) "SUCCESS" else "FAILED"

            """
            |[$statusIcon] ${log.timestamp} - $statusColor
            |Tool: ${log.toolName}
            |Params: ${log.parameters}
            |Result: ${log.result}
            """.trimMargin()
        }

        toolLogText.text = formattedLogs
    }
}
