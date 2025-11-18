package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
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
import uk.co.mrsheep.halive.core.ExecutionMode
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.OptimizationLevel
import uk.co.mrsheep.halive.core.PerformanceMode
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var toolbar: Toolbar

    // HA section
    private lateinit var haUrlText: TextView
    private lateinit var haTokenText: TextView
    private lateinit var haEditButton: Button
    private lateinit var haTestButton: Button

    // Firebase section
    private lateinit var firebaseProjectIdText: TextView
    private lateinit var firebaseChangeButton: Button

    // Gemini section
    private lateinit var geminiApiKeyText: TextView
    private lateinit var geminiEditButton: Button
    private lateinit var geminiClearButton: Button
    private lateinit var conversationServiceText: TextView
    private lateinit var switchServiceButton: Button

    // Debug section
    private lateinit var viewCrashLogsButton: Button
    private lateinit var shareCrashLogsButton: Button

    // Wake word section
    private lateinit var wakeWordStatusText: TextView
    private lateinit var wakeWordModeText: TextView
    private lateinit var wakeWordThresholdText: TextView
    private lateinit var wakeWordConfigButton: Button

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

        // Gemini section
        geminiApiKeyText = findViewById(R.id.geminiApiKeyText)
        geminiEditButton = findViewById(R.id.geminiEditButton)
        geminiClearButton = findViewById(R.id.geminiClearButton)
        conversationServiceText = findViewById(R.id.conversationServiceText)
        switchServiceButton = findViewById(R.id.switchServiceButton)

        geminiEditButton.setOnClickListener {
            showGeminiEditDialog()
        }

        geminiClearButton.setOnClickListener {
            showGeminiClearDialog()
        }

        switchServiceButton.setOnClickListener {
            viewModel.switchConversationService()
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

        // Wake word section
        wakeWordStatusText = findViewById(R.id.wakeWordStatusText)
        wakeWordModeText = findViewById(R.id.wakeWordModeText)
        wakeWordThresholdText = findViewById(R.id.wakeWordThresholdText)
        wakeWordConfigButton = findViewById(R.id.wakeWordConfigButton)

        wakeWordConfigButton.setOnClickListener {
            showWakeWordConfigDialog()
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
                geminiApiKeyText.text = if (state.geminiApiKey != "Not configured") "••••••••" else "Not configured"

                // Update conversation service display
                conversationServiceText.text = state.conversationService

                // Show/hide switch button based on whether both services are available
                if (state.canChooseService) {
                    switchServiceButton.visibility = View.VISIBLE
                    val otherService = if (state.conversationService == "Gemini Direct API") {
                        "Firebase SDK"
                    } else {
                        "Gemini Direct API"
                    }
                    switchServiceButton.text = "Switch to $otherService"
                } else {
                    switchServiceButton.visibility = View.GONE
                }

                // Update wake word display
                wakeWordStatusText.text = if (state.wakeWordEnabled) "Enabled" else "Disabled"
                wakeWordModeText.text = state.wakeWordMode
                wakeWordThresholdText.text = String.format("%.2f", state.wakeWordThreshold)
                wakeWordConfigButton.isEnabled = !state.isReadOnly

                // Enable/disable buttons based on read-only state
                haEditButton.isEnabled = !state.isReadOnly
                haTestButton.isEnabled = !state.isReadOnly
                firebaseChangeButton.isEnabled = !state.isReadOnly
                geminiEditButton.isEnabled = !state.isReadOnly
                geminiClearButton.isEnabled = !state.isReadOnly
                switchServiceButton.isEnabled = !state.isReadOnly

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

    private fun showGeminiEditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gemini_config, null)
        val apiKeyInput = dialogView.findViewById<android.widget.EditText>(R.id.geminiApiKeyInput)

        // Load current API key from GeminiConfig
        val currentApiKey = GeminiConfig.getApiKey(this) ?: ""
        apiKeyInput.setText(currentApiKey)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Gemini API Key")
            .setView(dialogView)
            .setPositiveButton("Save", null) // Set to null, we'll override below
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val newApiKey = apiKeyInput.text.toString().trim()

                // Validate input
                if (newApiKey.isBlank()) {
                    apiKeyInput.error = "API key is required"
                    return@setOnClickListener
                }

                // Save API key
                viewModel.saveGeminiApiKey(newApiKey)

                // Close dialog
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showGeminiClearDialog() {
        AlertDialog.Builder(this)
            .setTitle("Remove Gemini API Key?")
            .setMessage("Remove Gemini API key? App will use Firebase SDK instead.")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.clearGeminiApiKey()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wake_word_config, null)

        // Load current settings
        val currentSettings = WakeWordConfig.getSettings(this)

        // Performance mode radio buttons
        val radioModeBatterySaver = dialogView.findViewById<RadioButton>(R.id.radioModeBatterySaver)
        val radioModeBalanced = dialogView.findViewById<RadioButton>(R.id.radioModeBalanced)
        val radioModePerformance = dialogView.findViewById<RadioButton>(R.id.radioModePerformance)

        when (currentSettings.performanceMode) {
            PerformanceMode.BATTERY_SAVER -> radioModeBatterySaver.isChecked = true
            PerformanceMode.BALANCED -> radioModeBalanced.isChecked = true
            PerformanceMode.PERFORMANCE -> radioModePerformance.isChecked = true
        }

        // Threshold seekbar
        val thresholdSeekBar = dialogView.findViewById<SeekBar>(R.id.thresholdSeekBar)
        val thresholdValue = dialogView.findViewById<TextView>(R.id.thresholdValue)

        // Convert 0.3-0.8 range to 0-100 seekbar
        val thresholdToProgress = { threshold: Float -> ((threshold - 0.3f) / 0.5f * 100f).toInt() }
        val progressToThreshold = { progress: Int -> 0.3f + (progress / 100f) * 0.5f }

        thresholdSeekBar.progress = thresholdToProgress(currentSettings.threshold)
        thresholdValue.text = String.format("%.2f", currentSettings.threshold)

        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progressToThreshold(progress)
                thresholdValue.text = String.format("%.2f", threshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Advanced settings toggle
        val advancedCheckbox = dialogView.findViewById<CheckBox>(R.id.advancedCheckbox)
        val advancedSettingsLayout = dialogView.findViewById<LinearLayout>(R.id.advancedSettingsLayout)

        advancedCheckbox.isChecked = currentSettings.useAdvancedSettings
        advancedSettingsLayout.visibility = if (currentSettings.useAdvancedSettings) View.VISIBLE else View.GONE

        advancedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            advancedSettingsLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Thread count spinner
        val threadCountSpinner = dialogView.findViewById<Spinner>(R.id.threadCountSpinner)
        val threadOptions = listOf("1", "2", "4", "8")
        threadCountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, threadOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentThreadCount = currentSettings.advancedThreadCount ?: currentSettings.performanceMode.getThreadCount()
        threadCountSpinner.setSelection(threadOptions.indexOf(currentThreadCount.toString()).coerceAtLeast(0))

        // Execution mode radio buttons
        val radioExecutionSequential = dialogView.findViewById<RadioButton>(R.id.radioExecutionSequential)
        val radioExecutionParallel = dialogView.findViewById<RadioButton>(R.id.radioExecutionParallel)
        val currentExecutionMode = currentSettings.advancedExecutionMode ?: currentSettings.performanceMode.getExecutionMode()
        when (currentExecutionMode) {
            ExecutionMode.SEQUENTIAL -> radioExecutionSequential.isChecked = true
            ExecutionMode.PARALLEL -> radioExecutionParallel.isChecked = true
        }

        // Optimization level radio buttons
        val radioOptNone = dialogView.findViewById<RadioButton>(R.id.radioOptNone)
        val radioOptBasic = dialogView.findViewById<RadioButton>(R.id.radioOptBasic)
        val radioOptExtended = dialogView.findViewById<RadioButton>(R.id.radioOptExtended)
        val radioOptAll = dialogView.findViewById<RadioButton>(R.id.radioOptAll)
        val currentOptLevel = currentSettings.advancedOptimizationLevel ?: currentSettings.performanceMode.getOptimizationLevel()
        when (currentOptLevel) {
            OptimizationLevel.NO_OPT -> radioOptNone.isChecked = true
            OptimizationLevel.BASIC_OPT -> radioOptBasic.isChecked = true
            OptimizationLevel.EXTENDED_OPT -> radioOptExtended.isChecked = true
            OptimizationLevel.ALL_OPT -> radioOptAll.isChecked = true
        }

        // Build dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("Configure Wake Word Detection")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                // Collect settings from dialog
                val performanceMode = when {
                    radioModeBatterySaver.isChecked -> PerformanceMode.BATTERY_SAVER
                    radioModePerformance.isChecked -> PerformanceMode.PERFORMANCE
                    else -> PerformanceMode.BALANCED
                }

                val threshold = progressToThreshold(thresholdSeekBar.progress)
                val useAdvanced = advancedCheckbox.isChecked

                val advancedThreadCount = if (useAdvanced) {
                    threadOptions[threadCountSpinner.selectedItemPosition].toInt()
                } else null

                val advancedExecutionMode = if (useAdvanced) {
                    if (radioExecutionParallel.isChecked) ExecutionMode.PARALLEL else ExecutionMode.SEQUENTIAL
                } else null

                val advancedOptLevel = if (useAdvanced) {
                    when {
                        radioOptNone.isChecked -> OptimizationLevel.NO_OPT
                        radioOptExtended.isChecked -> OptimizationLevel.EXTENDED_OPT
                        radioOptAll.isChecked -> OptimizationLevel.ALL_OPT
                        else -> OptimizationLevel.BASIC_OPT
                    }
                } else null

                // Create updated settings
                val newSettings = WakeWordSettings(
                    enabled = currentSettings.enabled,
                    performanceMode = performanceMode,
                    threshold = threshold,
                    useAdvancedSettings = useAdvanced,
                    advancedThreadCount = advancedThreadCount,
                    advancedExecutionMode = advancedExecutionMode,
                    advancedOptimizationLevel = advancedOptLevel
                )

                // Save via ViewModel
                viewModel.saveWakeWordSettings(newSettings)

                dialog.dismiss()
            }
        }

        dialog.show()
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
        val isReadOnly: Boolean,
        val geminiApiKey: String,
        val conversationService: String,
        val canChooseService: Boolean,
        val wakeWordEnabled: Boolean,
        val wakeWordMode: String,
        val wakeWordThreshold: Float
    ) : SettingsState()
    object TestingConnection : SettingsState()
    data class ConnectionSuccess(val message: String) : SettingsState()
    data class ConnectionFailed(val error: String) : SettingsState()
}
