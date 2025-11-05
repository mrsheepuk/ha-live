package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.HAConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var toolLogText: TextView

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

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        toolLogText = findViewById(R.id.toolLogText)

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
                mainButton.visibility = View.GONE
                statusText.text = "Loading..."
            }
            UiState.FirebaseConfigNeeded -> {
                // Should not reach here - handled by OnboardingActivity
                mainButton.visibility = View.GONE
                statusText.text = "Please complete onboarding"
            }
            UiState.HAConfigNeeded -> {
                // Should not reach here - handled by OnboardingActivity
                mainButton.visibility = View.GONE
                statusText.text = "Please complete onboarding"
            }
            UiState.ReadyToTalk -> {
                mainButton.visibility = View.VISIBLE
                mainButton.text = "Start Chat"
                statusText.text = "Ready to chat"
                mainButton.setOnTouchListener(null) // Remove touch listener
                mainButton.setOnClickListener(chatButtonClickListener)
            }
            UiState.ChatActive -> {
                mainButton.visibility = View.VISIBLE
                mainButton.text = "Stop Chat"
                statusText.text = "Chat active - listening..."
                // Listener is already active
            }
            UiState.Listening -> {
                mainButton.visibility = View.VISIBLE
                mainButton.text = "Stop Chat"
                statusText.text = "Listening..."
                // Listener is already active
            }
            UiState.ExecutingAction -> {
                mainButton.visibility = View.VISIBLE
                mainButton.text = "Stop Chat"
                statusText.text = "Executing action..."
                // Keep button active but show execution status
            }
            is UiState.Error -> {
                mainButton.visibility = View.GONE
                statusText.text = state.message
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
