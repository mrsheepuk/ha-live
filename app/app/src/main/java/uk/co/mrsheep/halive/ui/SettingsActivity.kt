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
import java.util.UUID

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
    private lateinit var wakeWordDetailsText: TextView
    private lateinit var wakeWordConfigButton: Button

    // Quick messages section
    private lateinit var quickMessagesRecyclerView: RecyclerView
    private lateinit var addQuickMessageButton: Button
    private lateinit var quickMessagesAdapter: QuickMessagesAdapter

    // Test mode service
    private var testWakeWordService: WakeWordService? = null

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
        viewModel.loadQuickMessages()
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
                wakeWordDetailsText.text = state.wakeWordDetails
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
        val haToken: String,
        val firebaseProjectId: String,
        val profileCount: Int,
        val isReadOnly: Boolean,
        val geminiApiKey: String,
        val conversationService: String,
        val canChooseService: Boolean,
        val wakeWordEnabled: Boolean,
        val wakeWordDetails: String,
        val wakeWordThreshold: Float
    ) : SettingsState()
    object TestingConnection : SettingsState()
    data class ConnectionSuccess(val message: String) : SettingsState()
    data class ConnectionFailed(val error: String) : SettingsState()
}
