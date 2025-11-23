package uk.co.mrsheep.halive.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
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
import uk.co.mrsheep.halive.core.DummyToolsConfig
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.TranscriptionEntry
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
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

    enum class ServiceState {
        IDLE,
        WAKE_WORD_LISTENING,
        CONVERSATION_ACTIVE,
        ERROR
    }

    companion object {
        private const val TAG = "LiveSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live_session_channel"
        private const val ACTION_STOP = "uk.co.mrsheep.halive.STOP_SESSION"
        const val ACTION_START_ALWAYS_ON = "uk.co.mrsheep.halive.START_ALWAYS_ON"
        const val ACTION_STOP_ALWAYS_ON = "uk.co.mrsheep.halive.STOP_ALWAYS_ON"
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

    // Wake word and state management
    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private var wakeWordService: WakeWordService? = null
    private var isAlwaysOnMode = false

    // State flows
    private val _transcriptionLogs = MutableStateFlow<List<TranscriptionEntry>>(emptyList())
    val transcriptionLogs: StateFlow<List<TranscriptionEntry>> = _transcriptionLogs.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _connectionState = MutableStateFlow<UiState>(UiState.ReadyToTalk)
    val connectionState: StateFlow<UiState> = _connectionState.asStateFlow()

    private val _toolLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val toolLogs: StateFlow<List<LogEntry>> = _toolLogs.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): LiveSessionService = this@LiveSessionService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_START_ALWAYS_ON -> {
                isAlwaysOnMode = true

                // Load profile from intent
                val profileId = intent?.getStringExtra("profile_id")
                if (profileId != null) {
                    currentProfile = uk.co.mrsheep.halive.core.ProfileManager.getProfileById(profileId)
                    if (currentProfile == null) {
                        Log.e(TAG, "Failed to load profile with ID: $profileId")
                        _serviceState.value = ServiceState.ERROR
                        _connectionState.value = UiState.Error("Profile not found")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                } else {
                    Log.e(TAG, "No profile ID provided for always-on mode")
                    _serviceState.value = ServiceState.ERROR
                    _connectionState.value = UiState.Error("No profile specified")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createNotification())
                startWakeWordListening()
                return START_STICKY
            }
            ACTION_STOP_ALWAYS_ON -> {
                stopWakeWordListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Default behavior - start foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                return START_STICKY
            }
        }
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

        // Determine notification content based on state
        val (title, text) = when (_serviceState.value) {
            ServiceState.WAKE_WORD_LISTENING -> {
                "Wake Word Active" to "Listening for 'Okay Computer'..."
            }
            ServiceState.CONVERSATION_ACTIVE -> {
                val profileName = profile?.name ?: "Voice Assistant"
                "$profileName Active" to "Listening..."
            }
            else -> {
                "Voice Assistant" to "Active"
            }
        }

        val stopIntent = Intent(this, LiveSessionService::class.java).apply {
            action = if (isAlwaysOnMode) ACTION_STOP_ALWAYS_ON else ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = android.app.Person.Builder()
                .setName(profile?.name ?: "HA Live")
                .setImportant(true)
                .build()

            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
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
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
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
     */
    fun startSession(profile: Profile) {
        serviceScope.launch {
            try {
                _connectionState.value = UiState.Initializing

                val app = application as HAGeminiApp

                conversationService = ConversationServiceFactory.create(this@LiveSessionService)

                // Create fresh MCP connection for this session
                val mcp = McpClientManager(app.haUrl!!, app.haToken!!)
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

                sessionPreparer.prepareAndInitialize(profile, conversationService!!)

                // Store current profile for notification
                currentProfile = profile

                // Update notification with profile information
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, createNotification(profile))

                _isSessionActive.value = true
                _connectionState.value = UiState.ChatActive
                conversationService!!.startSession()

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
        try {
            conversationService?.stopSession()
        } catch (e: Exception) {
            _connectionState.value = UiState.Error("Failed to stop session: ${e.message}")
        }

        // Clean up MCP connection
        toolExecutor = null
        mcpClient?.shutdown()
        mcpClient = null

        // Reset audio level
        _audioLevel.value = 0f

        _isSessionActive.value = false
        _connectionState.value = UiState.ReadyToTalk

        // Check if we should restart wake word listening
        if (isAlwaysOnMode) {
            Log.d(TAG, "Always-on mode: restarting wake word listening")
            startWakeWordListening()
        } else {
            // Stop the foreground service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        _transcriptionLogs.value += TranscriptionEntry(
            spokenBy = if (isThought) TranscriptionSpeaker.MODELTHOUGHT else TranscriptionSpeaker.MODEL,
            chunk = chunk,
        )
    }

    override fun addUserTranscription(chunk: String) {
        _transcriptionLogs.value += TranscriptionEntry(
            spokenBy = TranscriptionSpeaker.USER,
            chunk = chunk,
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up wake word service
        stopWakeWordListening()

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
     * Starts listening for wake word in background service mode.
     */
    private fun startWakeWordListening() {
        if (_serviceState.value == ServiceState.WAKE_WORD_LISTENING) {
            Log.w(TAG, "Already listening for wake word")
            return
        }

        try {
            wakeWordService = WakeWordService(this) {
                onWakeWordDetected()
            }
            wakeWordService?.startListening(serviceScope)
            _serviceState.value = ServiceState.WAKE_WORD_LISTENING

            // Update notification
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())

            Log.d(TAG, "Started wake word listening in background service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word listening: ${e.message}", e)
            _serviceState.value = ServiceState.ERROR
            _connectionState.value = UiState.Error("Failed to start wake word: ${e.message}")
        }
    }

    /**
     * Stops listening for wake word.
     */
    private fun stopWakeWordListening() {
        wakeWordService?.stopListening()
        wakeWordService?.destroy()
        wakeWordService = null
        Log.d(TAG, "Stopped wake word listening")
    }

    /**
     * Callback when wake word is detected.
     */
    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word detected in background service!")

        // Stop wake word listening immediately to release microphone
        stopWakeWordListening()
        _serviceState.value = ServiceState.CONVERSATION_ACTIVE

        // Play ready beep
        BeepHelper.playReadyBeep(this)

        // Start conversation session with current profile
        currentProfile?.let { profile ->
            startSession(profile)
        } ?: run {
            Log.e(TAG, "No profile set for wake word activation")
            _serviceState.value = ServiceState.ERROR
            _connectionState.value = UiState.Error("No profile configured")
        }
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

                    Use when the conversation has come to its natural end, for example, if the user says 'thanks' with no obvious follow up.

                    Wish the user goodbye before calling this tool so they know the conversation is finished.
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
                        delay(300)
                        stopSession()
                    }

                    // Return success response immediately
                    ToolCallResult(
                        isError = false,
                        content = listOf(ToolContent(type = "text", text = "Conversation ended: $reason"))
                    )
                }
            )
        )
    }
}
