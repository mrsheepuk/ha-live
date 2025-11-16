package uk.co.mrsheep.halive.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.HAConfig
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.LogEntry

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var toolbar: Toolbar
    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var toolLogText: TextView
    private lateinit var retryButton: Button
    private lateinit var audioVisualizer: AudioVisualizerView
    private lateinit var wakeWordChip: MaterialButton
    private lateinit var logHeaderContainer: LinearLayout
    private lateinit var logChevronIcon: ImageView
    private lateinit var logContentScroll: ScrollView
    private lateinit var logHeaderText: TextView

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

    override fun onResume() {
        super.onResume()
        // Only start wake word if we're in ready state AND have permission
        if (viewModel.uiState.value == UiState.ReadyToTalk) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.onActivityResume()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Always stop listening when app is paused (foreground-only behavior)
        viewModel.onActivityPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check configuration first
        checkConfigurationAndLaunch()

        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        toolLogText = findViewById(R.id.toolLogText)
        retryButton = findViewById(R.id.retryButton)
        audioVisualizer = findViewById(R.id.audioVisualizer)
        wakeWordChip = findViewById(R.id.wakeWordChip)
        logHeaderContainer = findViewById(R.id.logHeaderContainer)
        logChevronIcon = findViewById(R.id.logChevronIcon)
        logContentScroll = findViewById(R.id.logContentScroll)
        logHeaderText = findViewById(R.id.logHeaderText)

        retryButton.setOnClickListener {
            viewModel.retryInitialization()
        }

        // Observe wake word state from ViewModel and update chip appearance
        lifecycleScope.launch {
            viewModel.wakeWordEnabled.collect { enabled ->
                updateWakeWordChipAppearance(enabled)
            }
        }

        // Handle user clicking the wake word chip
        wakeWordChip.setOnClickListener {
            viewModel.toggleWakeWord(!viewModel.wakeWordEnabled.value)
        }

        // Add click listener for log header collapse/expand
        logHeaderContainer.setOnClickListener {
            viewModel.toggleLogExpanded()
        }

        // Observe log expanded state from ViewModel
        lifecycleScope.launch {
            viewModel.logExpanded.collect { isExpanded ->
                updateLogExpandedState(isExpanded)
            }
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
            R.id.action_profiles -> {
                showProfileSelectorDialog()
                true
            }
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

    private fun showProfileSelectorDialog() {
        val profiles = viewModel.profiles.value
        if (profiles.isEmpty()) {
            Toast.makeText(this, "No profiles available", Toast.LENGTH_SHORT).show()
            return
        }

        val profileNames = profiles.map { it.name }.toTypedArray()
        val currentIndex = profiles.indexOfFirst { it.id == viewModel.currentProfileId }

        AlertDialog.Builder(this)
            .setTitle("Switch Profile")
            .setSingleChoiceItems(profileNames, currentIndex) { dialog, which ->
                val selectedProfile = profiles[which]

                // Check if chat is active
                if (viewModel.isSessionActive()) {
                    Toast.makeText(
                        this,
                        R.string.profile_switch_during_chat_error,
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }

                viewModel.switchProfile(selectedProfile.id)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUiForState(state: UiState) {
        when (state) {
            UiState.Loading -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                statusText.text = "Loading..."
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
            UiState.FirebaseConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
            UiState.HAConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
            UiState.Initializing -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                statusText.text = "Initializing..."
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
            UiState.ReadyToTalk -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Start Chat"
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = true
                statusText.text = if (viewModel.wakeWordEnabled.value) {
                    "Listening for wake word..."
                } else {
                    "Ready to chat"
                }
                mainButton.setOnTouchListener(null) // Remove touch listener
                mainButton.setOnClickListener(chatButtonClickListener)
            }
            UiState.ChatActive -> {
                audioVisualizer.setState(VisualizerState.ACTIVE)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Chat active - listening..."
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
                // Listener is already active
            }
            is UiState.ExecutingAction -> {
                audioVisualizer.setState(VisualizerState.EXECUTING)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Executing ${state.tool}..."
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
            is UiState.Error -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                statusText.text = state.message
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
            }
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

    private fun updateToolLogs(logs: List<LogEntry>) {
        if (logs.isEmpty()) {
            toolLogText.text = "Log will appear here..."
            return
        }

        // Format logs in chronological order (oldest first)
        val formattedLogs = logs.joinToString("\n\n") { log ->
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

        // Auto-scroll to bottom to show most recent log entry
        logContentScroll.post {
            logContentScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateWakeWordChipAppearance(enabled: Boolean) {
        if (enabled) {
            // Filled style when enabled
            wakeWordChip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.orange_accent)
            wakeWordChip.strokeWidth = 0
        } else {
            // Outlined style when disabled
            wakeWordChip.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
            wakeWordChip.strokeWidth = (1 * resources.displayMetrics.density).toInt() // 1dp stroke
        }
    }

    private fun updateLogExpandedState(isExpanded: Boolean) {
        if (isExpanded) {
            // Expand log content
            logContentScroll.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(logChevronIcon, "rotation", 0f, 180f).apply {
                duration = 300
                start()
            }
            // Auto-scroll to bottom when opening to show most recent entries
            logContentScroll.post {
                logContentScroll.fullScroll(View.FOCUS_DOWN)
            }
        } else {
            // Collapse log content
            logContentScroll.visibility = View.GONE
            ObjectAnimator.ofFloat(logChevronIcon, "rotation", 180f, 0f).apply {
                duration = 300
                start()
            }
        }
    }
}
