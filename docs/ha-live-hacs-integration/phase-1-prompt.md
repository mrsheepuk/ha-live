# Phase 1: OAuth2 Authentication

## Goal

Replace the current manual long-lived token authentication with OAuth2 browser-based login for Home Assistant. This provides a much better user experience - users enter their HA URL, log in via browser, and the app receives tokens automatically.

## Current State

- User manually creates long-lived access token in HA UI (Profile → Security → Long-lived tokens)
- User copies token to app settings
- Token stored via `HAConfig.kt` in SharedPreferences
- `HomeAssistantApiClient` and `McpClientManager` use this token

Key files:
- `app/app/src/main/java/uk/co/mrsheep/halive/core/HAConfig.kt` - Token storage
- `app/app/src/main/java/uk/co/mrsheep/halive/services/HomeAssistantApiClient.kt` - REST API client
- `app/app/src/main/java/uk/co/mrsheep/halive/services/mcp/McpClientManager.kt` - MCP SSE client
- `app/app/src/main/java/uk/co/mrsheep/halive/ui/OnboardingActivity.kt` - Setup flow

## Target State

1. User enters HA URL only
2. App opens browser/WebView to HA login page
3. User authenticates with HA credentials
4. HA redirects back to app with auth code
5. App exchanges code for access_token + refresh_token
6. Tokens used for both REST API and MCP connection
7. Automatic token refresh when expired

## Technical Implementation

### OAuth2 Flow

```
1. App → Browser: https://{ha_url}/auth/authorize
   ?client_id=https://halive.app
   &redirect_uri=halive://oauth/callback
   &response_type=code
   &state={random_state}

2. User logs in to Home Assistant

3. HA → App: halive://oauth/callback?code={auth_code}&state={state}

4. App → HA: POST /auth/token
   grant_type=authorization_code
   &code={auth_code}
   &client_id=https://halive.app

5. HA → App: {
     "access_token": "eyJ...",
     "refresh_token": "abc123...",
     "expires_in": 1800,
     "token_type": "Bearer"
   }
```

### New Files to Create

#### 1. `core/OAuthConfig.kt`
```kotlin
object OAuthConfig {
    const val CLIENT_ID = "https://halive.app"
    const val REDIRECT_URI = "halive://oauth/callback"
    const val REDIRECT_SCHEME = "halive"
    const val REDIRECT_HOST = "oauth"
    const val REDIRECT_PATH = "/callback"
}
```

#### 2. `core/SecureTokenStorage.kt`
```kotlin
class SecureTokenStorage(context: Context) {
    // Use EncryptedSharedPreferences with MasterKey
    // Store: access_token, refresh_token, expires_at, ha_url

    fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long)
    fun getTokens(): OAuthTokens?
    fun clearTokens()
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)
```

#### 3. `core/OAuthTokenManager.kt`
```kotlin
class OAuthTokenManager(
    private val haUrl: String,
    private val storage: SecureTokenStorage,
    private val httpClient: OkHttpClient
) {
    suspend fun getValidToken(): String {
        val tokens = storage.getTokens()
        if (tokens != null && tokens.expiresAt > System.currentTimeMillis() + 60_000) {
            return tokens.accessToken
        }
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        // POST to /auth/token with grant_type=refresh_token
        // Save new tokens
        // Return new access token
    }

    suspend fun exchangeCodeForTokens(authCode: String): OAuthTokens {
        // POST to /auth/token with grant_type=authorization_code
    }
}
```

#### 4. `ui/OAuthCallbackActivity.kt`
```kotlin
class OAuthCallbackActivity : AppCompatActivity() {
    // Handle intent with halive://oauth/callback?code=xxx&state=xxx
    // Validate state matches expected
    // Pass code back to OnboardingActivity or handle exchange here
}
```

### AndroidManifest.xml Changes

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

### Dependencies to Add (build.gradle)

```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

### Files to Modify

#### `HomeAssistantApiClient.kt`
- Add constructor that accepts `OAuthTokenManager` instead of static token
- Call `tokenManager.getValidToken()` before each request
- Handle 401 responses by triggering token refresh

#### `McpClientManager.kt`
- Similar changes - get fresh token for SSE connection
- May need to reconnect if token refreshed mid-session

#### `OnboardingActivity.kt`
- Replace token input field with "Log in to Home Assistant" button
- Launch browser with OAuth URL
- Handle return from OAuthCallbackActivity

#### `HAGeminiApp.kt`
- Initialize OAuthTokenManager instead of just storing token
- Provide token manager to clients

### Backward Compatibility

Support both auth methods during transition:

```kotlin
sealed class AuthMethod {
    data class OAuth(val tokenManager: OAuthTokenManager) : AuthMethod()
    data class LegacyToken(val token: String) : AuthMethod()
}

class HomeAssistantAuth(context: Context) {
    fun getAuthMethod(): AuthMethod {
        // Check for OAuth tokens first
        val oauthTokens = secureStorage.getTokens()
        if (oauthTokens != null) {
            return AuthMethod.OAuth(OAuthTokenManager(...))
        }
        // Fall back to legacy token
        val legacyToken = HAConfig.getToken(context)
        if (legacyToken != null) {
            return AuthMethod.LegacyToken(legacyToken)
        }
        throw NotAuthenticatedException()
    }
}
```

### Updated Onboarding Flow

```
Screen 1: Welcome
  [Connect to Home Assistant]

Screen 2: Enter URL
  [https://________________]
  [Continue]

Screen 3: (Browser opens for HA login)
  - User authenticates
  - HA redirects back

Screen 4: Success!
  "Connected to Home Assistant"
  [Continue to setup...]
```

## Acceptance Criteria

1. [ ] Fresh install: User can connect via OAuth without manual token
2. [ ] Existing install with legacy token: Continues to work
3. [ ] Token refresh: Automatic when expired, no user action needed
4. [ ] OAuth failure: Clear error message, option to retry
5. [ ] Token revocation: Handle gracefully (re-authenticate)
6. [ ] Tokens stored securely: EncryptedSharedPreferences
7. [ ] State parameter validated: Prevent CSRF attacks

## Testing Checklist

- [ ] Fresh install → OAuth flow completes successfully
- [ ] Token expires → Auto-refresh works transparently
- [ ] Refresh token invalid → User prompted to re-authenticate
- [ ] Existing user with token → App continues working
- [ ] Cancel during OAuth → Returns to previous screen gracefully
- [ ] Network error during token exchange → Appropriate error shown

## Notes

- Home Assistant's OAuth doesn't require pre-registration of client_id
- The client_id is just a URL that identifies your app
- Access tokens typically expire in 30 minutes
- Refresh tokens are long-lived but can be revoked
- User identity is available from `/api/` after authentication

## Dependencies

- None (this is the first phase)

## What This Enables

- Phase 3+ will use OAuth tokens for shared config API calls
- Better UX for all users
- User identity available for "modified by" tracking in shared profiles
