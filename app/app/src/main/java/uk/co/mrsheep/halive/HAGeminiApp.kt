package uk.co.mrsheep.halive

import android.app.Application
import uk.co.mrsheep.halive.core.AssetCopyUtil
import uk.co.mrsheep.halive.core.CrashLogger
import uk.co.mrsheep.halive.core.HomeAssistantAuth
import uk.co.mrsheep.halive.core.AuthMethod
import uk.co.mrsheep.halive.core.SecureTokenStorage
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.services.HomeAssistantApiClient
import uk.co.mrsheep.halive.services.TokenProvider
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.mcp.McpClientManager
import uk.co.mrsheep.halive.services.mcp.McpTool

class HAGeminiApp : Application() {
    var haApiClient: HomeAssistantApiClient? = null
    var lastAvailableTools: List<String>? = null
    var haUrl: String? = null
    var haToken: String? = null
    var homeAssistantAuth: HomeAssistantAuth? = null
        private set

    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize crash logger FIRST to catch any initialization crashes
            CrashLogger.initialize(this)

            // Clear old crash logs on successful startup (they've already been seen/fixed)
            CrashLogger.clearLog(this)

            // Initialize auth system
            homeAssistantAuth = HomeAssistantAuth(this)

            // Initialize ProfileManager
            ProfileManager.initialize(this)

            // Ensure at least one profile exists
            ProfileManager.ensureDefaultProfileExists()

            // Copy TFLite model files from assets to filesDir for wake word detection
            AssetCopyUtil.copyAssetsToFilesDir(this)

            // Note: MCP connection is NOT established here
            // It will be established in MainActivity after user configures HA
        } catch (e: Exception) {
            // Log to system log as last resort if CrashLogger fails
            android.util.Log.e("HAGeminiApp", "FATAL: App initialization failed", e)

            // Try to log to crash logger if it was initialized
            try {
                CrashLogger.logException(this, "App initialization failed", e)
            } catch (ignored: Exception) {
                // CrashLogger itself failed, nothing we can do
            }

            // Re-throw to crash the app (but at least we tried to log it)
            throw e
        }
    }

    /**
     * Called by MainActivity after user provides HA credentials (legacy flow).
     * Establishes the MCP SSE connection and performs initialization handshake.
     */
    suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
        this.haUrl = haUrl
        this.haToken = haToken
        haApiClient = HomeAssistantApiClient(haUrl, haToken)
    }

    /**
     * Initialize Home Assistant using OAuth tokens.
     * Called after successful OAuth authentication.
     */
    suspend fun initializeHomeAssistantWithOAuth(haUrl: String, tokenManager: OAuthTokenManager) {
        this.haUrl = haUrl
        this.haToken = null  // Not used with OAuth
        haApiClient = HomeAssistantApiClient(haUrl, TokenProvider.OAuth(tokenManager))
    }

    /**
     * Get the current authentication method.
     */
    fun getAuthMethod(): AuthMethod? = homeAssistantAuth?.getAuthMethod()

    /**
     * Check if Home Assistant is configured and authenticated.
     */
    fun isHomeAssistantConfigured(): Boolean = homeAssistantAuth?.isAuthenticated() == true

    /**
     * Updates the cache of available tool names.
     * Called when tools are fetched to maintain a cached list.
     */
    fun updateToolCache(tools: List<McpTool>) {
        lastAvailableTools = tools.map { it.name }.sorted()
    }
}
