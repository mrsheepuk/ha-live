package uk.co.mrsheep.halive.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.Transformation
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.ToolFilterMode
import uk.co.mrsheep.halive.ui.adapters.SelectableTool
import uk.co.mrsheep.halive.ui.adapters.ToolSelectionAdapter
import kotlinx.coroutines.launch

class ProfileEditorActivity : AppCompatActivity() {

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
    private lateinit var autoStartChatCheckbox: MaterialCheckBox

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

    // Buttons (unchanged)
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    // Expansion state
    private var isSystemPromptExpanded = true
    private var isPersonalityExpanded = false
    private var isBackgroundInfoExpanded = false
    private var isInitialMessageExpanded = false

    // Mode tracking
    private var editingProfileId: String? = null
    private var isEditMode: Boolean = false

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

        // Setup model dropdown - hardcoded single option for now
        val modelOptions = arrayOf("gemini-live-2.5-flash-preview", "gemini-live-2.5-flash-preview-native-audio-09-2025")
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelOptions)
        modelInput.setAdapter(modelAdapter)
        modelInput.setText(modelOptions[0], false) // Set default

        // Setup voice dropdown - hardcoded two options
        val voiceOptions = arrayOf("Aoede", "Leda", "Kore", "Puck", "Charon", "Fenrir", "Orus", "Zephyr")
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
        autoStartChatCheckbox = findViewById(R.id.autoStartChatCheckbox)

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
            val name = profileNameInput.text?.toString() ?: ""
            val prompt = systemPromptInput.text?.toString() ?: ""
            val personality = personalityInput.text?.toString() ?: ""
            val backgroundInfo = backgroundInfoInput.text?.toString() ?: ""
            val initialMessageToAgent = initialMessageInput.text?.toString() ?: ""
            val model = modelInput.text?.toString() ?: ""
            val voice = voiceInput.text?.toString() ?: ""
            val includeLiveContext = includeLiveContextCheckbox.isChecked
            val autoStartChat = autoStartChatCheckbox.isChecked
            viewModel.saveProfile(
                name, prompt, personality, backgroundInfo, initialMessageToAgent,
                model, voice, includeLiveContext, autoStartChat, currentToolFilterMode,
                selectedToolNames.toSet(), editingProfileId
            )
        }

        cancelButton.setOnClickListener {
            finish()
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
                autoStartChatCheckbox.isChecked = state.profile.autoStartChat

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
                saveButton.isEnabled = true
            }
            is ProfileEditorState.Saving -> {
                // Disable button while saving
                saveButton.isEnabled = false
                saveButton.text = "Saving..."
                profileNameLayout.error = null
            }
            is ProfileEditorState.SaveSuccess -> {
                // Show success and finish
                Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
                finish()
            }
            is ProfileEditorState.SaveError -> {
                // Show error
                saveButton.isEnabled = true
                saveButton.text = getString(R.string.profile_editor_save)

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

    private fun loadAvailableTools() {
        lifecycleScope.launch {
            try {
                val app = application as HAGeminiApp
                val mcpClient = app.mcpClient

                // Try to fetch from MCP client, fallback to cache
                if (mcpClient != null) {
                    try {
                        val toolsResult = mcpClient.getTools()
                        if (toolsResult != null) {
                            val tools = toolsResult.tools.map { mcpTool ->
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
                        }
                    } catch (e: Exception) {
                        // MCP failed, use cache
                        loadToolsFromCache(app)
                    }
                } else {
                    // No MCP connection, use cache
                    loadToolsFromCache(app)
                }
            } catch (e: Exception) {
                // Silent fail - just show empty list
                availableTools = emptyList()
                toolAdapter.submitFullList(emptyList())
            }
        }
    }

    private fun loadToolsFromCache(app: HAGeminiApp) {
        val cachedTools = app.lastAvailableTools ?: emptyList()
        availableTools = cachedTools.map { toolName ->
            SelectableTool(
                name = toolName,
                description = "Tool (from cache)",
                isSelected = selectedToolNames.contains(toolName),
                isAvailable = true
            )
        }.sortedBy { it.name }

        // Show warning if using cache
        if (cachedTools.isNotEmpty()) {
            toolCacheWarning.visibility = View.VISIBLE
        }

        toolAdapter.submitFullList(availableTools)
        updateToolCountLabel()
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

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

