package uk.co.mrsheep.halive.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.services.CameraEntity
import uk.co.mrsheep.halive.core.DummyToolsConfig
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.TranscriptItem
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper
import uk.co.mrsheep.halive.services.camera.CameraFacing
import uk.co.mrsheep.halive.services.camera.VideoSource
import uk.co.mrsheep.halive.services.conversation.ConversationService
import uk.co.mrsheep.halive.services.conversation.ConversationServiceFactory
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import uk.co.mrsheep.halive.services.mcp.McpInputSchema
import uk.co.mrsheep.halive.services.mcp.McpProperty
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult
import uk.co.mrsheep.halive.services.mcp.ToolContent
import uk.co.mrsheep.halive.ui.MainActivity
import uk.co.mrsheep.halive.ui.UiState

/**
 * Foreground service that hosts the live conversation session.
 *
 * This service allows the conversation to continue even when the app is backgrounded.
 * It manages the ConversationService, MCP client, and tool executor, and exposes
 * state flows for UI updates.
 */
class LiveSessionService : Service(), AppLogger {

    companion object {
        private const val TAG = "LiveSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live_session_channel"
        private const val ACTION_STOP = "uk.co.mrsheep.halive.STOP_SESSION"
    }

    // Binder for activity communication
    private val binder = LocalBinder()

    // Service scope for coroutines
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Session components
    private var conversationService: ConversationService? = null
    private var mcpClient: McpClientManager? = null
    private var toolExecutor: ToolExecutor? = null
    private var currentProfile: Profile? = null
    private var allowedModelCameras: Set<String> = emptySet()

    // State flows
    private val _transcriptionLogs = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcriptionLogs: StateFlow<List<TranscriptItem>> = _transcriptionLogs.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _connectionState = MutableStateFlow<UiState>(UiState.ReadyToTalk)
    val connectionState: StateFlow<UiState> = _connectionState.asStateFlow()

    private val _toolLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val toolLogs: StateFlow<List<LogEntry>> = _toolLogs.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    // Camera state
    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _cameraFacing = MutableStateFlow(CameraFacing.FRONT)
    val cameraFacing: StateFlow<CameraFacing> = _cameraFacing.asStateFlow()

    // Available Home Assistant cameras (populated during session start)
    private val _availableHACameras = MutableStateFlow<List<CameraEntity>>(emptyList())
    val availableHACameras: StateFlow<List<CameraEntity>> = _availableHACameras.asStateFlow()

    // Video source is provided externally (managed by MainActivity for lifecycle binding)
    private var videoSource: VideoSource? = null

    // Model-controlled camera state
    private val _modelWatchingCamera = MutableStateFlow<String?>(null)  // entity_id or null
    val modelWatchingCamera: StateFlow<String?> = _modelWatchingCamera.asStateFlow()

    // Callback for requesting camera switch (set by MainActivity)
    var onModelCameraRequest: ((entityId: String, friendlyName: String, onApproved: () -> Unit, onDenied: () -> Unit) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): LiveSessionService = this@LiveSessionService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    /**
     * Creates a notification for the foreground service.
     * Uses Notification.CallStyle for API >= 31, standard notification otherwise.
     * Optionally includes profile information if available.
     */
    private fun createNotification(profile: Profile? = currentProfile): Notification {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LiveSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Use full profile name - Android automatically extracts initials for the letter bubble
        val displayName = profile?.name ?: "Voice Assistant"
        val notificationTitle = if (profile != null) "${profile.name} Active" else "Voice Assistant Active"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: Use CallStyle notification - Android extracts initials automatically
            val person = android.app.Person.Builder()
                .setName(displayName) // Android shows initials in letter bubble (e.g., "House Lizard" -> "HL")
                .setImportant(true)
                .build()

            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText("Listening...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setStyle(
                    Notification.CallStyle.forOngoingCall(
                        person,
                        stopPendingIntent
                    )
                )
                .build()
        } else {
            // API < 31: Standard notification with Stop action
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText("Listening...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Stop",
                    stopPendingIntent
                )
                .build()
        }
    }

    /**
     * Creates the notification channel for Android O and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Assistant Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing voice assistant conversation"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Starts a conversation session with the given profile.
     *
     * @param profile The profile configuration for the session
     * @param externalMicrophoneHelper Optional MicrophoneHelper to handover from external source
     */
    fun startSession(profile: Profile, externalMicrophoneHelper: MicrophoneHelper? = null) {
        // Guard against duplicate session starts
        if (_isSessionActive.value) {
            Log.w(TAG, "startSession called but session already active - ignoring")
            return
        }

        serviceScope.launch {
            try {
                _connectionState.value = UiState.Initializing

                val app = application as HAGeminiApp

                conversationService = ConversationServiceFactory.create(this@LiveSessionService)

                // Create fresh MCP connection for this session using OAuth
                val tokenManager = app.getTokenManager()
                    ?: throw IllegalStateException("OAuth not configured")
                val mcp = McpClientManager(app.haUrl!!, tokenManager)
                mcp.connect()
                mcpClient = mcp

                val localTools = getLocalTools()

                // Determine base executor (wrap MCP with MockToolExecutor if dummy tools enabled)
                val baseExecutor: ToolExecutor = if (DummyToolsConfig.isEnabled(this@LiveSessionService)) {
                    // Build passthrough tools list: GetDateTime, GetLiveContext, and all local tools
                    val passthroughTools = buildList {
                        add("GetDateTime")
                        add("GetLiveContext")
                        addAll(localTools.keys)  // All local tool names (e.g., EndConversation)
                    }
                    MockToolExecutor(
                        realExecutor = mcp,
                        passthroughTools = passthroughTools
                    )
                } else {
                    mcp
                }

                // Wrap the base executor (which may be mocked) with AppToolExecutor
                toolExecutor = AppToolExecutor(
                    toolExecutor = baseExecutor,
                    logger = this@LiveSessionService,
                    setUIState = { uiState: UiState -> _connectionState.value = uiState },
                    localTools = localTools,
                )

                // Now sessionPreparer needs to be created with the new mcpClient
                val sessionPreparer = SessionPreparer(
                    toolExecutor = toolExecutor!!,
                    haApiClient = app.haApiClient!!,
                    logger = this@LiveSessionService,
                    localTools = localTools.keys,
                    onAudioLevel = { level -> _audioLevel.value = level }
                )

                val haCameras = sessionPreparer.prepareAndInitialize(profile, conversationService!!)
                _availableHACameras.value = haCameras
                Log.d(TAG, "Fetched ${haCameras.size} HA cameras for video source selection")

                // Store current profile for notification
                currentProfile = profile

                // Store allowed cameras for this session
                allowedModelCameras = profile.allowedModelCameras

                // Update notification with profile information
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, createNotification(profile))

                _isSessionActive.value = true
                _connectionState.value = UiState.ChatActive
                conversationService!!.startSession(microphoneHelper = externalMicrophoneHelper)

                // Play ready beep to indicate session is active
                BeepHelper.playReadyBeep(this@LiveSessionService)

                // Send initial message to agent if configured
                profile.initialMessageToAgent.let { initialText ->
                    if (initialText.isNotBlank()) {
                        try {
                            conversationService!!.sendText(initialText)

                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())

                            // Log it to tool logs
                            addLogEntry(
                                LogEntry(
                                    timestamp = timestamp,
                                    toolName = "System Startup",
                                    parameters = "Initial Message to Agent",
                                    success = true,
                                    result = "Sent: $initialText"
                                )
                            )
                        } catch (e: Exception) {
                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())

                            addLogEntry(
                                LogEntry(
                                    timestamp = timestamp,
                                    toolName = "System Startup",
                                    parameters = "Initial Message to Agent",
                                    success = false,
                                    result = "Failed to send: ${e.message}"
                                )
                            )
                            // Don't fail the session, just log it
                        }
                    }
                }
            } catch (e: Exception) {
                // Log the full exception details to tool log for debugging
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())

                addLogEntry(
                    LogEntry(
                        timestamp = timestamp,
                        toolName = "Session Start Error",
                        parameters = "Failed to start conversation session",
                        success = false,
                        result = "Exception: ${e.javaClass.simpleName}\n" +
                                "Message: ${e.message}\n\n" +
                                "Stack trace:\n${e.stackTraceToString()}"
                    )
                )

                // Clean up MCP connection if initialization failed
                toolExecutor = null
                mcpClient?.shutdown()
                mcpClient = null

                _isSessionActive.value = false
                _connectionState.value = UiState.Error("Failed to start session: ${e.message}")
            }
        }
    }

    /**
     * Stops the current conversation session.
     */
    fun stopSession() {
        BeepHelper.playEndBeep(this)

        // Stop video capture first
        if (_isCameraEnabled.value) {
            stopVideoCapture()
        }

        try {
            conversationService?.stopSession()
        } catch (e: Exception) {
            _connectionState.value = UiState.Error("Failed to stop session: ${e.message}")
        }

        // Clean up MCP connection
        toolExecutor = null
        mcpClient?.shutdown()
        mcpClient = null

        // Clear profile
        currentProfile = null

        // Reset audio level
        _audioLevel.value = 0f

        // Clear available cameras
        _availableHACameras.value = emptyList()

        // Clear model camera state
        _modelWatchingCamera.value = null

        _isSessionActive.value = false
        _connectionState.value = UiState.ReadyToTalk

        // Stop the foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Yields the MicrophoneHelper from the conversation service for handover.
     * Returns null if conversation service doesn't support handover or no session active.
     */
    fun yieldMicrophoneHelper(): MicrophoneHelper? {
        return conversationService?.yieldMicrophoneHelper()
    }

    /**
     * Start video capture and streaming to the conversation.
     *
     * @param source The VideoSource instance to use for video capture
     * @param onFrameSent Optional callback invoked when a frame is actually sent to the model.
     *                    Use this to update preview UI to show exactly what the model sees.
     */
    fun startVideoCapture(source: VideoSource, onFrameSent: ((ByteArray) -> Unit)? = null) {
        if (!_isSessionActive.value) {
            Log.w(TAG, "Cannot start video capture - no active session")
            return
        }

        videoSource = source
        conversationService?.startVideoCapture(source, onFrameSent)
        _isCameraEnabled.value = true

        Log.d(TAG, "Video capture started from source: ${source.sourceId}")
    }

    /**
     * Stop video capture and streaming.
     */
    fun stopVideoCapture() {
        conversationService?.stopVideoCapture()
        videoSource = null
        _isCameraEnabled.value = false

        Log.d(TAG, "Video capture stopped")
    }

    /**
     * Update the camera facing direction state.
     * The actual camera switching is handled by MainActivity which owns the CameraHelper lifecycle.
     *
     * @param facing The new camera facing direction
     */
    fun setCameraFacing(facing: CameraFacing) {
        _cameraFacing.value = facing
        Log.d(TAG, "Camera facing updated to: $facing")
    }

    /**
     * Send a text message to the conversation service.
     */
    fun sendText(message: String) {
        serviceScope.launch {
            try {
                conversationService?.sendText(message)

                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())

                addLogEntry(
                    LogEntry(
                        timestamp = timestamp,
                        toolName = "Quick Message",
                        parameters = "User Quick Message",
                        success = true,
                        result = "Sent: $message"
                    )
                )
            } catch (e: Exception) {
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())

                addLogEntry(
                    LogEntry(
                        timestamp = timestamp,
                        toolName = "Quick Message",
                        parameters = "User Quick Message",
                        success = false,
                        result = "Failed to send: ${e.message}"
                    )
                )
                Log.e(TAG, "Error sending message: ${e.message}", e)
            }
        }
    }

    /**
     * Clear all transcription logs.
     */
    fun clearTranscriptionLogs() {
        _transcriptionLogs.value = emptyList()
    }

    override fun addLogEntry(log: LogEntry) {
        _toolLogs.value += log
    }

    override fun addModelTranscription(chunk: String, isThought: Boolean) {
        _transcriptionLogs.value += TranscriptItem.Speech(
            speaker = if (isThought) TranscriptionSpeaker.MODELTHOUGHT else TranscriptionSpeaker.MODEL,
            chunk = chunk,
        )
    }

    override fun addUserTranscription(chunk: String) {
        _transcriptionLogs.value += TranscriptItem.Speech(
            speaker = TranscriptionSpeaker.USER,
            chunk = chunk,
        )
    }

    override fun addToolCallToTranscript(toolName: String, targetName: String?, parameters: String, success: Boolean, result: String) {
        _transcriptionLogs.value += TranscriptItem.ToolCall(
            toolName = toolName,
            targetName = targetName,
            parameters = parameters,
            success = success,
            result = result,
            timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up conversation service
        conversationService?.cleanup()
        conversationService = null

        // Clean up MCP connection
        mcpClient?.shutdown()
        mcpClient = null

        // Clear profile
        currentProfile = null

        // Cancel all coroutines
        serviceScope.cancel()
    }

    /**
     * Start watching a camera (called by model via tool).
     * Returns result indicating success or failure.
     */
    fun modelStartWatchingCamera(entityId: String): ToolCallResult {
        // Check if camera is in allowed list
        if (entityId !in allowedModelCameras) {
            return ToolCallResult(
                isError = true,
                content = listOf(ToolContent(type = "text", text = "Camera '$entityId' is not in your allowed camera list. Check the <available_cameras> section in your system prompt to see which cameras you can access."))
            )
        }

        // Find camera info
        val camera = _availableHACameras.value.find { it.entityId == entityId }
        if (camera == null) {
            return ToolCallResult(
                isError = true,
                content = listOf(ToolContent(type = "text", text = "Camera '$entityId' not found in available cameras."))
            )
        }

        // Check if user has device camera active - need to prompt
        if (_isCameraEnabled.value && _modelWatchingCamera.value == null) {
            // User has a camera active, need to request permission via callback
            val callback = onModelCameraRequest
            if (callback == null) {
                // No callback set up yet - just proceed without prompting
                // This shouldn't happen in normal flow, but handle gracefully
            } else {
                var approved = false
                val latch = CountDownLatch(1)

                callback.invoke(
                    entityId,
                    camera.friendlyName,
                    { approved = true; latch.countDown() },
                    { approved = false; latch.countDown() }
                )

                // This is synchronous for simplicity - the callback will be called on main thread
                // In practice, this should complete quickly as user sees dialog
                try {
                    latch.await(30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    return ToolCallResult(
                        isError = true,
                        content = listOf(ToolContent(type = "text", text = "Timeout waiting for user approval to switch camera view."))
                    )
                }

                if (!approved) {
                    return ToolCallResult(
                        isError = false,
                        content = listOf(ToolContent(type = "text", text = "The user declined the request to switch to camera '${camera.friendlyName}'."))
                    )
                }
            }
        }

        // Set model watching state
        _modelWatchingCamera.value = entityId

        return ToolCallResult(
            isError = false,
            content = listOf(ToolContent(type = "text", text = "Now viewing camera '${camera.friendlyName}' (${entityId}). Video frames are being streamed to you. Call StopWatchingCamera when you no longer need to see this view."))
        )
    }

    /**
     * Stop watching the current camera.
     */
    fun modelStopWatchingCamera(): ToolCallResult {
        val wasWatching = _modelWatchingCamera.value
        _modelWatchingCamera.value = null

        return if (wasWatching != null) {
            ToolCallResult(
                isError = false,
                content = listOf(ToolContent(type = "text", text = "Stopped viewing camera. Video streaming has ended."))
            )
        } else {
            ToolCallResult(
                isError = false,
                content = listOf(ToolContent(type = "text", text = "No camera was being viewed."))
            )
        }
    }

    /**
     * Clear model watching state (called when user overrides camera selection).
     */
    fun clearModelWatchingCamera() {
        _modelWatchingCamera.value = null
    }

    /**
     * Returns the local tools map (e.g., EndConversation).
     */
    private fun getLocalTools(): Map<String, LocalTool> {
        return mapOf(
            "EndConversation" to LocalTool(
                definition = McpTool(
                    name = "EndConversation",
                    description = """
                    Immediately ends the conversation.

                    Use when the conversation has come to its natural end, for example, if the user says "that's all", or "thanks" with no obvious follow up.

                    Tell the user 'goodbye' and call this tool to end the conversation.
                    """.trimIndent(),
                    inputSchema = McpInputSchema(
                        type = "object",
                        properties = mapOf("reason" to McpProperty(
                            description = "Brief reason why this conversation has come to an end"
                        ))
                    ),
                ),
                execute = { name: String, arguments: Map<String, JsonElement> ->
                    val reason = arguments["reason"]?.jsonPrimitive?.content ?: "Natural conclusion"

                    serviceScope.launch {
                        // Allow conversation service to send the function response
                        delay(2500)
                        stopSession()
                    }

                    // Return success response immediately
                    ToolCallResult(
                        isError = false,
                        content = listOf(ToolContent(type = "text", text = "Conversation ended: $reason"))
                    )
                }
            ),
            "StartWatchingCamera" to LocalTool(
                definition = McpTool(
                    name = "StartWatchingCamera",
                    description = """
                    Start viewing a Home Assistant camera. Video frames will be continuously streamed to you until you call StopWatchingCamera.

                    Check the <available_cameras> section in your system prompt to see which cameras you can access.

                    If you want to switch to a different camera, call StartWatchingCamera with the new camera - it will automatically switch.

                    Parameters:
                    - entity_id: The camera entity ID (e.g., 'camera.front_door')
                    """.trimIndent(),
                    inputSchema = McpInputSchema(
                        type = "object",
                        properties = mapOf(
                            "entity_id" to McpProperty(
                                type = "string",
                                description = "The Home Assistant camera entity ID (e.g., 'camera.front_door')"
                            )
                        ),
                        required = listOf("entity_id")
                    )
                ),
                execute = { _, arguments ->
                    val entityId = arguments["entity_id"]?.jsonPrimitive?.content
                    if (entityId == null) {
                        ToolCallResult(
                            isError = true,
                            content = listOf(ToolContent(type = "text", text = "Missing required parameter: entity_id"))
                        )
                    } else {
                        modelStartWatchingCamera(entityId)
                    }
                }
            ),
            "StopWatchingCamera" to LocalTool(
                definition = McpTool(
                    name = "StopWatchingCamera",
                    description = """
                    Stop viewing the current camera. Call this when you no longer need to see the camera feed.
                    """.trimIndent(),
                    inputSchema = McpInputSchema(
                        type = "object",
                        properties = emptyMap()
                    )
                ),
                execute = { _, _ -> modelStopWatchingCamera() }
            )
        )
    }
}
