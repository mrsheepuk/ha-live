package uk.co.mrsheep.halive

import android.app.Application
import android.util.Log
import kotlinx.coroutines.runBlocking
import uk.co.mrsheep.halive.core.CrashLogger
import uk.co.mrsheep.halive.core.GeminiConfig
import uk.co.mrsheep.halive.core.HomeAssistantAuth
import uk.co.mrsheep.halive.core.LocalProfileRepository
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.core.ProfileService
import uk.co.mrsheep.halive.services.HomeAssistantApiClient
import uk.co.mrsheep.halive.services.SharedConfig
import uk.co.mrsheep.halive.services.SharedConfigRepository
import uk.co.mrsheep.halive.services.mcp.McpTool

class HAGeminiApp : Application() {
    var haApiClient: HomeAssistantApiClient? = null
    var lastAvailableTools: List<String>? = null
    var haUrl: String? = null
    var homeAssistantAuth: HomeAssistantAuth? = null
        private set
    var sharedConfigRepo: SharedConfigRepository? = null
        private set
    lateinit var profileService: ProfileService
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

            // Initialize ProfileService with local repository
            val localRepo = LocalProfileRepository(this)
            profileService = ProfileService(this, localRepo)

            // Initialize local profiles and ensure default exists
            runBlocking {
                profileService.initialize()
                profileService.ensureDefaultProfileExists()
            }

            // Log final configuration state
            val geminiConfigured = GeminiConfig.isConfigured(this)
            val haConfigured = homeAssistantAuth?.isAuthenticated() == true
            Log.d(TAG, "Startup complete: GeminiConfig.isConfigured=$geminiConfigured, " +
                    "HAConfig.isConfigured=$haConfigured, " +
                    "profiles=${profileService.getAllProfiles().size}")

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
     * Initialize Home Assistant using OAuth tokens.
     * Called after successful OAuth authentication.
     */
    suspend fun initializeHomeAssistantWithOAuth(haUrl: String, tokenManager: OAuthTokenManager) {
        this.haUrl = haUrl
        haApiClient = HomeAssistantApiClient(haUrl, tokenManager)
        sharedConfigRepo = SharedConfigRepository(haApiClient!!)

        // Set the repository in ProfileService for remote profile access
        profileService.setRemoteRepository(sharedConfigRepo)
    }

    /**
     * Check if Home Assistant is configured and authenticated.
     */
    fun isHomeAssistantConfigured(): Boolean = homeAssistantAuth?.isAuthenticated() == true

    /**
     * Get the OAuth token manager if authenticated.
     */
    fun getTokenManager(): OAuthTokenManager? = homeAssistantAuth?.getTokenManager()

    /**
     * Updates the cache of available tool names.
     * Called when tools are fetched to maintain a cached list.
     */
    fun updateToolCache(tools: List<McpTool>) {
        lastAvailableTools = tools.map { it.name }.sorted()
    }

    /**
     * Check for integration and fetch shared config including Gemini API key.
     * Call this after HA is initialized.
     * Returns the SharedConfig if integration is installed, null otherwise.
     */
    suspend fun fetchSharedConfig(): SharedConfig? {
        val repo = sharedConfigRepo ?: return null

        // Check if integration is installed
        val installed = repo.isIntegrationInstalled()

        if (!installed) {
            Log.d(TAG, "ha_live_config integration not installed")
            return null
        }

        // Fetch config to get Gemini API key
        val config = repo.getSharedConfig()
        if (config != null) {
            Log.d(TAG, "Fetched shared config: hasApiKey=${config.geminiApiKey != null}, profiles=${config.profiles.size}")
            GeminiConfig.updateSharedKey(config.geminiApiKey)
        } else {
            Log.w(TAG, "getSharedConfig returned null")
        }

        return config
    }

    /**
     * Check if remote repository is available (HA integration installed).
     */
    fun isSharedConfigAvailable(): Boolean {
        return profileService.isRemoteAvailable()
    }

    companion object {
        private const val TAG = "HAGeminiApp"
    }
}
