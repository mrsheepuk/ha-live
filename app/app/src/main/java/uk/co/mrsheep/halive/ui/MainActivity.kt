package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var haUrlInput: EditText
    private lateinit var haTokenInput: EditText
    private lateinit var haConfigContainer: View
    private lateinit var toolLogText: TextView

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
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        haUrlInput = findViewById(R.id.haUrlInput)
        haTokenInput = findViewById(R.id.haTokenInput)
        haConfigContainer = findViewById(R.id.haConfigContainer)
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

    private fun updateUiForState(state: UiState) {
        when (state) {
            UiState.Loading -> {
                mainButton.visibility = View.GONE
                haConfigContainer.visibility = View.GONE
                statusText.text = "Loading..."
            }
            UiState.FirebaseConfigNeeded -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Import google-services.json"
                statusText.text = "Firebase configuration needed"
                mainButton.setOnClickListener {
                    // Launch file picker
                    selectConfigFileLauncher.launch(arrayOf("application/json"))
                }
                mainButton.setOnTouchListener(null) // Remove talk listener
            }
            UiState.HAConfigNeeded -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.VISIBLE
                mainButton.text = "Save HA Config"
                statusText.text = "Home Assistant configuration needed"
                mainButton.setOnClickListener {
                    val url = haUrlInput.text.toString()
                    val token = haTokenInput.text.toString()
                    viewModel.saveHAConfig(url, token)
                }
                mainButton.setOnTouchListener(null) // Remove talk listener
            }
            UiState.ReadyToTalk -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Start Chat"
                statusText.text = "Ready to chat"
                mainButton.setOnTouchListener(null) // Remove touch listener
                mainButton.setOnClickListener(chatButtonClickListener)
            }
            UiState.ChatActive -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Chat active - listening..."
                // Listener is already active
            }
            UiState.Listening -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Listening..."
                // Listener is already active
            }
            UiState.ExecutingAction -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Executing action..."
                // Keep button active but show execution status
            }
            is UiState.Error -> {
                mainButton.visibility = View.GONE
                haConfigContainer.visibility = View.GONE
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
