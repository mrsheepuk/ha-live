package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileSource
import uk.co.mrsheep.halive.core.ToolFilterMode
import uk.co.mrsheep.halive.services.ProfileTestManager
import uk.co.mrsheep.halive.ui.adapters.SelectableTool
import uk.co.mrsheep.halive.ui.adapters.ToolSelectionAdapter
import uk.co.mrsheep.halive.ui.adapters.SelectableCamera
import uk.co.mrsheep.halive.ui.adapters.CameraSelectionAdapter
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.services.mcp.McpClientManager

class ProfileEditorActivity : AppCompatActivity(), AppLogger {

    private val viewModel: ProfileEditorViewModel by viewModels()

    // Intent extras
    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_DUPLICATE_FROM_ID = "duplicate_from_id"
    }

    private lateinit var toolbar: Toolbar

    // UI components
    // Profile name (unchanged)
    private lateinit var profileNameLayout: TextInputLayout
    private lateinit var profileNameInput: TextInputEditText
    private lateinit var modelLayout: TextInputLayout
    private lateinit var modelInput: AutoCompleteTextView
    private lateinit var voiceLayout: TextInputLayout
    private lateinit var voiceInput: AutoCompleteTextView

    // System Prompt expansion panel
    private lateinit var systemPromptHeader: View
    private lateinit var systemPromptContent: View
    private lateinit var systemPromptExpandIcon: ImageView
    private lateinit var systemPromptInput: TextInputEditText

    // Personality expansion panel
    private lateinit var personalityHeader: View
    private lateinit var personalityContent: View
    private lateinit var personalityExpandIcon: ImageView
    private lateinit var personalityInput: TextInputEditText

    // Background Info expansion panel
    private lateinit var backgroundInfoHeader: View
    private lateinit var backgroundInfoContent: View
    private lateinit var backgroundInfoExpandIcon: ImageView
    private lateinit var backgroundInfoInput: TextInputEditText

    // Initial Message expansion panel
    private lateinit var initialMessageHeader: View
    private lateinit var initialMessageContent: View
    private lateinit var initialMessageExpandIcon: ImageView
    private lateinit var initialMessageInput: TextInputEditText

    private lateinit var includeLiveContextCheckbox: MaterialCheckBox
    private lateinit var enableTranscriptionCheckbox: MaterialCheckBox
    private lateinit var autoStartChatCheckbox: MaterialCheckBox
    private lateinit var interruptableSwitch: SwitchMaterial
    private lateinit var affectiveDialogSwitch: SwitchMaterial
    private lateinit var proactivitySwitch: SwitchMaterial

    // Tool Filtering UI components
    private lateinit var toolFilterModeGroup: RadioGroup
    private lateinit var radioAllTools: RadioButton
    private lateinit var radioSelectedTools: RadioButton
    private lateinit var toolSelectionContainer: LinearLayout
    private lateinit var toolCacheWarning: TextView
    private lateinit var toolSearchBox: TextInputEditText
    private lateinit var toolCountLabel: TextView
    private lateinit var toolsRecyclerView: RecyclerView
    private lateinit var toolAdapter: ToolSelectionAdapter

    // Tool filtering state
    private var currentToolFilterMode: ToolFilterMode = ToolFilterMode.ALL
    private var selectedToolNames: MutableSet<String> = mutableSetOf()
    private var availableTools: List<SelectableTool> = emptyList()

    // Camera selection UI components
    private lateinit var cameraCountLabel: TextView
    private lateinit var camerasRecyclerView: RecyclerView
    private lateinit var cameraAdapter: CameraSelectionAdapter

    // Camera selection state
    private var selectedCameraEntityIds: MutableSet<String> = mutableSetOf()
    private var availableCameras: List<SelectableCamera> = emptyList()

    // Buttons (unchanged)
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    // Test UI components
    private lateinit var testButton: Button
    private lateinit var testStatusCard: MaterialCardView
    private lateinit var testStatusText: TextView
    private lateinit var testToolLogContainer: LinearLayout
    private lateinit var testToolLogText: TextView
    private var testManager: ProfileTestManager? = null
    private var isTestActive = false
    private val testLogs = mutableListOf<LogEntry>()

    // Sync status UI
    private lateinit var syncStatusSection: LinearLayout
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var syncStatusIcon: ImageView
    private lateinit var syncStatusText: TextView
    private lateinit var retryButton: Button

    // Conflict detection
    private var originalLastModified: String? = null

    // Expansion state
    private var isSystemPromptExpanded = true
    private var isPersonalityExpanded = false
    private var isBackgroundInfoExpanded = false
    private var isInitialMessageExpanded = false

    // Mode tracking
    private var editingProfileId: String? = null
    private var isEditMode: Boolean = false
    private var targetSource: ProfileSource = ProfileSource.LOCAL

    // Permission launcher
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            lifecycleScope.launch { startTest() }
        } else {
            Toast.makeText(this, "Microphone permission required for testing", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_editor)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Check if we're editing or duplicating
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val duplicateFromId = intent.getStringExtra(EXTRA_DUPLICATE_FROM_ID)

        initViews()
        observeState()

        when {
            profileId != null -> {
                // Edit mode
                isEditMode = true
                editingProfileId = profileId
                supportActionBar?.title = getString(R.string.profile_editor_title_edit)
                viewModel.loadProfile(profileId)
            }
            duplicateFromId != null -> {
                // Duplicate mode
                isEditMode = false
                editingProfileId = null
                supportActionBar?.title = getString(R.string.profile_editor_title_create)
                viewModel.loadForDuplicate(duplicateFromId)
            }
            else -> {
                // Create mode
                isEditMode = false
                editingProfileId = null
                supportActionBar?.title = getString(R.string.profile_editor_title_create)
            }
        }
    }

    private fun initViews() {
        profileNameLayout = findViewById(R.id.profileNameLayout)
        profileNameInput = findViewById(R.id.profileNameInput)
        modelLayout = findViewById(R.id.modelLayout)
        modelInput = findViewById(R.id.modelInput)
        voiceLayout = findViewById(R.id.voiceLayout)
        voiceInput = findViewById(R.id.voiceInput)

        // Setup model dropdown
        val modelOptions = arrayOf("gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.5-flash-native-audio-preview-09-2025", "gemini-live-2.5-flash-preview")
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelOptions)
        modelInput.setAdapter(modelAdapter)
        modelInput.setText(modelOptions[0], false) // Set default

        // Setup voice dropdown
//        val voiceOptions = arrayOf("Aoede", "Leda", "Kore", "Puck", "Charon", "Fenrir", "Orus", "Zephyr")
        val voiceOptions = arrayOf(
            "Achernar", "Achird", "Algenib", "Algieba", "Alnilam", "Aoede", "Autonoe",
            "Callirrhoe", "Charon",
            "Despina",
            "Enceladus", "Erinome",
            "Fenrir",
            "Gacrux",
            "Iapetus",
            "Kore",
            "Laomedeia","Leda",
            "Orus",
            "Pulcherrima", "Puck",
            "Rasalgethi",
            "Sadachbia", "Sadaltager", "Schedar", "Sulafat",
            "Umbriel",
            "Vindemiatrix",
            "Zephyr", "Zubenelgenubi"
        )
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, voiceOptions)
        voiceInput.setAdapter(voiceAdapter)
        voiceInput.setText(voiceOptions[0], false) // Set default to Aoede

        systemPromptHeader = findViewById(R.id.systemPromptHeader)
        systemPromptContent = findViewById(R.id.systemPromptContent)
        systemPromptExpandIcon = findViewById(R.id.systemPromptExpandIcon)
        systemPromptInput = findViewById(R.id.systemPromptInput)

        personalityHeader = findViewById(R.id.personalityHeader)
        personalityContent = findViewById(R.id.personalityContent)
        personalityExpandIcon = findViewById(R.id.personalityExpandIcon)
        personalityInput = findViewById(R.id.personalityInput)

        backgroundInfoHeader = findViewById(R.id.backgroundInfoHeader)
        backgroundInfoContent = findViewById(R.id.backgroundInfoContent)
        backgroundInfoExpandIcon = findViewById(R.id.backgroundInfoExpandIcon)
        backgroundInfoInput = findViewById(R.id.backgroundInfoInput)

        initialMessageHeader = findViewById(R.id.initialMessageHeader)
        initialMessageContent = findViewById(R.id.initialMessageContent)
        initialMessageExpandIcon = findViewById(R.id.initialMessageExpandIcon)
        initialMessageInput = findViewById(R.id.initialMessageInput)

        includeLiveContextCheckbox = findViewById(R.id.includeLiveContextCheckbox)
        enableTranscriptionCheckbox = findViewById(R.id.enableTranscriptionCheckbox)
        autoStartChatCheckbox = findViewById(R.id.autoStartChatCheckbox)
        interruptableSwitch = findViewById(R.id.interruptableSwitch)
        affectiveDialogSwitch = findViewById(R.id.affectiveDialogSwitch)
        proactivitySwitch = findViewById(R.id.proactivitySwitch)

        // Tool Filtering UI
        toolFilterModeGroup = findViewById(R.id.toolFilterModeGroup)
        radioAllTools = findViewById(R.id.radioAllTools)
        radioSelectedTools = findViewById(R.id.radioSelectedTools)
        toolSelectionContainer = findViewById(R.id.toolSelectionContainer)
        toolCacheWarning = findViewById(R.id.toolCacheWarning)
        toolSearchBox = findViewById(R.id.toolSearchBox)
        toolCountLabel = findViewById(R.id.toolCountLabel)
        toolsRecyclerView = findViewById(R.id.toolsRecyclerView)

        // Setup tool adapter
        setupToolAdapter()

        // Load available tools
        loadAvailableTools()

        // Camera Selection UI
        cameraCountLabel = findViewById(R.id.cameraCountLabel)
        camerasRecyclerView = findViewById(R.id.camerasRecyclerView)
        setupCameraAdapter()
        loadAvailableCameras()

        // Setup tool filter mode radio buttons
        toolFilterModeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioAllTools -> {
                    currentToolFilterMode = ToolFilterMode.ALL
                    toolSelectionContainer.visibility = View.GONE
                }
                R.id.radioSelectedTools -> {
                    currentToolFilterMode = ToolFilterMode.SELECTED
                    toolSelectionContainer.visibility = View.VISIBLE
                    // When switching to SELECTED, pre-select all available tools if none are selected
                    if (selectedToolNames.isEmpty()) {
                        selectedToolNames = availableTools.map { it.name }.toMutableSet()
                        updateToolSelections()
                    }
                }
            }
        }

        // Setup search box with text watcher
        toolSearchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                toolAdapter.filter(query)
            }
        })

        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)

        saveButton.setOnClickListener {
            // Show syncing state for shared profiles
            val app = application as HAGeminiApp
            val editingProfile = editingProfileId?.let { id ->
                app.profileService.getAllProfiles().find { it.id == id }
            }
            if (targetSource == ProfileSource.SHARED ||
                (editingProfile?.source == ProfileSource.SHARED)) {
                showSyncingState()
            }

            doSaveProfile()
        }

        cancelButton.setOnClickListener {
            finish()
        }

        // Initialize test UI components
        testButton = findViewById(R.id.testButton)
        testStatusCard = findViewById(R.id.testStatusCard)
        testStatusText = findViewById(R.id.testStatusText)
        testToolLogContainer = findViewById(R.id.testToolLogContainer)
        testToolLogText = findViewById(R.id.testToolLogText)

        // Initialize test manager
        testManager = ProfileTestManager(
            app = application as HAGeminiApp,
            onStatusChange = { status ->
                lifecycleScope.launch {
                    updateTestStatus(status)
                }
            },
            logger = this
        )

        testButton.setOnClickListener {
            if (isTestActive) {
                testManager?.stopTest()
            } else {
                lifecycleScope.launch { startTest() }
            }
        }

        // Initialize sync status UI
        syncStatusSection = findViewById(R.id.syncStatusSection)
        syncProgressBar = findViewById(R.id.syncProgressBar)
        syncStatusIcon = findViewById(R.id.syncStatusIcon)
        syncStatusText = findViewById(R.id.syncStatusText)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener {
            saveButton.performClick()
        }

        // Setup expansion panels
        setupExpansionPanel(systemPromptHeader, systemPromptContent, systemPromptExpandIcon, isSystemPromptExpanded)
            { isSystemPromptExpanded = it }
        setupExpansionPanel(personalityHeader, personalityContent, personalityExpandIcon, isPersonalityExpanded)
            { isPersonalityExpanded = it }
        setupExpansionPanel(backgroundInfoHeader, backgroundInfoContent, backgroundInfoExpandIcon, isBackgroundInfoExpanded)
            { isBackgroundInfoExpanded = it }
        setupExpansionPanel(initialMessageHeader, initialMessageContent, initialMessageExpandIcon, isInitialMessageExpanded)
            { isInitialMessageExpanded = it }
    }

    private fun setupExpansionPanel(
        headerView: View,
        contentView: View,
        iconView: ImageView,
        initiallyExpanded: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        // Set initial icon rotation
        iconView.rotation = if (initiallyExpanded) 180f else 0f

        headerView.setOnClickListener {
            val isExpanding = contentView.visibility != View.VISIBLE

            if (isExpanding) {
                contentView.visibility = View.VISIBLE
            } else {
                contentView.visibility = View.GONE
            }

            // Animate icon rotation
            iconView.animate()
                .rotation(if (isExpanding) 180f else 0f)
                .setDuration(200)
                .start()

            onToggle(isExpanding)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.editorState.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: ProfileEditorState) {
        when (state) {
            is ProfileEditorState.Idle -> {
                // Initial state
                saveButton.isEnabled = true
            }
            is ProfileEditorState.Loaded -> {
                // Populate fields with loaded profile
                profileNameInput.setText(state.profile.name)
                systemPromptInput.setText(state.profile.systemPrompt)
                personalityInput.setText(state.profile.personality)
                backgroundInfoInput.setText(state.profile.backgroundInfo)
                initialMessageInput.setText(state.profile.initialMessageToAgent)
                modelInput.setText(state.profile.model, false)
                voiceInput.setText(state.profile.voice, false)
                includeLiveContextCheckbox.isChecked = state.profile.includeLiveContext
                enableTranscriptionCheckbox.isChecked = state.profile.enableTranscription
                autoStartChatCheckbox.isChecked = state.profile.autoStartChat
                interruptableSwitch.isChecked = state.profile.interruptable
                affectiveDialogSwitch.isChecked = state.profile.enableAffectiveDialog
                proactivitySwitch.isChecked = state.profile.enableProactivity

                // Store original lastModified for conflict detection
                originalLastModified = state.profile.lastModified

                // Restore tool filter settings
                currentToolFilterMode = state.profile.toolFilterMode
                selectedToolNames = state.profile.selectedToolNames.toMutableSet()

                // Update UI to match the loaded settings
                when (currentToolFilterMode) {
                    ToolFilterMode.ALL -> {
                        radioAllTools.isChecked = true
                        toolSelectionContainer.visibility = View.GONE
                    }
                    ToolFilterMode.SELECTED -> {
                        radioSelectedTools.isChecked = true
                        toolSelectionContainer.visibility = View.VISIBLE
                    }
                }

                // Update tool selections in adapter
                updateToolSelections()

                // Restore camera selection settings
                selectedCameraEntityIds = state.profile.allowedModelCameras.toMutableSet()
                updateCameraSelections()

                // Set target source based on loaded profile
                targetSource = state.profile.source

                saveButton.isEnabled = true
            }
            is ProfileEditorState.Saving -> {
                // Disable button while saving
                saveButton.isEnabled = false
                saveButton.text = "Saving..."
                profileNameLayout.error = null
            }
            is ProfileEditorState.SaveSuccess -> {
                if (targetSource == ProfileSource.SHARED) {
                    showSyncedState()
                    // Delay finish to show success
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(500)
                        Toast.makeText(this@ProfileEditorActivity, "Profile saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            is ProfileEditorState.SaveError -> {
                // Show error
                saveButton.isEnabled = true
                saveButton.text = getString(R.string.profile_editor_save)

                // Show sync error state if this was a shared profile save attempt
                if (syncStatusSection.visibility == View.VISIBLE) {
                    showSyncErrorState(state.message)
                } else {
                    // Show error in appropriate field or dialog
                    when {
                        state.message.contains("blank", ignoreCase = true) -> {
                            profileNameLayout.error = state.message
                        }
                        state.message.contains("already exists", ignoreCase = true) -> {
                            profileNameLayout.error = state.message
                        }
                        else -> {
                            // Generic error - show in dialog
                            showErrorDialog(state.message)
                        }
                    }
                }
            }
            is ProfileEditorState.ConflictDetected -> {
                showConflictDialog(state.serverProfile)
            }
        }
    }

    private fun setupToolAdapter() {
        toolAdapter = ToolSelectionAdapter { toolName, isSelected ->
            if (isSelected) {
                selectedToolNames.add(toolName)
            } else {
                selectedToolNames.remove(toolName)
            }
            updateToolCountLabel()
        }

        toolsRecyclerView.layoutManager = LinearLayoutManager(this)
        toolsRecyclerView.adapter = toolAdapter
    }

    private fun setupCameraAdapter() {
        cameraAdapter = CameraSelectionAdapter { entityId, isSelected ->
            if (isSelected) {
                selectedCameraEntityIds.add(entityId)
            } else {
                selectedCameraEntityIds.remove(entityId)
            }
            updateCameraCountLabel()
        }

        camerasRecyclerView.layoutManager = LinearLayoutManager(this)
        camerasRecyclerView.adapter = cameraAdapter
    }

    private fun loadAvailableTools() {
        lifecycleScope.launch {
            val app = application as HAGeminiApp
            val tokenManager = app.getTokenManager()
            if (tokenManager == null || app.haUrl == null) {
                toolCacheWarning.visibility = View.VISIBLE
                return@launch
            }
            val mcp = McpClientManager(app.haUrl!!, tokenManager)
            try {
                mcp.connect()
                val toolsResult = mcp.getTools()
                val tools = toolsResult.map { mcpTool ->
                    SelectableTool(
                        name = mcpTool.name,
                        description = mcpTool.description,
                        isSelected = selectedToolNames.contains(mcpTool.name),
                        isAvailable = true
                    )
                }
                availableTools = tools.sortedBy { it.name }
                toolCacheWarning.visibility = View.GONE
                toolAdapter.submitFullList(availableTools)
                updateToolCountLabel()
            } finally {
                mcp.shutdown()
            }
        }
    }

    private fun updateToolSelections() {
        // Update adapter with current selections
        val updatedTools = availableTools.map { tool ->
            tool.copy(isSelected = selectedToolNames.contains(tool.name))
        }
        availableTools = updatedTools
        toolAdapter.submitFullList(updatedTools)
        updateToolCountLabel()
    }

    private fun updateToolCountLabel() {
        val count = selectedToolNames.size
        val plural = if (count == 1) "tool" else "tools"
        toolCountLabel.text = "$count $plural selected"
    }

    private fun loadAvailableCameras() {
        lifecycleScope.launch {
            val app = application as HAGeminiApp
            val haApiClient = app.haApiClient
            if (haApiClient == null) {
                cameraCountLabel.text = "Cameras unavailable (not connected)"
                return@launch
            }
            try {
                val cameras = haApiClient.getCameraEntities()
                val selectableCameras = cameras.map { camera ->
                    SelectableCamera(
                        entityId = camera.entityId,
                        friendlyName = camera.friendlyName,
                        isSelected = selectedCameraEntityIds.contains(camera.entityId)
                    )
                }
                availableCameras = selectableCameras.sortedBy { it.friendlyName }
                cameraAdapter.submitFullList(availableCameras)
                updateCameraCountLabel()
            } catch (e: Exception) {
                cameraCountLabel.text = "Failed to load cameras"
            }
        }
    }

    private fun updateCameraSelections() {
        val updatedCameras = availableCameras.map { camera ->
            camera.copy(isSelected = selectedCameraEntityIds.contains(camera.entityId))
        }
        availableCameras = updatedCameras
        cameraAdapter.submitFullList(updatedCameras)
        updateCameraCountLabel()
    }

    private fun updateCameraCountLabel() {
        val count = selectedCameraEntityIds.size
        val total = availableCameras.size
        cameraCountLabel.text = "$count of $total cameras available for model"
    }

    private fun showSyncingState() {
        syncStatusSection.visibility = View.VISIBLE
        syncProgressBar.visibility = View.VISIBLE
        syncStatusIcon.visibility = View.GONE
        syncStatusText.text = "Saving to Home Assistant..."
        retryButton.visibility = View.GONE
        saveButton.isEnabled = false
    }

    private fun showSyncedState() {
        syncProgressBar.visibility = View.GONE
        syncStatusIcon.visibility = View.VISIBLE
        syncStatusIcon.setImageResource(android.R.drawable.ic_menu_save)
        syncStatusIcon.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        syncStatusText.text = "Saved!"
        retryButton.visibility = View.GONE
    }

    private fun showSyncErrorState(message: String?) {
        syncProgressBar.visibility = View.GONE
        syncStatusIcon.visibility = View.VISIBLE
        syncStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
        syncStatusIcon.setColorFilter(android.graphics.Color.parseColor("#F44336"))
        syncStatusText.text = "Sync failed: ${message ?: "Unknown error"}"
        retryButton.visibility = View.VISIBLE
        saveButton.isEnabled = true
    }

    private fun showConflictDialog(serverProfile: Profile?) {
        val modifiedBy = serverProfile?.modifiedBy ?: "someone"

        AlertDialog.Builder(this)
            .setTitle("Profile Modified")
            .setMessage("This profile was modified by $modifiedBy while you were editing. What would you like to do?")
            .setPositiveButton("Overwrite with my changes") { _, _ ->
                doSaveProfile(forceOverwrite = true)
            }
            .setNegativeButton("Discard my changes") { _, _ ->
                finish()
            }
            .setNeutralButton("Save as new profile") { _, _ ->
                saveAsNewProfile()
            }
            .show()
    }

    private fun saveAsNewProfile() {
        val name = profileNameInput.text?.toString() ?: ""
        // Create with new name
        profileNameInput.setText("$name (My Version)")
        editingProfileId = null  // Force create mode
        targetSource = ProfileSource.LOCAL  // Save as local
        saveButton.performClick()
    }

    private fun doSaveProfile(forceOverwrite: Boolean = false) {
        val name = profileNameInput.text?.toString() ?: ""
        val prompt = systemPromptInput.text?.toString() ?: ""
        val personality = personalityInput.text?.toString() ?: ""
        val backgroundInfo = backgroundInfoInput.text?.toString() ?: ""
        val initialMessageToAgent = initialMessageInput.text?.toString() ?: ""
        val model = modelInput.text?.toString() ?: ""
        val voice = voiceInput.text?.toString() ?: ""
        val includeLiveContext = includeLiveContextCheckbox.isChecked
        val enableTranscription = enableTranscriptionCheckbox.isChecked
        val autoStartChat = autoStartChatCheckbox.isChecked
        val interruptable = interruptableSwitch.isChecked
        val enableAffectiveDialog = affectiveDialogSwitch.isChecked
        val enableProactivity = proactivitySwitch.isChecked
        val allowedModelCameras = selectedCameraEntityIds.toSet()
        viewModel.saveProfile(
            name, prompt, personality, backgroundInfo, initialMessageToAgent,
            model, voice, includeLiveContext, enableTranscription, autoStartChat, interruptable, currentToolFilterMode,
            selectedToolNames.toSet(), allowedModelCameras, editingProfileId, targetSource,
            originalLastModified, forceOverwrite, enableAffectiveDialog, enableProactivity
        )
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private suspend fun startTest() {
        // Check microphone permission
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Build profile from current UI state
        val testProfile = buildProfileFromUI()

        try {
            testManager?.startTest(testProfile, this@ProfileEditorActivity)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start test: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildProfileFromUI(): Profile {
        return Profile(
            id = "test-${System.currentTimeMillis()}", // Temporary ID
            name = profileNameInput.text.toString().ifBlank { "Test Profile" },
            systemPrompt = systemPromptInput.text.toString(),
            personality = personalityInput.text.toString(),
            backgroundInfo = backgroundInfoInput.text.toString(),
            initialMessageToAgent = initialMessageInput.text.toString(),
            model = modelInput.text.toString(),
            voice = voiceInput.text.toString(),
            includeLiveContext = includeLiveContextCheckbox.isChecked,
            enableTranscription = enableTranscriptionCheckbox.isChecked,
            autoStartChat = false, // Irrelevant for testing
            interruptable = interruptableSwitch.isChecked,
            enableAffectiveDialog = affectiveDialogSwitch.isChecked,
            enableProactivity = proactivitySwitch.isChecked,
            toolFilterMode = currentToolFilterMode,
            selectedToolNames = selectedToolNames.toSet(),
            allowedModelCameras = selectedCameraEntityIds.toSet()
        )
    }

    private fun updateTestStatus(status: ProfileTestManager.TestStatus) {
        when (status) {
            is ProfileTestManager.TestStatus.Idle -> {
                testButton.text = getString(R.string.profile_test_button)
                testButton.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_media_play, 0, 0, 0)
                testStatusCard.visibility = View.GONE
                isTestActive = false
                enableEditing(true)
            }
            is ProfileTestManager.TestStatus.Initializing -> {
                testButton.isEnabled = false
                testStatusCard.visibility = View.VISIBLE
                testStatusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#E3F2FD")))
                testStatusText.text = getString(R.string.profile_test_initializing)
                testToolLogContainer.visibility = View.GONE
                enableEditing(false)

                // Clear logs from previous test
                testLogs.clear()
            }
            is ProfileTestManager.TestStatus.Active -> {
                testButton.text = getString(R.string.profile_test_stop)
                testButton.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_media_pause, 0, 0, 0)
                testButton.isEnabled = true
                testStatusCard.visibility = View.VISIBLE
                testStatusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#C8E6C9")))
                testStatusText.text = status.message
                isTestActive = true
            }
            is ProfileTestManager.TestStatus.Stopped -> {
                val toolsCalled = testManager?.getCalledTools() ?: emptyList()

                testStatusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#FFF9C4")))
                testStatusCard.visibility = View.VISIBLE

                if (toolsCalled.isNotEmpty()) {
                    testStatusText.text = getString(R.string.profile_test_stopped_with_tools)
                    testToolLogContainer.visibility = View.VISIBLE
                    testToolLogText.text = toolsCalled.joinToString("\n") { "• $it" }
                } else {
                    testStatusText.text = getString(R.string.profile_test_stopped_no_tools)
                    testToolLogContainer.visibility = View.GONE
                }

                testButton.text = getString(R.string.profile_test_button)
                testButton.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_media_play, 0, 0, 0)
                isTestActive = false
                enableEditing(true)

                // Hide status after 5 seconds
                testStatusCard.postDelayed({
                    testStatusCard.visibility = View.GONE
                }, 5000)
            }
            is ProfileTestManager.TestStatus.Error -> {
                testStatusCard.visibility = View.VISIBLE
                testStatusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#FFCDD2")))
                testStatusText.text = getString(R.string.profile_test_error, status.message)
                testToolLogContainer.visibility = View.GONE
                testButton.text = getString(R.string.profile_test_button)
                testButton.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_media_play, 0, 0, 0)
                isTestActive = false
                enableEditing(true)
            }
        }
    }

    private fun updateTestLogDisplay() {
        if (testLogs.isEmpty()) {
            testToolLogContainer.visibility = View.GONE
            return
        }

        testToolLogContainer.visibility = View.VISIBLE

        // Format logs in detailed multi-line format
        val formatted = testLogs.joinToString("\n\n") { log ->
            val status = if (log.success) "✓" else "✗"
            buildString {
                append("[$status] ${log.timestamp} ${log.toolName}\n")

                // Show parameters if not empty
                if (log.parameters.isNotBlank()) {
                    append("  Args: ${log.parameters}\n")
                }

                // Show result (truncate if too long)
                val result = if (log.result.length > 200) {
                    log.result.take(200) + "..."
                } else {
                    log.result
                }
                append("  Result: $result")
            }
        }

        testToolLogText.text = formatted

        // Auto-scroll to bottom to see latest log entry
        testToolLogText.post {
            // Find the parent NestedScrollView and scroll to bottom
            var parent = testToolLogText.parent
            while (parent != null && parent !is NestedScrollView) {
                parent = parent.parent
            }
            (parent as? NestedScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun enableEditing(enabled: Boolean) {
        profileNameInput.isEnabled = enabled
        modelInput.isEnabled = enabled
        voiceInput.isEnabled = enabled
        systemPromptInput.isEnabled = enabled
        personalityInput.isEnabled = enabled
        backgroundInfoInput.isEnabled = enabled
        initialMessageInput.isEnabled = enabled
        includeLiveContextCheckbox.isEnabled = enabled
        enableTranscriptionCheckbox.isEnabled = enabled
        autoStartChatCheckbox.isEnabled = enabled
        interruptableSwitch.isEnabled = enabled
        affectiveDialogSwitch.isEnabled = enabled
        proactivitySwitch.isEnabled = enabled
        radioAllTools.isEnabled = enabled
        radioSelectedTools.isEnabled = enabled
        toolSearchBox.isEnabled = enabled
        camerasRecyclerView.isEnabled = enabled
        saveButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        testManager?.cleanup()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun addLogEntry(log: LogEntry) {
        testLogs.add(log)

        lifecycleScope.launch {
            updateTestLogDisplay()
        }
    }

    override fun addModelTranscription(chunk: String, isThought: Boolean) {
        testLogs.add(LogEntry(
            timestamp = "",
            toolName = "Transcription",
            parameters = "isThought: ${isThought}",
            success = true,
            result = "MODEL: $chunk"
        ))

        lifecycleScope.launch {
            updateTestLogDisplay()
        }
    }

    override fun addUserTranscription(chunk: String) {
        testLogs.add(LogEntry(
            timestamp = "",
            toolName = "Transcription",
            parameters = "",
            success = true,
            result = "USER: $chunk"
        ))

        lifecycleScope.launch {
            updateTestLogDisplay()
        }
    }

    override fun addToolCallToTranscript(toolName: String, parameters: String, success: Boolean, result: String) {
        // Tool calls are already logged via addLogEntry in tests
        // This method exists for transcript display in main activity
    }
}

