package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.DummyToolsConfig
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.TranscriptionEntry
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
import uk.co.mrsheep.halive.core.TranscriptionTurn

class MainActivity : AppCompatActivity() {

    companion object {
        const val AUTO_START_CONVERSATION = "auto_start_conversation"
        const val PROFILE_ID = "profile_id"
    }

    private val viewModel: MainViewModel by viewModels()

    private lateinit var toolbar: Toolbar
    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var retryButton: Button
    private lateinit var audioVisualizer: AudioVisualizerView
    private lateinit var wakeWordChip: MaterialButton

    private lateinit var transcriptionRecyclerView: RecyclerView
    private lateinit var transcriptionAdapter: TranscriptionAdapter

    private lateinit var quickMessageScrollView: View
    private lateinit var quickMessageChipGroup: ChipGroup
    private lateinit var clearButton: Button

    // Layout transition support
    private lateinit var mainConstraintLayout: ConstraintLayout
    private val centeredConstraintSet = ConstraintSet()
    private val topAlignedConstraintSet = ConstraintSet()
    private var hasTransitionedToTop = false

    private fun checkConfigurationAndLaunch() {
        // Check if app is configured - need at least one provider AND Home Assistant
        val hasFirebase = FirebaseConfig.isConfigured(this)
        val hasGemini = GeminiConfig.isConfigured(this)
        val hasHA = HAConfig.isConfigured(this)

        if ((!hasFirebase && !hasGemini) || !hasHA) {
            // Launch onboarding - need at least one conversation provider and HA
            val intent = Intent(this, OnboardingActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
    }

    /**
     * Sets up both constraint configurations:
     * - Centered: vertical chain with CHAIN_PACKED for centered appearance
     * - TopAligned: current XML layout (top-to-bottom flow)
     */
    private fun setupConstraintSets() {
        // Top-aligned: clone from current XML (this is our "chat mode" layout)
        topAlignedConstraintSet.clone(mainConstraintLayout)

        // Centered: create vertical chain for centered appearance (pre-chat mode)
        centeredConstraintSet.clone(mainConstraintLayout)

        // Clear existing bottom constraints for chain elements
        centeredConstraintSet.clear(R.id.statusContainer, ConstraintSet.BOTTOM)
        centeredConstraintSet.clear(R.id.audioVisualizer, ConstraintSet.BOTTOM)
        centeredConstraintSet.clear(R.id.buttonContainer, ConstraintSet.BOTTOM)

        // Create vertical chain: statusContainer -> audioVisualizer -> buttonContainer
        centeredConstraintSet.createVerticalChain(
            R.id.statusContainer,
            ConstraintSet.TOP,
            R.id.buttonContainer,
            ConstraintSet.BOTTOM,
            intArrayOf(R.id.statusContainer, R.id.audioVisualizer, R.id.buttonContainer),
            null,
            ConstraintSet.CHAIN_PACKED
        )

        // Preserve original margins between chain elements for proper spacing
        val density = resources.displayMetrics.density
        centeredConstraintSet.setMargin(R.id.audioVisualizer, ConstraintSet.TOP, (16 * density).toInt())
        centeredConstraintSet.setMargin(R.id.buttonContainer, ConstraintSet.TOP, (8 * density).toInt())

        // Anchor chain to parent top/bottom for vertical centering
        centeredConstraintSet.connect(
            R.id.statusContainer,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )
        centeredConstraintSet.connect(
            R.id.buttonContainer,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )

        // Hide transcript and quick messages in centered mode
        centeredConstraintSet.setVisibility(R.id.transcriptionRecyclerView, View.GONE)
        centeredConstraintSet.setVisibility(R.id.quickMessageScrollView, View.GONE)
    }

    /**
     * Animate one-time transition from centered to top-aligned layout.
     * Called only on first chat activation.
     */
    private fun transitionToTopAlignedLayout() {
        if (hasTransitionedToTop) {
            return
        }

        hasTransitionedToTop = true
        viewModel.markHasChatted()

        // Make transcript visible before transition
        transcriptionRecyclerView.visibility = View.VISIBLE

        // Create smooth transition with bounds change and fade
        val transitionSet = TransitionSet().apply {
            addTransition(ChangeBounds().apply {
                duration = 400
            })
            addTransition(Fade(Fade.IN).apply {
                duration = 300
                addTarget(transcriptionRecyclerView)
            })
        }

        TransitionManager.beginDelayedTransition(mainConstraintLayout, transitionSet)
        topAlignedConstraintSet.applyTo(mainConstraintLayout)
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
        // Reload wake word settings in case they were changed in SettingsActivity
        viewModel.reloadWakeWordSettings()

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

        // Get reference to main constraint layout for transitions
        mainConstraintLayout = findViewById(R.id.mainConstraintLayout)
        setupConstraintSets()

        // Apply initial layout based on whether user has ever chatted
        if (viewModel.hasEverChatted.value) {
            // Already chatted before (e.g., after rotation) - use top layout immediately
            topAlignedConstraintSet.applyTo(mainConstraintLayout)
            hasTransitionedToTop = true
        } else {
            // Never chatted - start with centered layout
            centeredConstraintSet.applyTo(mainConstraintLayout)
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        retryButton = findViewById(R.id.retryButton)
        clearButton = findViewById(R.id.clearButton)
        audioVisualizer = findViewById(R.id.audioVisualizer)
        wakeWordChip = findViewById(R.id.wakeWordChip)

        transcriptionRecyclerView = findViewById(R.id.transcriptionRecyclerView)

        // Initialize RecyclerView
        transcriptionAdapter = TranscriptionAdapter()
        transcriptionRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = transcriptionAdapter
        }

        quickMessageScrollView = findViewById(R.id.quickMessageScrollView)
        quickMessageChipGroup = findViewById(R.id.quickMessageChipGroup)

        retryButton.setOnClickListener {
            viewModel.retryInitialization()
        }

        clearButton.setOnClickListener {
            clearTranscriptionAndReset()
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

        // Observe the UI state from the ViewModel
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUiForState(state)
            }
        }

        lifecycleScope.launch {
            viewModel.transcriptionLogs.collect { logs ->
                updateTranscriptionLogs(logs)
            }
        }

        // Observe audio level from ViewModel
        lifecycleScope.launch {
            viewModel.audioLevel.collect { level ->
                audioVisualizer.setAudioLevel(level)
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

        // Handle widget auto-start intent
        handleWidgetAutoStartIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the activity's intent to point to the new one
        setIntent(intent)
        // Handle widget auto-start intent
        handleWidgetAutoStartIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // Set checked state for dummy tools menu item
        menu.findItem(R.id.action_dummy_tools)?.isChecked = DummyToolsConfig.isEnabled(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manage_profiles -> {
                val intent = Intent(this, ProfileManagementActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                // Pass chat active state
                intent.putExtra("isChatActive", viewModel.isSessionActive())
                startActivity(intent)
                true
            }
            R.id.action_debug_logs -> {
                DebugLogsBottomSheet.newInstance().show(supportFragmentManager, "DebugLogsBottomSheet")
                true
            }
            R.id.action_dummy_tools -> {
                val currentState = DummyToolsConfig.isEnabled(this)
                val newState = !currentState
                DummyToolsConfig.setEnabled(this, newState)
                item.isChecked = newState

                val message = if (newState) {
                    "Dummy tools enabled - restart chat to apply"
                } else {
                    "Dummy tools disabled - restart chat to apply"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
            }
            UiState.ProviderConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
            }
            UiState.HAConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
            }
            UiState.Initializing -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                statusText.text = "Initializing..."
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
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
                quickMessageScrollView.visibility = View.GONE
                // Show clear button if we have transcription logs from previous chats
                clearButton.visibility = if (viewModel.transcriptionLogs.value.isNotEmpty()) View.VISIBLE else View.GONE
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
                clearButton.visibility = View.GONE

                // Animate one-time layout transition to top-aligned mode (if first chat)
                if (!viewModel.hasEverChatted.value) {
                    transitionToTopAlignedLayout()
                }

                // Populate quick message chips (visibility handled inside)
                // Done after transition to avoid visibility being overridden by constraint set
                populateQuickMessageChips()
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
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
            }
            is UiState.Error -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                statusText.text = state.message
                wakeWordChip.visibility = View.VISIBLE
                wakeWordChip.isEnabled = false
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
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

    private fun handleWidgetAutoStartIntent(intent: Intent) {
        // Check if this intent has the auto-start conversation extra
        if (!intent.getBooleanExtra(AUTO_START_CONVERSATION, false)) {
            return
        }

        // If session is already active, don't interrupt it - just bring to foreground
        if (viewModel.isSessionActive()) {
            return
        }

        // Ensure we're in the ready state
        if (viewModel.uiState.value != UiState.ReadyToTalk) {
            return
        }

        // Handle profile switching if PROFILE_ID is provided
        val profileId = intent.getStringExtra(PROFILE_ID)
        if (!profileId.isNullOrEmpty()) {
            viewModel.switchProfile(profileId)
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

    private fun updateTranscriptionLogs(logs: List<TranscriptionEntry>) {
        // Group consecutive entries by speaker into "turns"
        val turns = mutableListOf<TranscriptionTurn>()

        if (logs.isEmpty()) {
            transcriptionAdapter.updateTurns(emptyList())
            return
        }

        var currentSpeaker: TranscriptionSpeaker? = null
        var currentText = StringBuilder()

        logs.forEach { entry ->
            if (entry.spokenBy != currentSpeaker) {
                // Speaker changed - save previous turn if exists
                if (currentSpeaker != null && currentText.isNotEmpty()) {
                    turns.add(TranscriptionTurn(currentSpeaker!!, currentText.toString()))
                }
                // Start new turn
                currentSpeaker = entry.spokenBy
                currentText = StringBuilder(entry.chunk)
            } else {
                // Same speaker - append to current turn
                currentText.append(entry.chunk)
            }
        }

        // Add final turn
        if (currentSpeaker != null && currentText.isNotEmpty()) {
            turns.add(TranscriptionTurn(currentSpeaker!!, currentText.toString()))
        }

        // Update adapter
        transcriptionAdapter.updateTurns(turns)

        // Auto-scroll to bottom to show most recent message
        if (turns.isNotEmpty()) {
            transcriptionRecyclerView.post {
                transcriptionRecyclerView.smoothScrollToPosition(turns.size - 1)
            }
        }
    }

    private fun updateWakeWordChipAppearance(enabled: Boolean) {
        if (enabled) {
            // Filled style when enabled
            wakeWordChip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.orange_accent)
            wakeWordChip.setTextColor(ContextCompat.getColor(this, R.color.white))
            wakeWordChip.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            wakeWordChip.strokeWidth = 0
        } else {
            // Outlined style when disabled
            wakeWordChip.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
            wakeWordChip.setTextColor(ContextCompat.getColor(this, R.color.teal_primary))
            wakeWordChip.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal_primary))
            wakeWordChip.strokeWidth = (1 * resources.displayMetrics.density).toInt() // 1dp stroke
        }
    }

    private fun populateQuickMessageChips() {
        // Clear any existing chips
        quickMessageChipGroup.removeAllViews()

        // Get enabled quick messages from viewModel
        val quickMessages = viewModel.getEnabledQuickMessages()

        // Hide the entire scroll view if there are no quick messages
        if (quickMessages.isEmpty()) {
            quickMessageScrollView.visibility = View.GONE
            return
        }

        // Show scroll view and populate chips
        quickMessageScrollView.visibility = View.VISIBLE

        // Create chip for each quick message
        for (quickMessage in quickMessages) {
            val chip = Chip(this).apply {
                text = quickMessage.label
                isClickable = true
                setOnClickListener {
                    viewModel.sendQuickMessage(quickMessage.message)
                }
            }
            quickMessageChipGroup.addView(chip)
        }
    }

    private fun clearTranscriptionAndReset() {
        // Clear transcription logs from ViewModel
        viewModel.clearTranscriptionLogs()

        // Reset chat state so next chat will animate transition again
        viewModel.resetChatState()

        // Hide transcription view and quick messages
        transcriptionRecyclerView.visibility = View.GONE
        quickMessageScrollView.visibility = View.GONE

        // Transition back to centered layout
        hasTransitionedToTop = false
        val transitionSet = TransitionSet().apply {
            addTransition(ChangeBounds().apply {
                duration = 400
            })
            addTransition(Fade(Fade.OUT).apply {
                duration = 300
                addTarget(transcriptionRecyclerView)
            })
        }

        TransitionManager.beginDelayedTransition(mainConstraintLayout, transitionSet)
        centeredConstraintSet.applyTo(mainConstraintLayout)

        // Hide the clear button
        clearButton.visibility = View.GONE

        // Update status text
        statusText.text = "Ready to chat"
    }

    fun provideViewModel(): MainViewModel = viewModel
}
