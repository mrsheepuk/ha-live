package uk.co.mrsheep.halive.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.button.MaterialButton
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.DummyToolsConfig
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HAConfig
import uk.co.mrsheep.halive.BuildConfig
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.TranscriptDisplayItem
import uk.co.mrsheep.halive.core.TranscriptItem
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
import uk.co.mrsheep.halive.core.CameraConfig
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.services.camera.CameraFacing
import uk.co.mrsheep.halive.services.camera.DeviceCameraSource
import uk.co.mrsheep.halive.services.camera.HACameraSource
import uk.co.mrsheep.halive.services.camera.VideoSource
import uk.co.mrsheep.halive.services.camera.VideoSourceType

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
    private lateinit var preVideoButton: MaterialButton

    private lateinit var transcriptionRecyclerView: RecyclerView
    private lateinit var transcriptionAdapter: TranscriptionAdapter

    private lateinit var quickMessageScrollView: View
    private lateinit var quickMessageChipGroup: ChipGroup
    private lateinit var clearButton: Button

    // Camera views
    private lateinit var cameraPreviewCard: MaterialCardView
    private lateinit var cameraPreview: PreviewView
    private lateinit var cameraToggleButton: MaterialButton
    private lateinit var cameraFlipButton: MaterialButton

    // Current video source (created on demand).
    // Volatile for visibility - updated on main thread, read from IO dispatcher in callbacks.
    @Volatile
    private var currentVideoSource: VideoSource? = null
    private var currentSourceType: VideoSourceType = VideoSourceType.None

    // ImageView for HA camera preview (shows last frame)
    private var haCameraPreviewImage: ImageView? = null

    // Layout transition support
    private lateinit var mainConstraintLayout: ConstraintLayout
    private val centeredConstraintSet = ConstraintSet()
    private val topAlignedConstraintSet = ConstraintSet()
    private var hasTransitionedToTop = false

    private fun checkConfigurationAndLaunch() {
        // Check if app is configured - need Gemini API key AND Home Assistant
        val geminiConfigured = GeminiConfig.isConfigured(this)
        val haConfigured = HAConfig.isConfigured(this)
        android.util.Log.d("MainActivity", "checkConfiguration: gemini=$geminiConfigured, ha=$haConfigured")

        if (!geminiConfigured || !haConfigured) {
            android.util.Log.d("MainActivity", "Launching onboarding: gemini=$geminiConfigured, ha=$haConfigured")
            // Launch onboarding - need Gemini API configuration and HA
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

    // Activity Result Launcher for required permissions (RECORD_AUDIO and POST_NOTIFICATIONS on API 33+)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true // Not required on older versions
        }

        if (audioGranted) {
            // Audio is granted, proceed even if notification permission is denied
            // (service can run, just notification might not show in shade)
            viewModel.onChatButtonClicked()
        } else {
            // Audio permission is required
            viewModel.onPermissionDenied()
        }
    }

    // Store pending source type for permission callback
    private var pendingSourceType: VideoSourceType? = null
    // Track if current permission request is from auto-start (to handle denial differently)
    private var isAutoStartPermissionRequest = false

    // Activity Result Launcher for camera permission
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, start the pending source
            (pendingSourceType as? VideoSourceType.DeviceCamera)?.let { sourceType ->
                startDeviceCameraSource(sourceType.facing)
            }
        } else {
            // Permission denied - check if this was from auto-start or manual selection
            if (isAutoStartPermissionRequest) {
                // Auto-start failed - show message but keep saved preference for next session
                Toast.makeText(this, R.string.video_permission_denied, Toast.LENGTH_SHORT).show()
                // Don't clear the preference - user can try again next session after granting permission
            } else {
                // Manual selection during chat - generic message, don't reset preference
                Toast.makeText(this, "Camera permission is required to use this feature", Toast.LENGTH_SHORT).show()
            }
        }
        pendingSourceType = null
        isAutoStartPermissionRequest = false
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
        preVideoButton = findViewById(R.id.preVideoButton)

        transcriptionRecyclerView = findViewById(R.id.transcriptionRecyclerView)

        // Initialize RecyclerView
        transcriptionAdapter = TranscriptionAdapter()
        transcriptionRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = transcriptionAdapter
        }

        quickMessageScrollView = findViewById(R.id.quickMessageScrollView)
        quickMessageChipGroup = findViewById(R.id.quickMessageChipGroup)

        // Camera views
        cameraPreviewCard = findViewById(R.id.cameraPreviewCard)
        cameraPreview = findViewById(R.id.cameraPreview)
        cameraToggleButton = findViewById(R.id.cameraToggleButton)
        cameraFlipButton = findViewById(R.id.cameraFlipButton)

        retryButton.setOnClickListener {
            viewModel.retryInitialization()
        }

        clearButton.setOnClickListener {
            clearTranscriptionAndReset()
        }

        // Observe flows and update UI - only when activity is in STARTED state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe video start enabled state
                launch {
                    viewModel.videoStartEnabled.collect { enabled ->
                        updatePreVideoButtonAppearance(enabled)
                    }
                }

                // Observe selected video source for button text
                launch {
                    viewModel.selectedVideoSource.collect { source ->
                        updatePreVideoButtonText(source)
                    }
                }

                // Observe the UI state from the ViewModel
                launch {
                    viewModel.uiState.collect { state ->
                        updateUiForState(state)
                    }
                }

                // Observe transcription logs
                launch {
                    viewModel.transcriptionLogs.collect { logs ->
                        updateTranscriptionLogs(logs)
                    }
                }

                // Observe audio level from ViewModel
                launch {
                    viewModel.audioLevel.collect { level ->
                        audioVisualizer.setAudioLevel(level)
                    }
                }

                // Observe auto-start intent
                launch {
                    viewModel.shouldAttemptAutoStart.collect { shouldAutoStart ->
                        if (shouldAutoStart) {
                            handleAutoStart()
                            viewModel.consumeAutoStartIntent()
                        }
                    }
                }

                // Observe camera enabled state
                launch {
                    viewModel.isCameraEnabled.collect { enabled ->
                        updateCameraUI(enabled)
                    }
                }

                // Observe camera facing state
                launch {
                    viewModel.cameraFacing.collect { facing ->
                        // Update button appearance based on facing direction
                        cameraFlipButton.contentDescription = if (facing == CameraFacing.FRONT) {
                            "Switch to rear camera"
                        } else {
                            "Switch to front camera"
                        }
                    }
                }

                // Observe model camera state
                launch {
                    viewModel.modelWatchingCamera.collect { entityId ->
                        handleModelCameraStateChange(entityId)
                    }
                }

                // Auto-start video when chat becomes active (if pre-selected)
                launch {
                    // Initialize based on current state to avoid re-triggering on activity recreation
                    var wasActive = viewModel.uiState.value == UiState.ChatActive
                    viewModel.uiState.collect { state ->
                        val isNowActive = state == UiState.ChatActive
                        if (isNowActive && !wasActive && viewModel.videoStartEnabled.value) {
                            // Just transitioned to ChatActive with video enabled
                            autoStartPreSelectedVideo()
                        }
                        wasActive = isNowActive
                    }
                }
            }
        }

        // Camera toggle button click handler - shows popup menu
        cameraToggleButton.setOnClickListener { view ->
            showCameraSourceMenu(view)
        }

        // Camera flip button click handler
        cameraFlipButton.setOnClickListener {
            switchCamera()
        }

        // Handle user clicking the pre-video button - opens camera selector
        preVideoButton.setOnClickListener {
            showPreChatCameraSourceMenu()
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
        // Set wake word menu item state
        menu.findItem(R.id.action_wake_word)?.let { item ->
            item.isChecked = viewModel.wakeWordEnabled.value
            item.isVisible = BuildConfig.HAS_WAKE_WORD
        }
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
            R.id.action_wake_word -> {
                val newState = !item.isChecked
                viewModel.toggleWakeWord(newState)
                item.isChecked = newState
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
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
            UiState.ProviderConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
            UiState.HAConfigNeeded -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                // Should not reach here - handled by OnboardingActivity
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                statusText.text = "Please complete onboarding"
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
            UiState.Initializing -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                statusText.text = "Initializing..."
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
            UiState.ReadyToTalk -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Start Chat"
                preVideoButton.visibility = View.VISIBLE
                preVideoButton.isEnabled = true
                statusText.text = "Ready to chat"
                mainButton.setOnTouchListener(null) // Remove touch listener
                mainButton.setOnClickListener(chatButtonClickListener)
                quickMessageScrollView.visibility = View.GONE
                // Show clear button if we have transcription logs from previous chats
                clearButton.visibility = if (viewModel.transcriptionLogs.value.isNotEmpty()) View.VISIBLE else View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
            UiState.ChatActive -> {
                audioVisualizer.setState(VisualizerState.ACTIVE)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Chat active - listening..."
                // Hide pre-video button during chat
                preVideoButton.visibility = View.GONE
                clearButton.visibility = View.GONE

                // Show camera toggle button and audio output button during active chat
                cameraToggleButton.visibility = View.VISIBLE
                cameraToggleButton.isEnabled = true

                // Animate one-time layout transition to top-aligned mode (if first chat)
                if (!viewModel.hasEverChatted.value) {
                    transitionToTopAlignedLayout()
                }

                // Populate quick message chips (visibility handled inside)
                // Done after transition to avoid visibility being overridden by constraint set
                populateQuickMessageChips()

                // Set up model camera request callback on first chat activation
                setupModelCameraCallback()
                // Listener is already active
            }
            is UiState.ExecutingAction -> {
                audioVisualizer.setState(VisualizerState.EXECUTING)
                mainButton.isEnabled = true
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                mainButton.text = "Stop Chat"
                statusText.text = "Executing ${state.tool}..."
                // Hide pre-video button during chat
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                // Keep camera and audio output visible and enabled during action execution
                cameraToggleButton.visibility = View.VISIBLE
                cameraToggleButton.isEnabled = true
            }
            is UiState.Error -> {
                audioVisualizer.setState(VisualizerState.DORMANT)
                mainButton.isEnabled = false
                mainButton.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                statusText.text = state.message
                preVideoButton.visibility = View.GONE
                quickMessageScrollView.visibility = View.GONE
                clearButton.visibility = View.GONE
                cameraToggleButton.visibility = View.GONE
                hideCameraPreview()
            }
        }
    }

    // Chat button click listener for toggle functionality
    private val chatButtonClickListener = View.OnClickListener {
        // Check required permissions before starting
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }

        if (audioGranted && notificationGranted) {
            viewModel.onChatButtonClicked()
        } else {
            // Request missing permissions
            val permissionsToRequest = mutableListOf<String>()
            if (!audioGranted) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun handleAutoStart() {
        // Ensure we're in the ready state
        if (viewModel.uiState.value != UiState.ReadyToTalk) {
            return
        }

        // Check required permissions
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }

        if (audioGranted && notificationGranted) {
            // Small delay to ensure UI is fully settled
            lifecycleScope.launch {
                kotlinx.coroutines.delay(300)
                viewModel.onChatButtonClicked()
            }
        } else {
            // Don't request permission automatically - just inform user
            val missingPermissions = mutableListOf<String>()
            if (!audioGranted) missingPermissions.add("microphone")
            if (!notificationGranted) missingPermissions.add("notifications")

            Toast.makeText(
                this,
                "Auto-start requires ${missingPermissions.joinToString(" and ")} permission(s). Please grant and restart.",
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

    private fun updateTranscriptionLogs(logs: List<TranscriptItem>) {
        val items = mutableListOf<TranscriptDisplayItem>()

        if (logs.isEmpty()) {
            transcriptionAdapter.updateItems(emptyList())
            return
        }

        var currentSpeaker: TranscriptionSpeaker? = null
        var currentText = StringBuilder()

        fun flushCurrentSpeech() {
            if (currentSpeaker != null && currentText.isNotEmpty()) {
                items.add(TranscriptDisplayItem.SpeechTurn(currentSpeaker!!, currentText.toString()))
                currentText = StringBuilder()
                currentSpeaker = null
            }
        }

        logs.forEach { item ->
            when (item) {
                is TranscriptItem.Speech -> {
                    if (item.speaker != currentSpeaker) {
                        flushCurrentSpeech()
                        currentSpeaker = item.speaker
                        currentText = StringBuilder(item.chunk)
                    } else {
                        currentText.append(item.chunk)
                    }
                }
                is TranscriptItem.ToolCall -> {
                    flushCurrentSpeech()  // Tool calls break speech grouping
                    items.add(TranscriptDisplayItem.ToolCallItem(
                        toolName = item.toolName,
                        targetName = item.targetName,
                        parameters = item.parameters,
                        success = item.success,
                        result = item.result
                    ))
                }
            }
        }

        flushCurrentSpeech()  // Don't forget final speech turn

        // Update adapter
        transcriptionAdapter.updateItems(items)

        // Auto-scroll to bottom to show most recent message
        if (items.isNotEmpty()) {
            transcriptionRecyclerView.post {
                transcriptionRecyclerView.smoothScrollToPosition(items.size - 1)
            }
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

    // ==================== Camera Methods ====================

    /**
     * Show bottom sheet for camera source selection.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun showCameraSourceMenu(anchor: View) {
        val options = viewModel.getAvailableVideoSources()

        val bottomSheet = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_camera_source, null)

        val recyclerView = sheetView.findViewById<RecyclerView>(R.id.sourceList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = CameraSourceAdapter(options) { selectedType ->
            bottomSheet.dismiss()
            selectCameraSource(selectedType)
        }

        bottomSheet.setContentView(sheetView)
        bottomSheet.show()
    }

    /**
     * Adapter for camera source selection bottom sheet.
     */
    private inner class CameraSourceAdapter(
        private val options: List<uk.co.mrsheep.halive.services.camera.VideoSourceOption>,
        private val onSelect: (VideoSourceType) -> Unit
    ) : RecyclerView.Adapter<CameraSourceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.sourceIcon)
            val name: TextView = view.findViewById(R.id.sourceName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_camera_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = options[position]
            holder.name.text = option.displayName

            // Set appropriate icon based on source type
            val iconRes = when (option.type) {
                is VideoSourceType.None -> R.drawable.ic_videocam_off
                is VideoSourceType.DeviceCamera -> R.drawable.ic_phone
                is VideoSourceType.HACamera -> R.drawable.ic_videocam
            }
            holder.icon.setImageResource(iconRes)

            holder.itemView.setOnClickListener {
                onSelect(option.type)
            }
        }

        override fun getItemCount() = options.size
    }

    /**
     * Select and activate a camera source.
     */
    private fun selectCameraSource(sourceType: VideoSourceType) {
        // Clear model watching state if user is manually selecting a camera
        viewModel.clearModelWatchingCamera()

        // Stop current source if any
        stopCurrentVideoSource()

        when (sourceType) {
            is VideoSourceType.None -> {
                // Just turn off - already stopped above
                currentSourceType = VideoSourceType.None
                updateCameraUI(false)
                hideCameraPreview()
            }

            is VideoSourceType.DeviceCamera -> {
                // Check camera permission first
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    startDeviceCameraSource(sourceType.facing)
                } else {
                    // Store pending source type and request permission
                    pendingSourceType = sourceType
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            is VideoSourceType.HACamera -> {
                startHACameraSource(sourceType)
            }
        }
    }

    /**
     * Start a device camera source (front or back).
     */
    private fun startDeviceCameraSource(facing: CameraFacing) {
        val source = DeviceCameraSource(
            context = this,
            lifecycleOwner = this,
            previewView = cameraPreview,
            facing = facing
        )

        currentVideoSource = source
        currentSourceType = VideoSourceType.DeviceCamera(facing)

        lifecycleScope.launch {
            try {
                source.start()
                viewModel.startVideoCapture(source)
                viewModel.setCameraFacing(facing)
                CameraConfig.saveFacing(this@MainActivity, facing)

                // Show preview card with live preview
                cameraPreviewCard.visibility = View.VISIBLE
                cameraPreview.visibility = View.VISIBLE
                // Hide HA camera static image if it exists
                haCameraPreviewImage?.visibility = View.GONE
                // Show flip button for device cameras
                cameraFlipButton.visibility = View.VISIBLE

                updateCameraUI(true)
                Log.d("MainActivity", "Device camera started: $facing")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start device camera", e)
                Toast.makeText(this@MainActivity, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
                stopCurrentVideoSource()
            }
        }
    }

    /**
     * Start a Home Assistant camera source.
     */
    private fun startHACameraSource(sourceType: VideoSourceType.HACamera) {
        val app = application as HAGeminiApp
        val haApiClient = app.haApiClient

        if (haApiClient == null) {
            Toast.makeText(this, "Home Assistant not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = CameraConfig.getSettings(this)

        val source = HACameraSource(
            entityId = sourceType.entityId,
            friendlyName = sourceType.friendlyName,
            haApiClient = haApiClient,
            maxDimension = settings.resolution.maxDimension,
            frameIntervalMs = settings.frameRate.intervalMs
        )

        // Set up error callback - also check source is still current
        source.onError = { e ->
            if (currentVideoSource === source) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.camera_error_fetch_failed), Toast.LENGTH_SHORT).show()
                    stopCurrentVideoSource()
                }
            }
        }

        // Note: We don't use source.onFrameAvailable for preview anymore.
        // Instead, we use the onFrameSent callback from startVideoCapture to ensure
        // the preview shows exactly what frames are sent to the model.

        currentVideoSource = source
        currentSourceType = sourceType

        lifecycleScope.launch {
            try {
                source.start()
                // Pass onFrameSent callback to update preview with exactly what the model sees
                viewModel.startVideoCapture(source) { frame ->
                    runOnUiThread {
                        updateHACameraPreview(frame)
                    }
                }

                // Show preview card
                cameraPreviewCard.visibility = View.VISIBLE
                // Hide live preview, use static image for HA cameras
                cameraPreview.visibility = View.GONE
                setupHACameraPreview()
                // Hide flip button for HA cameras (can't be flipped)
                cameraFlipButton.visibility = View.GONE

                updateCameraUI(true)
                Log.d("MainActivity", "HA camera started: ${sourceType.entityId}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start HA camera", e)
                Toast.makeText(this@MainActivity, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
                stopCurrentVideoSource()
            }
        }
    }

    /**
     * Set up ImageView for HA camera preview.
     */
    private fun setupHACameraPreview() {
        if (haCameraPreviewImage == null) {
            haCameraPreviewImage = ImageView(this).apply {
                layoutParams = cameraPreview.layoutParams
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            (cameraPreviewCard as android.view.ViewGroup).addView(haCameraPreviewImage)
        }
        haCameraPreviewImage?.visibility = View.VISIBLE
    }

    /**
     * Update HA camera preview with latest frame.
     */
    private fun updateHACameraPreview(jpegData: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        haCameraPreviewImage?.setImageBitmap(bitmap)
    }

    /**
     * Stop current video source and clean up.
     */
    private fun stopCurrentVideoSource() {
        viewModel.stopVideoCapture()
        currentVideoSource?.stop()
        currentVideoSource = null
        currentSourceType = VideoSourceType.None
    }

    /**
     * Switch between front and back camera.
     */
    private fun switchCamera() {
        val source = currentVideoSource as? DeviceCameraSource ?: return
        val newFacing = if (viewModel.cameraFacing.value == CameraFacing.FRONT) {
            CameraFacing.BACK
        } else {
            CameraFacing.FRONT
        }

        // Stop current, switch facing, restart
        stopCurrentVideoSource()
        startDeviceCameraSource(newFacing)

        Log.d("MainActivity", "Camera switched to $newFacing")
    }

    /**
     * Hide camera preview without stopping capture.
     * Used when transitioning to non-chat states.
     */
    private fun hideCameraPreview() {
        stopCurrentVideoSource()
        cameraPreviewCard.visibility = View.GONE
        haCameraPreviewImage?.visibility = View.GONE
    }

    /**
     * Handle model camera state changes.
     * When model requests a camera, start streaming it.
     * When model stops watching, stop the camera if it was model-controlled.
     */
    private fun handleModelCameraStateChange(entityId: String?) {
        if (entityId != null) {
            // Model wants to watch a camera - find it and start
            val camera = viewModel.availableHACameras.value.find { it.entityId == entityId }
            if (camera != null) {
                // Stop any current source first
                stopCurrentVideoSource()

                // Start the HA camera
                val sourceType = VideoSourceType.HACamera(entityId, camera.friendlyName)
                startHACameraSource(sourceType)

                Log.d("MainActivity", "Model started watching camera: ${camera.friendlyName}")
            }
        } else {
            // Model stopped watching - if current source is model-controlled, stop it
            val currentSource = currentSourceType
            if (currentSource is VideoSourceType.HACamera) {
                stopCurrentVideoSource()
                hideCameraPreview()
                Log.d("MainActivity", "Model stopped watching camera")
            }
        }
    }

    /**
     * Set up callback for model camera requests.
     * This shows a dialog when the model wants to switch camera view.
     */
    private fun setupModelCameraCallback() {
        viewModel.setModelCameraRequestCallback { entityId, friendlyName, onApproved, onDenied ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Camera Request")
                    .setMessage("The AI assistant is requesting to view '$friendlyName'. Do you want to switch the camera view?")
                    .setPositiveButton("Allow") { _, _ ->
                        onApproved()
                    }
                    .setNegativeButton("Deny") { _, _ ->
                        onDenied()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Update camera UI based on enabled state.
     */
    private fun updateCameraUI(enabled: Boolean) {
        if (enabled) {
            // Camera is on - filled style
            cameraToggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.teal_primary)
            cameraToggleButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            cameraToggleButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            cameraToggleButton.strokeWidth = 0
        } else {
            // Camera is off - outlined style
            cameraToggleButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
            cameraToggleButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal_primary))
            cameraToggleButton.setTextColor(ContextCompat.getColor(this, R.color.teal_primary))
            cameraToggleButton.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Show camera source selector for pre-chat video selection.
     * Fetches HA cameras on-demand before showing the selector.
     */
    private fun showPreChatCameraSourceMenu() {
        // Disable button while loading to prevent double-taps
        preVideoButton.isEnabled = false

        lifecycleScope.launch {
            // Fetch HA cameras on-demand
            val result = viewModel.fetchAvailableHACameras()

            // Re-enable button
            preVideoButton.isEnabled = true

            // Show error toast if fetch failed (but still show selector with device cameras)
            if (result.isFailure) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.camera_list_fetch_error,
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Get options (will use fetched cameras or empty list on failure)
            val options = viewModel.getAvailableVideoSources()

            val bottomSheet = BottomSheetDialog(this@MainActivity)
            val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_camera_source, null)

            val recyclerView = sheetView.findViewById<RecyclerView>(R.id.sourceList)
            recyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
            recyclerView.adapter = CameraSourceAdapter(options) { selectedType ->
                bottomSheet.dismiss()
                viewModel.setPreChatVideoSource(selectedType)
            }

            bottomSheet.setContentView(sheetView)
            bottomSheet.show()
        }
    }

    /**
     * Update pre-video button appearance based on enabled state.
     */
    private fun updatePreVideoButtonAppearance(enabled: Boolean) {
        if (enabled) {
            // Video enabled - filled style
            preVideoButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.teal_primary)
            preVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            preVideoButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            preVideoButton.strokeWidth = 0
        } else {
            // Video disabled - outlined style
            preVideoButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
            preVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal_primary))
            preVideoButton.setTextColor(ContextCompat.getColor(this, R.color.teal_primary))
            preVideoButton.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Update pre-video button text based on selected source.
     */
    private fun updatePreVideoButtonText(source: VideoSourceType) {
        preVideoButton.text = when (source) {
            is VideoSourceType.None -> "Video"
            is VideoSourceType.DeviceCamera -> if (source.facing == CameraFacing.FRONT) "Front" else "Back"
            is VideoSourceType.HACamera -> source.friendlyName.take(10) // Truncate long names
        }
    }

    /**
     * Auto-start video capture with the pre-selected source.
     * Called when chat becomes active and video start is enabled.
     */
    private fun autoStartPreSelectedVideo() {
        lifecycleScope.launch {
            // Small delay to ensure session is fully initialized
            kotlinx.coroutines.delay(500)

            // Verify session is still active after delay - user may have stopped chat
            if (viewModel.uiState.value != UiState.ChatActive) {
                Log.d("MainActivity", "Auto-start video cancelled - session no longer active")
                return@launch
            }

            // Read source AFTER delay to ensure we get the latest user selection
            val source = viewModel.selectedVideoSource.value
            if (source == VideoSourceType.None) return@launch

            when (source) {
                is VideoSourceType.DeviceCamera -> {
                    // Check camera permission
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                        startDeviceCameraSource(source.facing)
                    } else {
                        // Request permission - store pending source and mark as auto-start
                        pendingSourceType = source
                        isAutoStartPermissionRequest = true
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                is VideoSourceType.HACamera -> {
                    // Fetch fresh camera status before checking availability
                    val fetchResult = viewModel.fetchAvailableHACameras()

                    // Check if HA camera is available using fresh data
                    val available = viewModel.availableHACameras.value.any {
                        it.entityId == source.entityId && it.state != "unavailable"
                    }

                    if (fetchResult.isSuccess && available) {
                        startHACameraSource(source)
                    } else {
                        Toast.makeText(this@MainActivity, R.string.video_source_unavailable, Toast.LENGTH_SHORT).show()
                        viewModel.setPreChatVideoSource(VideoSourceType.None)
                    }
                }
                is VideoSourceType.None -> { /* Do nothing */ }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up video source resources
        stopCurrentVideoSource()
    }
}
