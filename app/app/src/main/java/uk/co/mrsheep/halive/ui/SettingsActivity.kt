package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.CrashLogger
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var toolbar: Toolbar

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

    // Debug section
    private lateinit var viewCrashLogsButton: Button
    private lateinit var shareCrashLogsButton: Button

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

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get chat active state from intent
        val isChatActive = intent.getBooleanExtra("isChatActive", false)
        viewModel.isChatActive = isChatActive

        initViews()
        observeState()

        viewModel.loadSettings()
    }

    private fun initViews() {
        // Profile section
        manageProfilesButton = findViewById(R.id.manageProfilesButton)
        profileSummaryText = findViewById(R.id.profileSummaryText)

        manageProfilesButton.setOnClickListener {
            val intent = Intent(this, ProfileManagementActivity::class.java)
            startActivity(intent)
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

        // Debug section
        viewCrashLogsButton = findViewById(R.id.viewCrashLogsButton)
        shareCrashLogsButton = findViewById(R.id.shareCrashLogsButton)

        viewCrashLogsButton.setOnClickListener {
            showCrashLogsDialog()
        }

        shareCrashLogsButton.setOnClickListener {
            shareCrashLogs()
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
                profileSummaryText.text = "${state.profileCount} profile(s) configured"

                // Enable/disable buttons based on read-only state
                manageProfilesButton.isEnabled = !state.isReadOnly
                haEditButton.isEnabled = !state.isReadOnly
                haTestButton.isEnabled = !state.isReadOnly
                firebaseChangeButton.isEnabled = !state.isReadOnly

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
        // Create custom layout with two EditTexts
        val dialogView = layoutInflater.inflate(R.layout.dialog_ha_config, null)
        val urlInput = dialogView.findViewById<android.widget.EditText>(R.id.haUrlInput)
        val tokenInput = dialogView.findViewById<android.widget.EditText>(R.id.haTokenInput)

        // Load current config
        val (currentUrl, currentToken) = uk.co.mrsheep.halive.core.HAConfig.loadConfig(this) ?: Pair("", "")
        urlInput.setText(currentUrl)
        tokenInput.setText(currentToken)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Home Assistant Config")
            .setView(dialogView)
            .setPositiveButton("Test & Save", null) // Set to null, we'll override below
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val newUrl = urlInput.text.toString().trim()
                val newToken = tokenInput.text.toString().trim()

                // Validate inputs
                if (newUrl.isBlank()) {
                    urlInput.error = "URL is required"
                    return@setOnClickListener
                }
                if (newToken.isBlank()) {
                    tokenInput.error = "Token is required"
                    return@setOnClickListener
                }

                // Save temporarily and test
                saveButton.isEnabled = false
                saveButton.text = "Testing..."

                // Save config
                uk.co.mrsheep.halive.core.HAConfig.saveConfig(this, newUrl, newToken)

                // Test connection
                viewModel.testHAConnection()

                // Close dialog
                dialog.dismiss()
            }
        }

        dialog.show()
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

    private fun showCrashLogsDialog() {
        val logContent = CrashLogger.readLog(this)

        // Create a scrollable TextView for the log content
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.text = logContent
        textView.textSize = 10f
        textView.setPadding(16, 16, 16, 16)
        textView.setTextIsSelectable(true)
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("Crash Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Logs") { _, _ ->
                CrashLogger.clearLog(this)
                showSuccessDialog("Crash logs cleared")
            }
            .show()
    }

    private fun shareCrashLogs() {
        try {
            val logFile = CrashLogger.getLogFile(this)

            if (!logFile.exists() || logFile.length() == 0L) {
                showErrorDialog("No crash logs to share")
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "HA Live Crash Logs")
                putExtra(Intent.EXTRA_TEXT, "Attached are the crash logs from HA Live")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share crash logs"))
        } catch (e: Exception) {
            showErrorDialog("Failed to share logs: ${e.message}")
        }
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
