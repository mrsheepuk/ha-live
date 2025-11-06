package uk.co.mrsheep.halive.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class ProfileEditorActivity : AppCompatActivity() {

    private val viewModel: ProfileEditorViewModel by viewModels()

    // Intent extras
    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_DUPLICATE_FROM_ID = "duplicate_from_id"
    }

    // UI components
    private lateinit var profileNameLayout: TextInputLayout
    private lateinit var profileNameInput: TextInputEditText
    private lateinit var systemPromptLayout: TextInputLayout
    private lateinit var systemPromptInput: TextInputEditText
    private lateinit var personalityLayout: TextInputLayout
    private lateinit var personalityInput: TextInputEditText
    private lateinit var backgroundInfoLayout: TextInputLayout
    private lateinit var backgroundInfoInput: TextInputEditText
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    // Mode tracking
    private var editingProfileId: String? = null
    private var isEditMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_editor)

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
        systemPromptLayout = findViewById(R.id.systemPromptLayout)
        systemPromptInput = findViewById(R.id.systemPromptInput)
        personalityLayout = findViewById(R.id.personalityLayout)
        personalityInput = findViewById(R.id.personalityInput)
        backgroundInfoLayout = findViewById(R.id.backgroundInfoLayout)
        backgroundInfoInput = findViewById(R.id.backgroundInfoInput)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)

        saveButton.setOnClickListener {
            val name = profileNameInput.text?.toString() ?: ""
            val prompt = systemPromptInput.text?.toString() ?: ""
            val personality = personalityInput.text?.toString() ?: ""
            val backgroundInfo = backgroundInfoInput.text?.toString() ?: ""
            viewModel.saveProfile(name, prompt, personality, backgroundInfo, editingProfileId)
        }

        cancelButton.setOnClickListener {
            finish()
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
                saveButton.isEnabled = true
            }
            is ProfileEditorState.Saving -> {
                // Disable button while saving
                saveButton.isEnabled = false
                saveButton.text = "Saving..."
                profileNameLayout.error = null
                systemPromptLayout.error = null
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
