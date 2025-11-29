# Phase 3: Integration Detection & Shared Config Fetch

## Goal

Update the Android app to detect if the `ha_live_config` HACS integration is installed in Home Assistant, and if so, fetch and use the shared Gemini API key.

## Prerequisites

- Phase 1 (OAuth) should be complete OR app still using legacy token auth
- Phase 2 (HACS integration) should be deployed and testable

## Context

The HA Live app currently stores the Gemini API key locally on each device. After this phase:
1. App checks if `ha_live_config` integration is installed on HA
2. If installed, fetches shared config (Gemini key + profiles)
3. Uses shared Gemini key if available, with option to use local key instead
4. Caches shared config for offline use

## Current State

Key files:
- `core/GeminiConfig.kt` - Local API key storage
- `core/HAConfig.kt` - HA URL and token storage
- `services/HomeAssistantApiClient.kt` - REST API client
- `ui/OnboardingActivity.kt` - Setup flow
- `ui/SettingsActivity.kt` - Settings UI
- `HAGeminiApp.kt` - Application class

## Implementation

### 1. Create SharedConfigRepository

New file: `services/SharedConfigRepository.kt`

```kotlin
package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SharedConfig(
    val geminiApiKey: String?,
    val profiles: List<SharedProfile>
)

@Serializable
data class SharedProfile(
    val id: String,
    val name: String,
    val systemPrompt: String = "",
    val personality: String = "",
    val backgroundInfo: String = "",
    val model: String = "gemini-2.0-flash-exp",
    val voice: String = "Aoede",
    val toolFilterMode: String = "ALL",
    val selectedTools: List<String> = emptyList(),
    val includeLiveContext: Boolean = true,
    val enableTranscription: Boolean = false,
    val autoStartChat: Boolean = false,
    val initialMessage: String = "",
    val lastModified: String? = null,
    val modifiedBy: String? = null,
    val schemaVersion: Int = 1
)

class SharedConfigRepository(
    private val haClient: HomeAssistantApiClient
) {
    companion object {
        private const val TAG = "SharedConfigRepository"
        private const val DOMAIN = "ha_live_config"
    }

    /**
     * Check if ha_live_config integration is installed in Home Assistant.
     * Returns true if the integration's services are available.
     */
    suspend fun isIntegrationInstalled(): Boolean {
        return try {
            val services = haClient.getServices()
            val hasService = services.any {
                it.jsonObject["domain"]?.jsonPrimitive?.content == DOMAIN
            }
            Log.d(TAG, "Integration installed: $hasService")
            hasService
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check for integration", e)
            false
        }
    }

    /**
     * Fetch all shared configuration from Home Assistant.
     * Returns null if fetch fails.
     */
    suspend fun getSharedConfig(): SharedConfig? {
        return try {
            val response = haClient.callService(
                domain = DOMAIN,
                service = "get_config",
                data = emptyMap(),
                returnResponse = true
            )
            parseSharedConfig(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch shared config", e)
            null
        }
    }

    /**
     * Set the shared Gemini API key in Home Assistant.
     */
    suspend fun setGeminiKey(apiKey: String?): Boolean {
        return try {
            haClient.callService(
                domain = DOMAIN,
                service = "set_gemini_key",
                data = mapOf("api_key" to (apiKey ?: ""))
            )
            Log.i(TAG, "Gemini key ${if (apiKey != null) "set" else "cleared"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Gemini key", e)
            false
        }
    }

    /**
     * Check if a profile name is available.
     */
    suspend fun isProfileNameAvailable(name: String, excludeId: String? = null): Boolean {
        return try {
            val data = buildMap<String, Any> {
                put("name", name)
                excludeId?.let { put("exclude_id", it) }
            }
            val response = haClient.callService(
                domain = DOMAIN,
                service = "check_profile_name",
                data = data,
                returnResponse = true
            )
            response?.get("available")?.jsonPrimitive?.boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check profile name", e)
            true // Assume available on error
        }
    }

    private fun parseSharedConfig(json: JsonObject?): SharedConfig? {
        if (json == null) return null
        return try {
            SharedConfig(
                geminiApiKey = json["gemini_api_key"]?.jsonPrimitive?.contentOrNull,
                profiles = json["profiles"]?.jsonArray?.map {
                    Json.decodeFromJsonElement<SharedProfile>(it)
                } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse shared config", e)
            null
        }
    }
}
```

### 2. Update HomeAssistantApiClient

Add methods to `services/HomeAssistantApiClient.kt`:

```kotlin
/**
 * Get list of available services (to check for integration).
 */
suspend fun getServices(): JsonArray {
    val response = httpClient.newCall(
        Request.Builder()
            .url("$haUrl/api/services")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
    ).await()

    if (!response.isSuccessful) {
        throw IOException("Failed to get services: ${response.code}")
    }

    return Json.parseToJsonElement(response.body!!.string()).jsonArray
}

/**
 * Call a Home Assistant service, optionally returning the response.
 */
suspend fun callService(
    domain: String,
    service: String,
    data: Map<String, Any>,
    returnResponse: Boolean = false
): JsonObject? {
    val url = if (returnResponse) {
        "$haUrl/api/services/$domain/$service?return_response=true"
    } else {
        "$haUrl/api/services/$domain/$service"
    }

    val body = Json.encodeToString(data)
        .toRequestBody("application/json".toMediaType())

    val response = httpClient.newCall(
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
    ).await()

    if (!response.isSuccessful) {
        throw IOException("Service call failed: ${response.code}")
    }

    return if (returnResponse) {
        val responseBody = response.body?.string()
        if (responseBody.isNullOrBlank()) null
        else Json.parseToJsonElement(responseBody).jsonObject
    } else null
}
```

### 3. Create SharedConfigCache

New file: `core/SharedConfigCache.kt`

```kotlin
package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.mrsheep.halive.services.SharedConfig

/**
 * Caches shared config locally for offline access.
 */
class SharedConfigCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shared_config_cache", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_INTEGRATION_INSTALLED = "integration_installed"
        private const val KEY_LAST_FETCH = "last_fetch_time"
    }

    fun saveConfig(config: SharedConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, Json.encodeToString(config))
            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
            .apply()
    }

    fun getConfig(): SharedConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            Json.decodeFromString<SharedConfig>(json)
        } catch (e: Exception) {
            null
        }
    }

    fun setIntegrationInstalled(installed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_INTEGRATION_INSTALLED, installed)
            .apply()
    }

    fun isIntegrationInstalled(): Boolean {
        return prefs.getBoolean(KEY_INTEGRATION_INSTALLED, false)
    }

    fun getLastFetchTime(): Long {
        return prefs.getLong(KEY_LAST_FETCH, 0)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
```

### 4. Update GeminiConfig

Modify `core/GeminiConfig.kt`:

```kotlin
object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USE_SHARED = "use_shared_key"

    // Cached shared key (set by app on config fetch)
    private var cachedSharedKey: String? = null

    /**
     * Update the cached shared key from HA.
     */
    fun updateSharedKey(key: String?) {
        cachedSharedKey = key
    }

    /**
     * Get the shared key (if cached).
     */
    fun getSharedKey(): String? = cachedSharedKey

    /**
     * Check if a shared key is available.
     */
    fun hasSharedKey(): Boolean = !cachedSharedKey.isNullOrBlank()

    /**
     * Get the effective Gemini API key.
     * Priority: Shared key (if available and enabled) > Local key
     */
    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)

        // Prefer shared key if available and user hasn't disabled it
        if (useShared && !cachedSharedKey.isNullOrBlank()) {
            return cachedSharedKey
        }

        // Fall back to local key
        return prefs.getString(KEY_API_KEY, null)
    }

    /**
     * Check if currently using the shared key.
     */
    fun isUsingSharedKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)
        return useShared && !cachedSharedKey.isNullOrBlank()
    }

    /**
     * Get the local API key (device-specific).
     */
    fun getLocalApiKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
    }

    /**
     * Save a local API key (device-specific).
     */
    fun saveLocalApiKey(context: Context, apiKey: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    /**
     * Set whether to prefer shared key over local.
     */
    fun setUseSharedKey(context: Context, useShared: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_SHARED, useShared)
            .apply()
    }

    /**
     * Check if user prefers shared key.
     */
    fun prefersSharedKey(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_SHARED, true)
    }
}
```

### 5. Update HAGeminiApp

Add to `HAGeminiApp.kt`:

```kotlin
class HAGeminiApp : Application() {
    // Existing fields...

    var sharedConfigRepo: SharedConfigRepository? = null
        private set
    var sharedConfigCache: SharedConfigCache? = null
        private set

    override fun onCreate() {
        super.onCreate()
        sharedConfigCache = SharedConfigCache(this)
    }

    suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
        this.haUrl = haUrl
        this.haToken = haToken
        haApiClient = HomeAssistantApiClient(haUrl, haToken)
        sharedConfigRepo = SharedConfigRepository(haApiClient!!)
    }

    /**
     * Check for integration and fetch shared config.
     * Call this after HA is initialized.
     */
    suspend fun fetchSharedConfig(): SharedConfig? {
        val repo = sharedConfigRepo ?: return null
        val cache = sharedConfigCache ?: return null

        // Check if integration is installed
        val installed = repo.isIntegrationInstalled()
        cache.setIntegrationInstalled(installed)

        if (!installed) {
            Log.d(TAG, "ha_live_config integration not installed")
            return null
        }

        // Fetch config
        val config = repo.getSharedConfig()
        if (config != null) {
            cache.saveConfig(config)
            GeminiConfig.updateSharedKey(config.geminiApiKey)
            Log.d(TAG, "Fetched shared config: ${config.profiles.size} profiles")
        }

        return config
    }

    /**
     * Get cached shared config (for offline use).
     */
    fun getCachedSharedConfig(): SharedConfig? {
        return sharedConfigCache?.getConfig()
    }

    /**
     * Check if integration is installed (from cache).
     */
    fun isSharedConfigAvailable(): Boolean {
        return sharedConfigCache?.isIntegrationInstalled() == true
    }
}
```

### 6. Update Onboarding Flow

Modify `ui/OnboardingActivity.kt` to detect integration after authentication:

```kotlin
private suspend fun checkForSharedConfig() {
    val app = application as HAGeminiApp

    showProgress("Checking for shared configuration...")

    val config = app.fetchSharedConfig()

    when {
        config == null -> {
            // Integration not installed
            showNoIntegrationScreen()
        }
        config.geminiApiKey != null -> {
            // Full config available
            showSharedConfigFoundScreen(
                hasApiKey = true,
                profileCount = config.profiles.size
            )
        }
        else -> {
            // Integration installed but no API key
            showSharedConfigFoundScreen(
                hasApiKey = false,
                profileCount = config.profiles.size
            )
        }
    }
}

private fun showNoIntegrationScreen() {
    // "No shared configuration found. Continue with local setup or install integration."
    // Options:
    // - [Continue with local setup] → proceed to Gemini key input
    // - [Learn about shared config] → open docs URL
}

private fun showSharedConfigFoundScreen(hasApiKey: Boolean, profileCount: Int) {
    if (hasApiKey) {
        // "Your household has shared configuration! Gemini API key configured, X profiles available."
        // [Get Started]
    } else {
        // "Shared configuration found but no API key. Set up shared key or use your own."
        // - [Set shared key] → input for API key, then call sharedConfigRepo.setGeminiKey()
        // - [Use my own key] → local key input, then proceed
    }
}
```

### 7. Update Settings UI

Add to `ui/SettingsActivity.kt`:

```kotlin
private fun setupApiKeySection() {
    val app = application as HAGeminiApp
    val hasSharedKey = GeminiConfig.hasSharedKey()
    val isUsingShared = GeminiConfig.isUsingSharedKey(this)

    if (hasSharedKey) {
        // Show radio buttons: Shared vs Local
        sharedKeyRadio.isChecked = isUsingShared
        localKeyRadio.isChecked = !isUsingShared

        sharedKeyRadio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                GeminiConfig.setUseSharedKey(this, true)
                updateApiKeyDisplay()
            }
        }

        localKeyRadio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                GeminiConfig.setUseSharedKey(this, false)
                updateApiKeyDisplay()
            }
        }

        // Show "Manage shared key" button
        manageSharedKeyButton.visibility = View.VISIBLE
        manageSharedKeyButton.setOnClickListener {
            showManageSharedKeyDialog()
        }
    } else {
        // No shared key - show local key input only
        sharedKeySection.visibility = View.GONE
        localKeySection.visibility = View.VISIBLE
    }
}

private fun showManageSharedKeyDialog() {
    // Dialog with options:
    // - View current shared key (masked)
    // - Update shared key
    // - Clear shared key
}
```

### 8. Layouts

Create/update layouts for:
- Onboarding integration detection screen
- Settings API key section with shared/local toggle

## Acceptance Criteria

1. [ ] App detects if `ha_live_config` integration is installed
2. [ ] App fetches shared config on startup (after HA auth)
3. [ ] Shared Gemini API key is used when available and enabled
4. [ ] User can switch between shared and local key in settings
5. [ ] User can set/update shared key (writes to HA)
6. [ ] Config is cached for offline access
7. [ ] Onboarding shows appropriate screen based on integration status
8. [ ] Settings show correct API key source

## Testing Checklist

- [ ] HA without integration → app proceeds to local setup
- [ ] HA with integration, no key → app offers to set shared or local key
- [ ] HA with integration + key → app uses shared key automatically
- [ ] Toggle to local key → app uses local key
- [ ] Toggle back to shared → app uses shared key
- [ ] Set shared key from app → key appears in HA
- [ ] Offline startup → uses cached config

## Dependencies

- Phase 1 (OAuth) - optional, can work with legacy tokens
- Phase 2 (HACS integration) - required for testing

## What This Enables

- Phase 4: Shared profile sync builds on this foundation
- Users can share API key without any profile sync
