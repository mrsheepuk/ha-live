package uk.co.mrsheep.halive.services

import android.content.Context
import android.util.Log
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.SystemPromptConfig
import uk.co.mrsheep.halive.ui.ToolCallLog

/**
 * Manages the lifecycle of testing a profile with MockToolExecutor.
 *
 * ## Purpose
 * Provides a safe way to test conversation profiles without making actual calls to Home Assistant.
 * Uses MockToolExecutor to simulate tool responses while allowing developers to validate:
 * - System prompt effectiveness
 * - Profile configurations
 * - Conversation flows
 * - Tool integration without side effects
 *
 * ## Safety
 * - Read-only tools (GetLiveContext, GetDateTime) pass through to real HA for accurate data
 * - State-modifying tools never make real calls to Home Assistant
 * - Uses MockToolExecutor with selective pass-through capability
 * - Separate Gemini service instance for testing
 * - Gracefully handles initialization errors
 *
 * ## Lifecycle
 * 1. Create instance: `ProfileTestManager(app, onLogEntry, onStatusChange)`
 * 2. Start test: `startTest(profile, context)`
 * 3. User speaks (audio conversation starts)
 * 4. Stop test: `stopTest()`
 * 5. Cleanup: `cleanup()`
 *
 * ## Example Usage
 * ```kotlin
 * val testManager = ProfileTestManager(app, { logEntry ->
 *     println("Tool called: ${logEntry.toolName}")
 * }, { status ->
 *     println("Test status: $status")
 * })
 *
 * testManager.startTest(myProfile, context)
 * // User can now speak to the test session...
 * testManager.stopTest()
 * val calledTools = testManager.getCalledTools()
 * println("Tools called: $calledTools")
 * ```
 */
class ProfileTestManager(
    private val app: HAGeminiApp,
    private val onLogEntry: (ToolCallLog) -> Unit,
    private val onStatusChange: (TestStatus) -> Unit
) {

    companion object {
        private const val TAG = "ProfileTestManager"
    }

    /**
     * Sealed class representing the current state of the test session.
     */
    sealed class TestStatus {
        object Idle : TestStatus()
        object Initializing : TestStatus()
        data class Active(val message: String) : TestStatus()
        object Stopped : TestStatus()
        data class Error(val message: String) : TestStatus()
    }

    /**
     * Separate Gemini service instance for testing (not shared with main app).
     * Uses Firebase SDK mode (useDirectProtocol = false) for testing.
     */
    private val testGeminiService = GeminiService(app.applicationContext, useDirectProtocol = false)

    /**
     * Mock tool executor with pass-through for read-only tools.
     * Read-only tools (GetLiveContext, GetDateTime) are passed to real HA for accurate data.
     * All other tools are mocked for safety.
     */
    private var mockToolExecutor: MockToolExecutor? = null

    /**
     * Session preparer that handles initialization (fetching tools, rendering templates, etc.).
     */
    private var sessionPreparer: GeminiSessionPreparer? = null

    /**
     * Fresh MCP connection created for this test session (separate from app's shared connection).
     * Ensures test sessions use isolated, ephemeral connections that can be safely cleaned up.
     */
    private var mcpClient: McpClientManager? = null

    /**
     * Tracks whether a test session is currently active.
     */
    private var isTestActive = false

    /**
     * Starts a test session with the given profile.
     *
     * This method:
     * 1. Transitions to "Initializing..." state
     * 2. Creates a GeminiSessionPreparer with MockToolExecutor
     * 3. Gets the default system prompt from SystemPromptConfig
     * 4. Prepares the session with the profile (fetches tools, renders templates, etc.)
     * 5. Initializes the Gemini model with mock tools
     * 6. Starts an audio conversation session
     * 7. Transitions to "Active" state
     *
     * @param profile The profile to test (may be unsaved)
     * @param context Android context for accessing resources/system services
     *
     * @throws SecurityException if microphone permissions are not granted
     * @throws IllegalStateException if MCP client is not initialized
     * @throws Exception on other initialization errors
     */
    suspend fun startTest(profile: Profile, context: Context) {
        try {
            onStatusChange(TestStatus.Initializing)
            Log.d(TAG, "Starting test session with profile: ${profile.name}")

            // Get HA credentials from app
            val haUrl = app.haUrl
                ?: throw IllegalStateException("Home Assistant URL not configured")
            val haToken = app.haToken
                ?: throw IllegalStateException("Home Assistant token not configured")

            val haApiClient = app.haApiClient
                ?: throw IllegalStateException("Home Assistant API client not initialized")

            // Create fresh MCP connection for this test session
            mcpClient = McpClientManager(haUrl, haToken)
            mcpClient!!.initialize()
            Log.d(TAG, "Fresh MCP connection created for test session")

            // Create real executor for pass-through of read-only tools
            val toolExecutor = GeminiMCPToolExecutor(mcpClient!!)

            // Create mock executor with pass-through capability
            mockToolExecutor = MockToolExecutor(realExecutor = toolExecutor)

            // Create a new session preparer for this test using MockToolExecutor
            sessionPreparer = GeminiSessionPreparer(
                mcpClient = mcpClient!!,
                haApiClient = haApiClient,
                toolExecutor = mockToolExecutor!!,
                onLogEntry = onLogEntry
            )

            // Get default system prompt from SystemPromptConfig
            val defaultSystemPrompt = SystemPromptConfig.getSystemPrompt(context)

            // Prepare and initialize the Gemini session with the profile
            sessionPreparer?.prepareAndInitialize(
                profile = profile,
                geminiService = testGeminiService,
                defaultSystemPrompt = defaultSystemPrompt
            )

            // Start the audio conversation with mock function handler
            testGeminiService.startSession(
                functionCallHandler = { call ->
                    mockToolExecutor!!.executeTool(call)
                },
                transcriptHandler = null
            )

            isTestActive = true
            onStatusChange(TestStatus.Active("Test session active - speak now!"))
            Log.d(TAG, "Test session started successfully")

        } catch (e: SecurityException) {
            // Clean up MCP connection if initialization failed
            mcpClient?.shutdown()
            mcpClient = null
            Log.e(TAG, "Microphone permission denied", e)
            onStatusChange(TestStatus.Error("Microphone permission required"))
            throw e

        } catch (e: Exception) {
            // Clean up MCP connection if initialization failed
            mcpClient?.shutdown()
            mcpClient = null
            Log.e(TAG, "Failed to start test session: ${e.message}", e)
            onStatusChange(TestStatus.Error("Failed to initialize test session: ${e.message}"))
            throw e
        }
    }

    /**
     * Stops the active test session.
     *
     * This method:
     * 1. Stops the Gemini service audio session
     * 2. Sets isTestActive to false
     * 3. Transitions to "Stopped" state
     * 4. Clears the MockToolExecutor call history
     *
     * Safe to call even if no session is active.
     */
    fun stopTest() {
        try {
            if (isTestActive) {
                testGeminiService.stopSession()
                Log.d(TAG, "Test session stopped")
            }

            isTestActive = false
            onStatusChange(TestStatus.Stopped)

            // Clear mock executor call history for the next test
            MockToolExecutor.clearCallHistory()

            // Clean up test MCP connection
            mcpClient?.shutdown()
            mcpClient = null
            Log.d(TAG, "Test MCP connection cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping test session: ${e.message}", e)
            onStatusChange(TestStatus.Error("Error stopping test session: ${e.message}"))
        }
    }

    /**
     * Returns the list of tools that were called during the test session.
     *
     * Useful for debugging which tools the Gemini model attempted to use
     * during the conversation.
     *
     * @return List of tool names in the order they were called
     */
    fun getCalledTools(): List<String> {
        return MockToolExecutor.getCalledTools()
    }

    /**
     * Cleans up test resources.
     *
     * Should be called when the test manager is no longer needed
     * to ensure the session is properly stopped.
     */
    fun cleanup() {
        try {
            if (isTestActive) {
                stopTest()
            }
            sessionPreparer = null
            Log.d(TAG, "ProfileTestManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }
}
