package uk.co.mrsheep.halive

import android.app.Application
import uk.co.mrsheep.halive.core.CrashLogger
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.services.McpClientManager
import uk.co.mrsheep.halive.services.GeminiMCPToolExecutor
import uk.co.mrsheep.halive.services.HomeAssistantApiClient
import uk.co.mrsheep.halive.services.mcp.McpTool

class HAGeminiApp : Application() {
    // Global MCP client - will be initialized after HA config
    var mcpClient: McpClientManager? = null
    var toolExecutor: GeminiMCPToolExecutor? = null
    var haApiClient: HomeAssistantApiClient? = null
    var lastAvailableTools: List<String>? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize crash logger FIRST to catch any initialization crashes
        CrashLogger.initialize(this)

        // Try to initialize Firebase on launch
        FirebaseConfig.initializeFirebase(this)

        // Initialize ProfileManager (NEW)
        ProfileManager.initialize(this)

        // Run migration from SystemPromptConfig to profiles (NEW)
        ProfileManager.runMigrationIfNeeded(this)

        // Run tool filter migration (NEW)
        ProfileManager.runToolFilterMigrationIfNeeded()

        // Ensure at least one profile exists (NEW)
        ProfileManager.ensureDefaultProfileExists()

        // Note: MCP connection is NOT established here
        // It will be established in MainActivity after user configures HA
    }

    /**
     * Called by MainActivity after user provides HA credentials.
     * Establishes the MCP SSE connection and performs initialization handshake.
     */
    suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
        mcpClient = McpClientManager(haUrl, haToken)
        mcpClient?.initialize() // SSE connection + MCP handshake
        toolExecutor = GeminiMCPToolExecutor(mcpClient!!)
        haApiClient = HomeAssistantApiClient(haUrl, haToken)
    }

    /**
     * Called when app is closing to gracefully shut down MCP connection.
     */
    fun shutdownHomeAssistant() {
        mcpClient?.shutdown()
        mcpClient = null
        toolExecutor = null
        haApiClient = null
    }

    /**
     * Updates the cache of available tool names.
     * Called when tools are fetched to maintain a cached list.
     */
    fun updateToolCache(tools: List<McpTool>) {
        lastAvailableTools = tools.map { it.name }.sorted()
    }
}
