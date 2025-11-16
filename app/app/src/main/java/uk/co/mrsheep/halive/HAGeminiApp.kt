package uk.co.mrsheep.halive

import android.app.Application
import uk.co.mrsheep.halive.core.AssetCopyUtil
import uk.co.mrsheep.halive.core.CrashLogger
import uk.co.mrsheep.halive.core.FirebaseConfig
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.services.HomeAssistantApiClient
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import uk.co.mrsheep.halive.services.mcp.McpTool

class HAGeminiApp : Application() {
    var haApiClient: HomeAssistantApiClient? = null
    var lastAvailableTools: List<String>? = null
    var haUrl: String? = null
    var haToken: String? = null

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

        // Copy TFLite model files from assets to filesDir for wake word detection
        AssetCopyUtil.copyAssetsToFilesDir(this)

        // Note: MCP connection is NOT established here
        // It will be established in MainActivity after user configures HA
    }

    /**
     * Called by MainActivity after user provides HA credentials.
     * Establishes the MCP SSE connection and performs initialization handshake.
     */
    suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
        this.haUrl = haUrl
        this.haToken = haToken
        haApiClient = HomeAssistantApiClient(haUrl, haToken)
    }

    /**
     * Updates the cache of available tool names.
     * Called when tools are fetched to maintain a cached list.
     */
    fun updateToolCache(tools: List<McpTool>) {
        lastAvailableTools = tools.map { it.name }.sorted()
    }
}
