package uk.co.mrsheep.halive

import android.app.Application
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.services.McpClientManager
import uk.co.mrsheep.halive.services.GeminiMCPToolExecutor

class HAGeminiApp : Application() {
    // Global MCP client - will be initialized after HA config
    var mcpClient: McpClientManager? = null
    var toolExecutor: GeminiMCPToolExecutor? = null

    override fun onCreate() {
        super.onCreate()

        // Try to initialize Firebase on launch
        FirebaseConfig.initializeFirebase(this)

        // Initialize ProfileManager (NEW)
        ProfileManager.initialize(this)

        // Run migration from SystemPromptConfig to profiles (NEW)
        ProfileManager.runMigrationIfNeeded(this)

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
    }

    /**
     * Called when app is closing to gracefully shut down MCP connection.
     */
    fun shutdownHomeAssistant() {
        mcpClient?.shutdown()
        mcpClient = null
        toolExecutor = null
    }
}
