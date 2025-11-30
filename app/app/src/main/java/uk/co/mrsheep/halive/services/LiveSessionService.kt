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
import uk.co.mrsheep.halive.services.audio.MicrophoneHelper
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

                sessionPreparer.prepareAndInitialize(profile, conversationService!!)

                // Store current profile for notification
                currentProfile = profile

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
u                        delay(2500)
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
