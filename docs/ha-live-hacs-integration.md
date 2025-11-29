# HA Live Shared Configuration via HACS Integration

## Overview

This document describes the design for shared configuration storage in Home Assistant, enabling multiple users of the same Home Assistant instance to share HA Live app configuration (Gemini API key and conversation profiles).

## Goals

1. **Shared Gemini API Key**: Household members share a single API key configured once in Home Assistant
2. **Shared Profiles**: Conversation profiles synced across all household members' devices
3. **Improved Authentication**: OAuth2 login instead of manual long-lived token copying
4. **Graceful Fallback**: App works normally if HACS integration is not installed (local-only mode)
5. **Migration Path**: Existing users can upload local profiles to shared storage

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Home Assistant                            │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              ha_live_config Integration                  │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  .storage/ha_live_config                        │    │    │
│  │  │  {                                              │    │    │
│  │  │    "gemini_api_key": "AIza...",                 │    │    │
│  │  │    "profiles": [...]                            │    │    │
│  │  │  }                                              │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  │                                                          │    │
│  │  Services:                                               │    │
│  │  • ha_live_config.get_config                            │    │
│  │  • ha_live_config.set_gemini_key                        │    │
│  │  • ha_live_config.upsert_profile                        │    │
│  │  • ha_live_config.delete_profile                        │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              ↑                                   │
│                              │ REST API + OAuth2                 │
│                              ↓                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    MCP Server                            │    │
│  │                  /mcp_server/sse                         │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                               ↑
                               │ OAuth2 Token (for both REST + MCP)
                               │
┌──────────────────────────────┴──────────────────────────────────┐
│                         HA Live App                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │  OAuth Manager  │  │ SharedConfig    │  │  Local Config   │  │
│  │                 │  │ Repository      │  │  (fallback)     │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 1: OAuth2 Authentication

### Current State

- User manually creates long-lived access token in HA UI
- User copies token to app settings
- Token stored in `HAConfig.kt` via SharedPreferences

### Target State

- User enters HA URL only
- App opens browser/WebView for HA login
- User authenticates with HA username/password
- App receives OAuth tokens automatically
- Tokens used for both REST API and MCP connection

### OAuth2 Flow

```
1. User enters HA URL
   └─→ App stores URL

2. App initiates OAuth
   └─→ Open: https://{ha_url}/auth/authorize
       ?client_id=https://halive.app
       &redirect_uri=halive://oauth/callback
       &response_type=code
       &state={random_state}

3. User logs in to Home Assistant
   └─→ HA validates credentials

4. HA redirects back to app
   └─→ halive://oauth/callback?code={auth_code}&state={state}

5. App exchanges code for tokens
   └─→ POST https://{ha_url}/auth/token
       Content-Type: application/x-www-form-urlencoded

       grant_type=authorization_code
       &code={auth_code}
       &client_id=https://halive.app

6. HA returns tokens
   └─→ {
         "access_token": "eyJ...",
         "refresh_token": "abc123...",
         "expires_in": 1800,
         "token_type": "Bearer"
       }

7. App stores tokens securely
   └─→ Use Android Keystore for encryption
```

### Token Refresh

```kotlin
class OAuthTokenManager(private val haUrl: String) {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAt: Instant? = null

    suspend fun getValidToken(): String {
        if (accessToken != null && expiresAt?.isAfter(Instant.now()) == true) {
            return accessToken!!
        }
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        val response = httpClient.post("$haUrl/auth/token") {
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken!!)
                append("client_id", OAUTH_CLIENT_ID)
            }))
        }
        // Parse and store new tokens
        val tokens = response.body<OAuthTokenResponse>()
        accessToken = tokens.accessToken
        expiresAt = Instant.now().plusSeconds(tokens.expiresIn)
        // Note: HA may return a new refresh token
        if (tokens.refreshToken != null) {
            refreshToken = tokens.refreshToken
        }
        persistTokens()
        return accessToken!!
    }
}
```

### Android Implementation Details

#### Custom URL Scheme

In `AndroidManifest.xml`:
```xml
<activity
    android:name=".ui.OAuthCallbackActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="halive"
            android:host="oauth"
            android:path="/callback" />
    </intent-filter>
</activity>
```

#### Secure Token Storage

```kotlin
class SecureTokenStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        "ha_live_oauth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        sharedPrefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", expiresAt)
            .apply()
    }
}
```

### Migration: Supporting Both Auth Methods

During transition, support both OAuth and legacy tokens:

```kotlin
sealed class AuthMethod {
    data class OAuth(val tokenManager: OAuthTokenManager) : AuthMethod()
    data class LegacyToken(val token: String) : AuthMethod()
}

class HomeAssistantAuth(private val context: Context) {
    fun getAuthMethod(): AuthMethod {
        // Prefer OAuth if available
        val oauthTokens = secureStorage.getOAuthTokens()
        if (oauthTokens != null) {
            return AuthMethod.OAuth(OAuthTokenManager(haUrl, oauthTokens))
        }

        // Fall back to legacy token
        val legacyToken = HAConfig.getToken(context)
        if (legacyToken != null) {
            return AuthMethod.LegacyToken(legacyToken)
        }

        throw NotAuthenticatedException()
    }

    suspend fun getAccessToken(): String {
        return when (val auth = getAuthMethod()) {
            is AuthMethod.OAuth -> auth.tokenManager.getValidToken()
            is AuthMethod.LegacyToken -> auth.token
        }
    }
}
```

---

## Part 2: HACS Integration (ha_live_config)

### Integration Structure

```
custom_components/
└── ha_live_config/
    ├── __init__.py          # Main integration setup
    ├── manifest.json        # Integration metadata
    ├── services.yaml        # Service definitions
    └── const.py             # Constants
```

### manifest.json

```json
{
  "domain": "ha_live_config",
  "name": "HA Live Config",
  "version": "1.0.0",
  "documentation": "https://github.com/user/ha-live-config",
  "issue_tracker": "https://github.com/user/ha-live-config/issues",
  "dependencies": [],
  "codeowners": ["@mrsheepuk"],
  "requirements": [],
  "iot_class": "local_push",
  "integration_type": "service"
}
```

### const.py

```python
DOMAIN = "ha_live_config"
STORAGE_VERSION = 1
STORAGE_KEY = "ha_live_config"

# Profile schema version for future migrations
PROFILE_SCHEMA_VERSION = 1
```

### __init__.py

```python
"""HA Live Config - Shared configuration for HA Live voice assistant."""
from __future__ import annotations

import logging
import uuid
from datetime import datetime
from typing import Any

from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.helpers.storage import Store
from homeassistant.helpers.typing import ConfigType
import homeassistant.util.dt as dt_util

from .const import DOMAIN, STORAGE_KEY, STORAGE_VERSION, PROFILE_SCHEMA_VERSION

_LOGGER = logging.getLogger(__name__)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up the HA Live Config integration."""
    store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    data = await store.async_load()

    if data is None:
        data = {
            "schema_version": PROFILE_SCHEMA_VERSION,
            "gemini_api_key": None,
            "profiles": []
        }

    hass.data[DOMAIN] = {
        "store": store,
        "data": data
    }

    async def _save() -> None:
        """Persist data to storage."""
        await store.async_save(hass.data[DOMAIN]["data"])

    # -------------------------------------------------------------------------
    # Service: get_config
    # -------------------------------------------------------------------------
    @callback
    def handle_get_config(call: ServiceCall) -> dict[str, Any]:
        """Return all shared configuration."""
        data = hass.data[DOMAIN]["data"]
        return {
            "gemini_api_key": data.get("gemini_api_key"),
            "profiles": data.get("profiles", [])
        }

    # -------------------------------------------------------------------------
    # Service: set_gemini_key
    # -------------------------------------------------------------------------
    async def handle_set_gemini_key(call: ServiceCall) -> None:
        """Set or update the shared Gemini API key."""
        api_key = call.data.get("api_key")

        if api_key is not None and not api_key.startswith("AIza"):
            _LOGGER.warning("API key doesn't look like a valid Gemini key")

        hass.data[DOMAIN]["data"]["gemini_api_key"] = api_key
        await _save()
        _LOGGER.info("Gemini API key %s", "set" if api_key else "cleared")

    # -------------------------------------------------------------------------
    # Service: upsert_profile
    # -------------------------------------------------------------------------
    async def handle_upsert_profile(call: ServiceCall) -> dict[str, Any]:
        """Create or update a profile."""
        profile = dict(call.data.get("profile", {}))
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        # Validate required fields
        if not profile.get("name"):
            raise ValueError("Profile must have a name")

        # Check for name collision (different ID, same name)
        profile_id = profile.get("id")
        existing_with_name = next(
            (p for p in profiles
             if p["name"].lower() == profile["name"].lower()
             and p["id"] != profile_id),
            None
        )
        if existing_with_name:
            raise ValueError(f"A profile named '{profile['name']}' already exists")

        # Generate ID if new profile
        if not profile_id:
            profile["id"] = str(uuid.uuid4())

        # Add metadata
        profile["last_modified"] = dt_util.utcnow().isoformat()
        profile["schema_version"] = PROFILE_SCHEMA_VERSION

        # Get user info if available
        if call.context.user_id:
            user = await hass.auth.async_get_user(call.context.user_id)
            profile["modified_by"] = user.name if user else None
        else:
            profile["modified_by"] = None

        # Upsert
        existing_idx = next(
            (i for i, p in enumerate(profiles) if p["id"] == profile["id"]),
            None
        )

        if existing_idx is not None:
            profiles[existing_idx] = profile
            _LOGGER.info("Updated profile: %s", profile["name"])
        else:
            profiles.append(profile)
            _LOGGER.info("Created profile: %s", profile["name"])

        await _save()
        return {"id": profile["id"]}

    # -------------------------------------------------------------------------
    # Service: delete_profile
    # -------------------------------------------------------------------------
    async def handle_delete_profile(call: ServiceCall) -> None:
        """Delete a profile by ID."""
        profile_id = call.data.get("profile_id")
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        original_count = len(profiles)
        hass.data[DOMAIN]["data"]["profiles"] = [
            p for p in profiles if p["id"] != profile_id
        ]

        if len(hass.data[DOMAIN]["data"]["profiles"]) < original_count:
            await _save()
            _LOGGER.info("Deleted profile: %s", profile_id)
        else:
            _LOGGER.warning("Profile not found: %s", profile_id)

    # -------------------------------------------------------------------------
    # Service: check_profile_name
    # -------------------------------------------------------------------------
    @callback
    def handle_check_profile_name(call: ServiceCall) -> dict[str, bool]:
        """Check if a profile name is available."""
        name = call.data.get("name", "")
        exclude_id = call.data.get("exclude_id")  # Optional: exclude this ID from check
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        name_taken = any(
            p["name"].lower() == name.lower() and p["id"] != exclude_id
            for p in profiles
        )

        return {"available": not name_taken}

    # Register all services
    hass.services.async_register(
        DOMAIN, "get_config", handle_get_config,
        supports_response=True
    )
    hass.services.async_register(
        DOMAIN, "set_gemini_key", handle_set_gemini_key
    )
    hass.services.async_register(
        DOMAIN, "upsert_profile", handle_upsert_profile,
        supports_response=True
    )
    hass.services.async_register(
        DOMAIN, "delete_profile", handle_delete_profile
    )
    hass.services.async_register(
        DOMAIN, "check_profile_name", handle_check_profile_name,
        supports_response=True
    )

    return True
```

### services.yaml

```yaml
get_config:
  name: Get Configuration
  description: Retrieve all HA Live shared configuration including Gemini API key and profiles.

set_gemini_key:
  name: Set Gemini API Key
  description: Set or update the shared Gemini API key.
  fields:
    api_key:
      name: API Key
      description: The Gemini API key to store. Pass null/empty to clear.
      required: false
      example: "AIzaSy..."
      selector:
        text:

upsert_profile:
  name: Create/Update Profile
  description: Create a new profile or update an existing one.
  fields:
    profile:
      name: Profile
      description: The profile data as a JSON object.
      required: true
      example: |
        {
          "name": "Default Assistant",
          "system_prompt": "You are a helpful assistant...",
          "personality": "Friendly and concise",
          "model": "gemini-2.0-flash-exp",
          "voice": "Aoede"
        }
      selector:
        object:

delete_profile:
  name: Delete Profile
  description: Delete a profile by its ID.
  fields:
    profile_id:
      name: Profile ID
      description: The UUID of the profile to delete.
      required: true
      example: "550e8400-e29b-41d4-a716-446655440000"
      selector:
        text:

check_profile_name:
  name: Check Profile Name
  description: Check if a profile name is available.
  fields:
    name:
      name: Name
      description: The profile name to check.
      required: true
      selector:
        text:
    exclude_id:
      name: Exclude ID
      description: Optional profile ID to exclude from the check (for updates).
      required: false
      selector:
        text:
```

### Profile Data Schema

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Default Assistant",
  "schema_version": 1,

  "system_prompt": "You are a helpful home assistant...",
  "personality": "Friendly, concise, and helpful",
  "background_info": "Current time: {{ now().strftime('%H:%M') }}\nWeather: {{ states('weather.home') }}",

  "model": "gemini-2.0-flash-exp",
  "voice": "Aoede",

  "tool_filter_mode": "ALL",
  "selected_tools": [],

  "include_live_context": true,
  "enable_transcription": false,
  "auto_start_chat": false,
  "initial_message": "",

  "last_modified": "2025-01-15T10:30:00Z",
  "modified_by": "John"
}
```

---

## Part 3: App-Side Implementation

### Data Model Changes

```kotlin
// core/ProfileSource.kt
enum class ProfileSource {
    LOCAL,      // Stored only on this device
    SHARED      // Synced with HA via ha_live_config integration
}

// Updated Profile.kt
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String = "",
    val personality: String = "",
    val backgroundInfo: String = "",
    val model: String = "gemini-2.0-flash-exp",
    val voice: String = "Aoede",
    val toolFilterMode: ToolFilterMode = ToolFilterMode.ALL,
    val selectedTools: List<String> = emptyList(),
    val includeLiveContext: Boolean = true,
    val enableTranscription: Boolean = false,
    val autoStartChat: Boolean = false,
    val initialMessage: String = "",

    // New fields for shared config support
    val source: ProfileSource = ProfileSource.LOCAL,
    val lastModified: String? = null,      // ISO 8601 timestamp
    val modifiedBy: String? = null,        // HA username
    val schemaVersion: Int = 1
)
```

### SharedConfigRepository

```kotlin
// services/SharedConfigRepository.kt
class SharedConfigRepository(
    private val haClient: HomeAssistantApiClient
) {
    /**
     * Check if ha_live_config integration is installed.
     */
    suspend fun isIntegrationInstalled(): Boolean {
        return try {
            val services = haClient.getServices()
            services.any { it.domain == "ha_live_config" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check for integration", e)
            false
        }
    }

    /**
     * Fetch all shared configuration from Home Assistant.
     */
    suspend fun getSharedConfig(): SharedConfig? {
        return try {
            val response = haClient.callService(
                domain = "ha_live_config",
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
     * Set the shared Gemini API key.
     */
    suspend fun setGeminiKey(apiKey: String?): Boolean {
        return try {
            haClient.callService(
                domain = "ha_live_config",
                service = "set_gemini_key",
                data = mapOf("api_key" to apiKey)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Gemini key", e)
            false
        }
    }

    /**
     * Create or update a shared profile.
     * @return The profile ID if successful, null otherwise.
     */
    suspend fun upsertProfile(profile: Profile): String? {
        return try {
            val response = haClient.callService(
                domain = "ha_live_config",
                service = "upsert_profile",
                data = mapOf("profile" to profile.toSharedFormat()),
                returnResponse = true
            )
            response?.get("id") as? String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upsert profile", e)
            null
        }
    }

    /**
     * Delete a shared profile.
     */
    suspend fun deleteProfile(profileId: String): Boolean {
        return try {
            haClient.callService(
                domain = "ha_live_config",
                service = "delete_profile",
                data = mapOf("profile_id" to profileId)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete profile", e)
            false
        }
    }

    /**
     * Check if a profile name is available.
     */
    suspend fun isProfileNameAvailable(name: String, excludeId: String? = null): Boolean {
        return try {
            val response = haClient.callService(
                domain = "ha_live_config",
                service = "check_profile_name",
                data = buildMap {
                    put("name", name)
                    excludeId?.let { put("exclude_id", it) }
                },
                returnResponse = true
            )
            response?.get("available") as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check profile name", e)
            true // Assume available on error
        }
    }

    companion object {
        private const val TAG = "SharedConfigRepository"
    }
}

data class SharedConfig(
    val geminiApiKey: String?,
    val profiles: List<Profile>
)
```

### ProfileManager Updates

```kotlin
// Updated ProfileManager.kt
class ProfileManager(private val context: Context) {

    private var sharedConfigRepo: SharedConfigRepository? = null
    private var cachedSharedProfiles: List<Profile> = emptyList()

    fun setSharedConfigRepository(repo: SharedConfigRepository) {
        sharedConfigRepo = repo
    }

    /**
     * Get all profiles (local + shared), with shared profiles first.
     */
    suspend fun getAllProfiles(): List<Profile> {
        // Fetch latest shared profiles
        sharedConfigRepo?.getSharedConfig()?.let { config ->
            cachedSharedProfiles = config.profiles.map {
                it.copy(source = ProfileSource.SHARED)
            }
        }

        val localProfiles = getLocalProfiles()

        // Return shared first, then local
        return cachedSharedProfiles + localProfiles
    }

    /**
     * Save a profile - routes to local or shared storage based on source.
     */
    suspend fun saveProfile(profile: Profile): Boolean {
        return when (profile.source) {
            ProfileSource.SHARED -> {
                val repo = sharedConfigRepo
                    ?: throw IllegalStateException("Shared config not available")
                repo.upsertProfile(profile) != null
            }
            ProfileSource.LOCAL -> {
                saveLocalProfile(profile)
                true
            }
        }
    }

    /**
     * Delete a profile from appropriate storage.
     */
    suspend fun deleteProfile(profile: Profile): Boolean {
        return when (profile.source) {
            ProfileSource.SHARED -> {
                sharedConfigRepo?.deleteProfile(profile.id) ?: false
            }
            ProfileSource.LOCAL -> {
                deleteLocalProfile(profile.id)
                true
            }
        }
    }

    /**
     * Upload a local profile to shared storage.
     * Creates a copy in shared storage and optionally deletes local version.
     */
    suspend fun uploadToShared(
        profile: Profile,
        deleteLocal: Boolean = true
    ): Result<Profile> {
        val repo = sharedConfigRepo
            ?: return Result.failure(IllegalStateException("Shared config not available"))

        // Check if name is available
        if (!repo.isProfileNameAvailable(profile.name)) {
            return Result.failure(
                ProfileNameConflictException("Profile '${profile.name}' already exists in shared storage")
            )
        }

        // Create shared version with new ID
        val sharedProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            source = ProfileSource.SHARED
        )

        val newId = repo.upsertProfile(sharedProfile)
            ?: return Result.failure(Exception("Failed to upload profile"))

        if (deleteLocal) {
            deleteLocalProfile(profile.id)
        }

        return Result.success(sharedProfile.copy(id = newId))
    }

    /**
     * Download a shared profile to local storage.
     * Creates a local copy without affecting shared version.
     */
    fun downloadToLocal(profile: Profile): Profile {
        require(profile.source == ProfileSource.SHARED)

        val localProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            source = ProfileSource.LOCAL,
            name = "${profile.name} (Local Copy)"
        )

        saveLocalProfile(localProfile)
        return localProfile
    }
}

class ProfileNameConflictException(message: String) : Exception(message)
```

### GeminiConfig Updates

```kotlin
// Updated GeminiConfig.kt
object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USE_SHARED = "use_shared_key"

    private var cachedSharedKey: String? = null

    fun updateSharedKey(key: String?) {
        cachedSharedKey = key
    }

    /**
     * Get the effective Gemini API key.
     * Priority: Shared key (if available and enabled) > Local key
     */
    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)

        // Prefer shared key if available and enabled
        if (useShared && cachedSharedKey != null) {
            return cachedSharedKey
        }

        // Fall back to local key
        return prefs.getString(KEY_API_KEY, null)
    }

    /**
     * Check if using the shared key.
     */
    fun isUsingSharedKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useShared = prefs.getBoolean(KEY_USE_SHARED, true)
        return useShared && cachedSharedKey != null
    }

    /**
     * Save a local API key (device-specific).
     */
    fun saveLocalApiKey(context: Context, apiKey: String) {
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
}
```

---

## Part 4: User Experience Flows

### First Run - New User

```
┌─────────────────────────────────────────┐
│         Welcome to HA Live              │
│                                         │
│  Control your home with your voice      │
│  using AI-powered conversations.        │
│                                         │
│  To get started, connect to your        │
│  Home Assistant instance.               │
│                                         │
│         [Connect to Home Assistant]     │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  Enter your Home Assistant URL          │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │ https://                          │  │
│  └───────────────────────────────────┘  │
│                                         │
│  Example: https://home.example.com      │
│                                         │
│               [Continue]                │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  ← Home Assistant (WebView/Browser)     │
│  ─────────────────────────────────────  │
│                                         │
│  Log in to authorize HA Live            │
│                                         │
│  Username: [_____________________]      │
│  Password: [_____________________]      │
│                                         │
│              [Log In]                   │
│                                         │
│  ─────────────────────────────────────  │
│  HA Live wants to access your Home      │
│  Assistant. [Allow] [Deny]              │
└─────────────────────────────────────────┘
                    │
                    ▼
            (Checking for shared config...)
                    │
        ┌───────────┴───────────┐
        │                       │
   Integration              No Integration
     Found                     Found
        │                       │
        ▼                       ▼
```

#### Branch A: Integration Found with Full Config

```
┌─────────────────────────────────────────┐
│  ✓ Connected Successfully!              │
│                                         │
│  Your household has shared              │
│  configuration available:               │
│                                         │
│  ✓ Gemini API key configured            │
│  ✓ 3 shared profiles available          │
│                                         │
│  You're all set! Tap below to start     │
│  your first voice conversation.         │
│                                         │
│            [Get Started]                │
└─────────────────────────────────────────┘
```

#### Branch B: Integration Found, No Gemini Key

```
┌─────────────────────────────────────────┐
│  ✓ Connected Successfully!              │
│                                         │
│  Shared configuration found, but no     │
│  Gemini API key is set up yet.          │
│                                         │
│  ○ Set up shared key (for household)    │
│    Everyone using HA Live will use      │
│    this key automatically.              │
│                                         │
│  ○ Use my own key (this device only)    │
│    Only this device will use your key.  │
│                                         │
│              [Continue]                 │
└─────────────────────────────────────────┘
```

#### Branch C: No Integration Found

```
┌─────────────────────────────────────────┐
│  ✓ Connected Successfully!              │
│                                         │
│  No shared configuration found.         │
│                                         │
│  You can:                               │
│  • Set up HA Live on this device        │
│  • Install the HA Live Config           │
│    integration to share settings        │
│    with your household                  │
│                                         │
│  [Continue with device setup]           │
│  [Learn about shared config →]          │
└─────────────────────────────────────────┘
```

### Profile List UI

```
┌─────────────────────────────────────────┐
│  Profiles                        [+ Add]│
│  ─────────────────────────────────────  │
│                                         │
│  SHARED                                 │
│  ┌───────────────────────────────────┐  │
│  │ 🏠 Default Assistant         [⋮]  │  │
│  │    Modified by Sarah, 2h ago      │  │
│  └───────────────────────────────────┘  │
│  ┌───────────────────────────────────┐  │
│  │ 🏠 Kitchen Helper            [⋮]  │  │
│  │    Modified by John, yesterday    │  │
│  └───────────────────────────────────┘  │
│                                         │
│  LOCAL                                  │
│  ┌───────────────────────────────────┐  │
│  │ 📱 My Work Mode              [⋮]  │  │
│  │    Upload to shared storage →     │  │
│  └───────────────────────────────────┘  │
│                                         │
└─────────────────────────────────────────┘
```

### Profile Context Menu (Local Profile)

```
┌────────────────────────┐
│ Edit                   │
│ Duplicate              │
│ ────────────────────── │
│ 🏠 Upload to Shared... │
│ ────────────────────── │
│ Delete                 │
└────────────────────────┘
```

### Upload to Shared Dialog

```
┌─────────────────────────────────────────┐
│  Upload to Shared Storage               │
│  ─────────────────────────────────────  │
│                                         │
│  This will share "My Work Mode" with    │
│  everyone in your household.            │
│                                         │
│  ☑ Delete local copy after upload       │
│                                         │
│        [Cancel]  [Upload]               │
└─────────────────────────────────────────┘
```

### Upload Conflict Dialog

```
┌─────────────────────────────────────────┐
│  Name Already Taken                     │
│  ─────────────────────────────────────  │
│                                         │
│  A shared profile named "Work Mode"     │
│  already exists.                        │
│                                         │
│  Enter a different name:                │
│  ┌───────────────────────────────────┐  │
│  │ My Work Mode                      │  │
│  └───────────────────────────────────┘  │
│                                         │
│        [Cancel]  [Upload]               │
└─────────────────────────────────────────┘
```

### Editing a Shared Profile

```
┌─────────────────────────────────────────┐
│  ← Edit Profile                         │
│  ─────────────────────────────────────  │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │ 🏠 Shared Profile                 │  │
│  │ Changes will be synced to all     │  │
│  │ household members.                │  │
│  └───────────────────────────────────┘  │
│                                         │
│  Name                                   │
│  ┌───────────────────────────────────┐  │
│  │ Default Assistant                 │  │
│  └───────────────────────────────────┘  │
│                                         │
│  System Prompt                          │
│  ┌───────────────────────────────────┐  │
│  │ You are a helpful...              │  │
│  │                                   │  │
│  └───────────────────────────────────┘  │
│                                         │
│  ...                                    │
│                                         │
│  [Save to Home Assistant]               │
│  [Save as Local Copy]                   │
└─────────────────────────────────────────┘
```

### Settings - API Key Section

```
┌─────────────────────────────────────────┐
│  Gemini API Key                         │
│  ─────────────────────────────────────  │
│                                         │
│  ● Use shared key (from Home Assistant) │
│    AIza...xxxxx (configured by John)    │
│                                         │
│  ○ Use my own key                       │
│    Not configured                       │
│                                         │
│  [Manage shared key...]                 │
└─────────────────────────────────────────┘
```

---

## Part 5: Implementation Phases

### Phase 1: OAuth Authentication
**Scope:** Replace long-lived tokens with OAuth2 flow

1. Implement `OAuthTokenManager` with token storage
2. Create `OAuthCallbackActivity` for redirect handling
3. Update `OnboardingActivity` with OAuth login flow
4. Update `HomeAssistantApiClient` to use OAuth tokens
5. Update `McpClientManager` to use OAuth tokens
6. Add token refresh logic
7. Maintain backward compatibility with existing token auth

**Testing:**
- Fresh install → OAuth flow works
- Existing install with token → continues to work
- Token refresh → automatic and seamless

### Phase 2: HACS Integration
**Scope:** Create and publish the Home Assistant integration

1. Develop `ha_live_config` integration locally
2. Write integration tests
3. Create GitHub repository for integration
4. Submit to HACS default repository (or provide as custom repo)
5. Write user documentation

**Testing:**
- Install via HACS
- All services work correctly
- Data persists across HA restarts

### Phase 3: Integration Detection & Shared Config Fetch
**Scope:** App detects integration and fetches shared config

1. Implement `SharedConfigRepository`
2. Add integration detection to onboarding flow
3. Fetch and cache shared config on app launch
4. Update `GeminiConfig` for shared key support
5. Add UI for shared key selection in settings

**Testing:**
- App detects integration presence/absence
- Shared Gemini key works correctly
- Fallback to local key works

### Phase 4: Shared Profiles
**Scope:** Full profile sync with Home Assistant

1. Update `Profile` data class with source tracking
2. Update `ProfileManager` for dual-source support
3. Update profile list UI with shared/local sections
4. Implement profile upload to shared storage
5. Handle name conflicts on upload
6. Add "Save to Home Assistant" for shared profiles
7. Add "Save as Local Copy" option

**Testing:**
- Shared profiles appear correctly
- Edits sync to HA
- Upload with name conflict handled
- New shared profile creation works

### Phase 5: Polish & Edge Cases
**Scope:** Handle offline, conflicts, and improve UX

1. Offline mode with cached profiles
2. Sync status indicators
3. Conflict detection and resolution UI
4. Migration prompt for existing users
5. "Last modified by" display
6. Pull-to-refresh for profile list

---

## Security Considerations

### Gemini API Key Storage

- **In Home Assistant:** Stored in `.storage/ha_live_config` (JSON file)
  - Only accessible to users with HA access
  - Appropriate for household sharing scenario

- **In App (local key):** Use Android `EncryptedSharedPreferences`
  - Encrypted with hardware-backed keystore

### OAuth Tokens

- Store in `EncryptedSharedPreferences`
- Never log tokens
- Refresh tokens securely
- Handle token revocation gracefully

### Service Call Authorization

- HA's service calls respect user permissions
- OAuth token identifies the user
- `modified_by` field tracks who made changes

---

## Future Considerations

### Per-User Preferences

Could add user-specific preferences that aren't shared:
- Preferred profile per user
- Personal wake word sensitivity
- UI preferences

### Real-Time Sync

Currently uses fetch-on-launch model. Could add:
- WebSocket subscription to HA for live updates
- Push notification when profile changes
- Immediate sync across devices

### Profile Permissions

Could add access control:
- Profile owner (can delete)
- Profile editors (can modify)
- View-only profiles

### Audit Log

Track all changes:
- Who modified what, when
- Restore previous versions
- See profile history

---

## Appendix: API Reference

### Home Assistant Service Calls

**Get Config:**
```http
POST /api/services/ha_live_config/get_config
Authorization: Bearer {token}
Content-Type: application/json

{}

Response:
{
  "gemini_api_key": "AIza...",
  "profiles": [...]
}
```

**Set Gemini Key:**
```http
POST /api/services/ha_live_config/set_gemini_key
Authorization: Bearer {token}
Content-Type: application/json

{
  "api_key": "AIza..."
}
```

**Upsert Profile:**
```http
POST /api/services/ha_live_config/upsert_profile
Authorization: Bearer {token}
Content-Type: application/json

{
  "profile": {
    "name": "My Profile",
    "system_prompt": "...",
    ...
  }
}

Response:
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Delete Profile:**
```http
POST /api/services/ha_live_config/delete_profile
Authorization: Bearer {token}
Content-Type: application/json

{
  "profile_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Check Profile Name:**
```http
POST /api/services/ha_live_config/check_profile_name
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "My Profile",
  "exclude_id": "optional-uuid-to-exclude"
}

Response:
{
  "available": true
}
```
