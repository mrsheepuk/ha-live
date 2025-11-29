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
import uk.co.mrsheep.halive.core.WakeWordConfig
import uk.co.mrsheep.halive.core.WakeWordSettings
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.widget.ProgressBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import uk.co.mrsheep.halive.services.WakeWordService
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import uk.co.mrsheep.halive.core.QuickMessage
import uk.co.mrsheep.halive.core.OAuthConfig
import uk.co.mrsheep.halive.HAGeminiApp
import androidx.browser.customtabs.CustomTabsIntent
import android.widget.Toast
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var toolbar: Toolbar

    // HA section
    private lateinit var haUrlText: TextView
    private lateinit var haAuthMethodText: TextView
    private lateinit var haEditButton: Button
    private lateinit var haTestButton: Button

    // Dialog reference for OAuth callback
    private var haEditDialog: AlertDialog? = null

    // Gemini section
    private lateinit var geminiApiKeyText: TextView
    private lateinit var geminiEditButton: Button
    private lateinit var geminiClearButton: Button
    private lateinit var conversationServiceText: TextView
    private lateinit var switchServiceButton: Button

    // Shared key section
    private lateinit var sharedKeySection: LinearLayout
    private lateinit var sharedKeyRadio: RadioButton
    private lateinit var localKeyRadio: RadioButton
    private lateinit var manageSharedKeyButton: Button
    private lateinit var apiKeySourceText: TextView

    // Debug section
    private lateinit var viewCrashLogsButton: Button
    private lateinit var shareCrashLogsButton: Button

    // Wake word section
    private lateinit var wakeWordStatusText: TextView
    private lateinit var wakeWordDetailsText: TextView
    private lateinit var wakeWordConfigButton: Button

    // Quick messages section
    private lateinit var quickMessagesRecyclerView: RecyclerView
    private lateinit var addQuickMessageButton: Button
    private lateinit var quickMessagesAdapter: QuickMessagesAdapter

    // Sync/Cache section
    private lateinit var syncCacheSection: LinearLayout
    private lateinit var lastSyncText: TextView
    private lateinit var forceSyncButton: Button
    private lateinit var clearCacheButton: Button
    private lateinit var syncProgressBar: ProgressBar

    // Test mode service
    private var testWakeWordService: WakeWordService? = null

    // Read-only overlay
    private lateinit var readOnlyOverlay: View
    private lateinit var readOnlyMessage: TextView

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
        viewModel.loadQuickMessages()
    }

    override fun onResume() {
        super.onResume()
        // Check if we returned from OAuth with result
        checkPendingOAuthResult()
    }

    private fun checkPendingOAuthResult() {
        val (code, state, error) = OAuthCallbackActivity.getPendingResult(this)

        if (code != null) {
            // Dismiss the edit dialog if open
            haEditDialog?.dismiss()
            haEditDialog = null

            viewModel.handleOAuthCallback(code, state)
        } else if (error != null) {
            viewModel.handleOAuthError(error)
        }
    }

    private fun initViews() {
        // HA section
        haUrlText = findViewById(R.id.haUrlText)
        haAuthMethodText = findViewById(R.id.haAuthMethodText)
        haEditButton = findViewById(R.id.haEditButton)
        haTestButton = findViewById(R.id.haTestButton)

        haEditButton.setOnClickListener {
            showHAEditDialog()
        }

        haTestButton.setOnClickListener {
            viewModel.testHAConnection()
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

        // Shared key section
        sharedKeySection = findViewById(R.id.sharedKeySection)
        sharedKeyRadio = findViewById(R.id.sharedKeyRadio)
        localKeyRadio = findViewById(R.id.localKeyRadio)
        manageSharedKeyButton = findViewById(R.id.manageSharedKeyButton)
        apiKeySourceText = findViewById(R.id.apiKeySourceText)

        sharedKeyRadio.setOnClickListener {
            viewModel.setUseSharedKey(true)
        }

        localKeyRadio.setOnClickListener {
            viewModel.setUseSharedKey(false)
        }

        manageSharedKeyButton.setOnClickListener {
            showManageSharedKeyDialog()
        }

        // Service switching removed - only Gemini Direct API is supported

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
        wakeWordDetailsText = findViewById(R.id.wakeWordDetailsText)
        wakeWordConfigButton = findViewById(R.id.wakeWordConfigButton)

        wakeWordConfigButton.setOnClickListener {
            showWakeWordConfigDialog()
        }

        // Quick messages section
        quickMessagesRecyclerView = findViewById(R.id.quickMessagesRecyclerView)
        addQuickMessageButton = findViewById(R.id.addQuickMessageButton)

        quickMessagesAdapter = QuickMessagesAdapter(
            onEditClick = { qm -> showAddEditQuickMessageDialog(qm) },
            onDeleteClick = { qm -> showDeleteQuickMessageDialog(qm) },
            onToggleClick = { qm -> viewModel.toggleQuickMessageEnabled(qm.id) }
        )

        quickMessagesRecyclerView.adapter = quickMessagesAdapter
        quickMessagesRecyclerView.layoutManager = LinearLayoutManager(this)

        addQuickMessageButton.setOnClickListener {
            showAddEditQuickMessageDialog(null)
        }

        // Sync/Cache section
        syncCacheSection = findViewById(R.id.syncCacheSection)
        lastSyncText = findViewById(R.id.lastSyncText)
        forceSyncButton = findViewById(R.id.forceSyncButton)
        clearCacheButton = findViewById(R.id.clearCacheButton)
        syncProgressBar = findViewById(R.id.syncProgressBar)

        setupCacheSection()

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

        lifecycleScope.launch {
            viewModel.quickMessages.collect { messages ->
                quickMessagesAdapter.submitList(messages)
            }
        }
    }

    private fun updateUIForState(state: SettingsState) {
        when (state) {
            is SettingsState.Loaded -> {
                // Update UI with current config
                haUrlText.text = state.haUrl
                haAuthMethodText.text = state.authMethod
                geminiApiKeyText.text = if (state.geminiApiKey != "Not configured") "••••••••" else "Not configured"

                // Update conversation service display
                conversationServiceText.text = state.conversationService

                // Switch button hidden - only Gemini Direct API is supported
                switchServiceButton.visibility = View.GONE

                // Update shared key UI
                if (state.hasSharedKey) {
                    sharedKeySection.visibility = View.VISIBLE
                    sharedKeyRadio.isChecked = state.isUsingSharedKey
                    localKeyRadio.isChecked = !state.isUsingSharedKey
                    apiKeySourceText.text = if (state.isUsingSharedKey) "Using shared key" else "Using local key"
                    manageSharedKeyButton.visibility = if (state.sharedConfigAvailable) View.VISIBLE else View.GONE
                } else {
                    sharedKeySection.visibility = View.GONE
                    apiKeySourceText.text = "Using local key"
                    manageSharedKeyButton.visibility = if (state.sharedConfigAvailable) View.VISIBLE else View.GONE
                }

                // Update wake word display
                wakeWordStatusText.text = if (state.wakeWordEnabled) "Enabled" else "Disabled"
                wakeWordDetailsText.text = state.wakeWordDetails
                wakeWordConfigButton.isEnabled = !state.isReadOnly

                // Enable/disable buttons based on read-only state
                haEditButton.isEnabled = !state.isReadOnly
                haTestButton.isEnabled = !state.isReadOnly
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_ha_config, null)

        val oauthUrlInput = dialogView.findViewById<android.widget.EditText>(R.id.oauthUrlInput)
        val oauthLoginButton = dialogView.findViewById<Button>(R.id.oauthLoginButton)

        // Pre-fill with current URL if available
        val currentUrl = uk.co.mrsheep.halive.core.SecureTokenStorage(this).getHaUrl()
        oauthUrlInput.setText(currentUrl ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect to Home Assistant")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        // Store dialog reference for OAuth callback
        haEditDialog = dialog

        // OAuth login button handler
        oauthLoginButton.setOnClickListener {
            val url = oauthUrlInput.text.toString().trim()
            if (url.isBlank()) {
                oauthUrlInput.error = "URL is required"
                return@setOnClickListener
            }

            val authUrl = viewModel.startOAuthFlow(url)

            // Set source so callback knows where to return
            OAuthCallbackActivity.setSourceActivity(this, OAuthCallbackActivity.SOURCE_SETTINGS)

            // Open browser for OAuth
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse(authUrl))
            } catch (e: Exception) {
                // Fallback to regular browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                startActivity(intent)
            }

            // Keep dialog open - it will be dismissed on OAuth callback
        }

        dialog.setOnDismissListener {
            haEditDialog = null
        }

        dialog.show()
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
            .setMessage("Remove Gemini API key from this device?")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.clearGeminiApiKey()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageSharedKeyDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gemini_config, null)
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.geminiApiKeyInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Manage Shared Gemini Key")
            .setMessage("This key will be available to all devices in your household.")
            .setView(dialogView)
            .setPositiveButton("Save to Shared") { _, _ ->
                val apiKey = apiKeyInput.text.toString().trim()
                if (apiKey.isNotBlank()) {
                    viewModel.setSharedGeminiKey(apiKey)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showWakeWordConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wake_word_config, null)

        // Load current settings
        val currentSettings = WakeWordConfig.getSettings(this)

        // Threshold seekbar
        val thresholdSeekBar = dialogView.findViewById<SeekBar>(R.id.thresholdSeekBar)
        val thresholdValue = dialogView.findViewById<TextView>(R.id.thresholdValue)

        // Convert 0.3-0.8 range to 0-100 seekbar
        val thresholdToProgress = { threshold: Float -> ((threshold - 0.3f) / 0.5f * 100f).toInt() }
        val progressToThreshold = { progress: Int -> 0.3f + (progress / 100f) * 0.5f }

        thresholdSeekBar.progress = thresholdToProgress(currentSettings.threshold)
        thresholdValue.text = String.format("%.2f", currentSettings.threshold)

        // Note: SeekBar listener is set in setupTestMode() to handle both
        // threshold value updates and test mode marker positioning

        // Thread count spinner
        val threadCountSpinner = dialogView.findViewById<Spinner>(R.id.threadCountSpinner)
        val threadOptions = listOf("1", "2", "4", "8")
        threadCountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, threadOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentThreadCount = currentSettings.threadCount
        threadCountSpinner.setSelection(threadOptions.indexOf(currentThreadCount.toString()).coerceAtLeast(0))

        // Execution mode radio buttons
        val radioExecutionSequential = dialogView.findViewById<RadioButton>(R.id.radioExecutionSequential)
        val radioExecutionParallel = dialogView.findViewById<RadioButton>(R.id.radioExecutionParallel)
        val currentExecutionMode = currentSettings.executionMode
        when (currentExecutionMode) {
            ExecutionMode.SEQUENTIAL -> radioExecutionSequential.isChecked = true
            ExecutionMode.PARALLEL -> radioExecutionParallel.isChecked = true
        }

        // Optimization level radio buttons
        val radioOptNone = dialogView.findViewById<RadioButton>(R.id.radioOptNone)
        val radioOptBasic = dialogView.findViewById<RadioButton>(R.id.radioOptBasic)
        val radioOptExtended = dialogView.findViewById<RadioButton>(R.id.radioOptExtended)
        val radioOptAll = dialogView.findViewById<RadioButton>(R.id.radioOptAll)
        val currentOptLevel = currentSettings.optimizationLevel
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
                val threshold = progressToThreshold(thresholdSeekBar.progress)
                val threadCount = threadOptions[threadCountSpinner.selectedItemPosition].toInt()

                val executionMode = if (radioExecutionParallel.isChecked) {
                    ExecutionMode.PARALLEL
                } else {
                    ExecutionMode.SEQUENTIAL
                }

                val optimizationLevel = when {
                    radioOptNone.isChecked -> OptimizationLevel.NO_OPT
                    radioOptExtended.isChecked -> OptimizationLevel.EXTENDED_OPT
                    radioOptAll.isChecked -> OptimizationLevel.ALL_OPT
                    else -> OptimizationLevel.BASIC_OPT
                }

                val newSettings = WakeWordSettings(
                    enabled = currentSettings.enabled,
                    threshold = threshold,
                    threadCount = threadCount,
                    executionMode = executionMode,
                    optimizationLevel = optimizationLevel
                )

                viewModel.saveWakeWordSettings(newSettings)
                dialog.dismiss()
            }
        }

        dialog.show()

        // Set up test mode UI
        setupTestMode(dialogView, dialog, currentSettings)
    }

    private fun setupTestMode(dialogView: View, dialog: AlertDialog, settings: WakeWordSettings) {
        val testCurrentScore = dialogView.findViewById<TextView>(R.id.testCurrentScore)
        val testPeakScore = dialogView.findViewById<TextView>(R.id.testPeakScore)
        val testScoreProgress = dialogView.findViewById<ProgressBar>(R.id.testScoreProgress)
        val testThresholdMarker = dialogView.findViewById<View>(R.id.testThresholdMarker)
        val testStatusText = dialogView.findViewById<TextView>(R.id.testStatusText)
        val testButton = dialogView.findViewById<Button>(R.id.testButton)
        val thresholdSeekBar = dialogView.findViewById<SeekBar>(R.id.thresholdSeekBar)
        val testSection = dialogView.findViewById<LinearLayout>(R.id.testSection)

        var peakScore = 0.0f
        var isTestActive = false
        var triggerCount = 0
        var lastTriggerTime = 0L

        // Helper to convert threshold (0.3-0.8) to progress bar position (0-100)
        val thresholdToProgress = { threshold: Float -> ((threshold - 0.3f) / 0.5f * 100f).toInt() }
        val progressToThreshold = { progress: Int -> 0.3f + (progress / 100f) * 0.5f }

        // Update threshold marker position when seekbar changes
        val updateThresholdMarker = {
            val threshold = progressToThreshold(thresholdSeekBar.progress)
            val markerPosition = thresholdToProgress(threshold)
            val layoutParams = testThresholdMarker.layoutParams as RelativeLayout.LayoutParams
            layoutParams.leftMargin = (testScoreProgress.width * markerPosition / 100f).toInt()
            testThresholdMarker.layoutParams = layoutParams
        }

        // Update marker when seekbar changes
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Update threshold value display (already handled by existing code)
                val threshold = progressToThreshold(progress)
                dialogView.findViewById<TextView>(R.id.thresholdValue).text = String.format("%.2f", threshold)
                // Update marker position
                updateThresholdMarker()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Position marker initially (post to ensure layout is complete)
        testScoreProgress.post { updateThresholdMarker() }

        testButton.setOnClickListener {
            if (!isTestActive) {
                // Check microphone permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    testStatusText.text = "Microphone permission required"
                    return@setOnClickListener
                }

                // Start test
                try {
                    testWakeWordService = WakeWordService(this) {
                        // No-op for wake word detection - test mode only
                    }

                    peakScore = 0.0f
                    triggerCount = 0
                    lastTriggerTime = 0L
                    testCurrentScore.text = "0.00"
                    testPeakScore.text = "0.00"
                    testScoreProgress.progress = 0

                    // Start test mode
                    testWakeWordService?.startTestMode { score ->
                        // Update UI with live score
                        testCurrentScore.text = String.format("%.2f", score)

                        // Update peak
                        if (score > peakScore) {
                            peakScore = score
                            testPeakScore.text = String.format("%.2f", peakScore)
                        }

                        // Update progress bar (convert 0.0-1.0 to 0-100)
                        testScoreProgress.progress = (score * 100).toInt()

                        // Check if score crosses threshold
                        val currentThreshold = progressToThreshold(thresholdSeekBar.progress)
                        val currentTime = System.currentTimeMillis()

                        if (score > currentThreshold) {
                            // Trigger detected!
                            if (currentTime - lastTriggerTime > 500) {
                                // Only increment if it's been >500ms since last trigger (debounce)
                                triggerCount++
                                lastTriggerTime = currentTime
                            }
                            // Flash green background
                            testSection.setBackgroundColor(0x4000FF00) // Semi-transparent green
                            testStatusText.text = "✓ DETECTED! ($triggerCount detections)"
                        } else if (currentTime - lastTriggerTime < 2000) {
                            // Keep showing trigger message for 2 seconds
                            testSection.setBackgroundColor(0x4000FF00)
                            testStatusText.text = "✓ DETECTED! ($triggerCount detections)"
                        } else {
                            // Back to normal
                            testSection.setBackgroundColor(0x00000000) // Transparent
                            if (triggerCount > 0) {
                                testStatusText.text = "Listening... ($triggerCount detections)"
                            } else {
                                testStatusText.text = "Listening..."
                            }
                        }
                    }

                    isTestActive = true
                    testButton.text = "Stop Test"
                    testStatusText.text = "Listening..."

                } catch (e: Exception) {
                    testStatusText.text = "Error: ${e.message}"
                }
            } else {
                // Stop test
                testWakeWordService?.stopTestMode()
                testWakeWordService?.destroy()
                testWakeWordService = null

                isTestActive = false
                testButton.text = "Start Test"
                testStatusText.text = "Tap Start to begin testing"
                testSection.setBackgroundColor(0x00000000) // Transparent
            }
        }

        // Clean up test on dialog dismiss
        dialog.setOnDismissListener {
            if (isTestActive) {
                testWakeWordService?.stopTestMode()
                testWakeWordService?.destroy()
                testWakeWordService = null
            }
        }
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

    private fun showAddEditQuickMessageDialog(quickMessage: QuickMessage?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_quick_message_config, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.quickMessageLabelInput)
        val messageInput = dialogView.findViewById<EditText>(R.id.quickMessageInput)

        val isEdit = quickMessage != null
        val title = if (isEdit) "Edit Quick Message" else "Add Quick Message"

        if (isEdit) {
            labelInput.setText(quickMessage!!.label)
            messageInput.setText(quickMessage.message)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val label = labelInput.text.toString().trim()
                val message = messageInput.text.toString().trim()

                if (label.isBlank()) {
                    labelInput.error = "Label is required"
                    return@setOnClickListener
                }
                if (message.isBlank()) {
                    messageInput.error = "Message is required"
                    return@setOnClickListener
                }

                if (isEdit) {
                    val updated = quickMessage!!.copy(label = label, message = message)
                    viewModel.updateQuickMessage(updated)
                } else {
                    val newQm = QuickMessage(
                        id = UUID.randomUUID().toString(),
                        label = label,
                        message = message,
                        enabled = true
                    )
                    viewModel.addQuickMessage(newQm)
                }

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showDeleteQuickMessageDialog(quickMessage: QuickMessage) {
        AlertDialog.Builder(this)
            .setTitle("Delete Quick Message?")
            .setMessage("Delete \"${quickMessage.label}\"?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteQuickMessage(quickMessage.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupCacheSection() {
        val app = application as HAGeminiApp
        val cache = app.sharedConfigCache

        if (cache != null && cache.isIntegrationInstalled()) {
            syncCacheSection.visibility = View.VISIBLE

            val lastFetch = cache.getLastFetchTime()
            if (lastFetch > 0) {
                lastSyncText.text = uk.co.mrsheep.halive.core.TimeFormatter.formatTime(lastFetch)
            } else {
                lastSyncText.text = "Never synced"
            }

            clearCacheButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear Cache")
                    .setMessage("This will clear cached shared profiles. They will be re-fetched from Home Assistant on next launch.")
                    .setPositiveButton("Clear") { _, _ ->
                        cache.clearProfileCache()
                        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                        lastSyncText.text = "Never synced"
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            forceSyncButton.setOnClickListener {
                forceSync()
            }
        } else {
            syncCacheSection.visibility = View.GONE
        }
    }

    private fun forceSync() {
        lifecycleScope.launch {
            forceSyncButton.isEnabled = false
            syncProgressBar.visibility = View.VISIBLE

            try {
                val app = application as HAGeminiApp
                app.fetchSharedConfig()
                Toast.makeText(this@SettingsActivity, "Sync complete", Toast.LENGTH_SHORT).show()
                setupCacheSection() // Refresh display
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    uk.co.mrsheep.halive.core.ErrorMessages.forSyncError(e),
                    Toast.LENGTH_LONG
                ).show()
            }

            forceSyncButton.isEnabled = true
            syncProgressBar.visibility = View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// Quick Messages Adapter
private class QuickMessagesAdapter(
    private val onEditClick: (QuickMessage) -> Unit,
    private val onDeleteClick: (QuickMessage) -> Unit,
    private val onToggleClick: (QuickMessage) -> Unit
) : RecyclerView.Adapter<QuickMessagesAdapter.QuickMessageViewHolder>() {

    private val items = mutableListOf<QuickMessage>()

    fun submitList(newItems: List<QuickMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickMessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_quick_message,
            parent,
            false
        )
        return QuickMessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: QuickMessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class QuickMessageViewHolder(itemView: android.view.View) : ViewHolder(itemView) {
        private val labelText = itemView.findViewById<TextView>(R.id.quickMessageLabel)
        private val messagePreview = itemView.findViewById<TextView>(R.id.quickMessagePreview)
        private val enableSwitch = itemView.findViewById<SwitchCompat>(R.id.quickMessageEnableSwitch)
        private val editButton = itemView.findViewById<Button>(R.id.quickMessageEditButton)
        private val deleteButton = itemView.findViewById<Button>(R.id.quickMessageDeleteButton)

        fun bind(qm: QuickMessage) {
            labelText.text = qm.label
            // Show preview of message (truncated if too long)
            val preview = if (qm.message.length > 50) {
                qm.message.substring(0, 50) + "..."
            } else {
                qm.message
            }
            messagePreview.text = preview

            enableSwitch.isChecked = qm.enabled
            enableSwitch.setOnCheckedChangeListener { _, _ ->
                onToggleClick(qm)
            }

            editButton.setOnClickListener {
                onEditClick(qm)
            }

            deleteButton.setOnClickListener {
                onDeleteClick(qm)
            }
        }
    }
}

// Settings states
sealed class SettingsState {
    data class Loaded(
        val haUrl: String,
        val authMethod: String,
        val profileCount: Int,
        val isReadOnly: Boolean,
        val geminiApiKey: String,
        val conversationService: String,
        val canChooseService: Boolean,
        val wakeWordEnabled: Boolean,
        val wakeWordDetails: String,
        val wakeWordThreshold: Float,
        val hasSharedKey: Boolean,
        val isUsingSharedKey: Boolean,
        val sharedConfigAvailable: Boolean
    ) : SettingsState()
    object TestingConnection : SettingsState()
    data class ConnectionSuccess(val message: String) : SettingsState()
    data class ConnectionFailed(val error: String) : SettingsState()
}
