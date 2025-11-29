package uk.co.mrsheep.halive.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,

    @SerialName("refresh_token")
    val refreshToken: String? = null,

    @SerialName("expires_in")
    val expiresIn: Long,

    @SerialName("token_type")
    val tokenType: String = "Bearer"
)

class TokenRefreshException(message: String, cause: Throwable? = null) : Exception(message, cause)

class OAuthTokenManager(
    private val haUrl: String,
    private val storage: SecureTokenStorage,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "OAuthTokenManager"
        private const val TOKEN_BUFFER_SECONDS = 60L
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getValidToken(): String = withContext(Dispatchers.IO) {
        val tokens = storage.getTokens()
        if (tokens != null) {
            val now = System.currentTimeMillis()
            val bufferMs = TOKEN_BUFFER_SECONDS * 1000
            if (tokens.expiresAt > (now + bufferMs)) {
                Log.d(TAG, "Returning cached access token")
                return@withContext tokens.accessToken
            }
            Log.d(TAG, "Access token expired, refreshing")
            return@withContext refreshAccessToken()
        }
        throw TokenRefreshException("No tokens available")
    }

    suspend fun refreshAccessToken(): String = withContext(Dispatchers.IO) {
        val tokens = storage.getTokens()
            ?: throw TokenRefreshException("No refresh token available")

        try {
            Log.d(TAG, "Refreshing access token")
            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", tokens.refreshToken)
                .add("client_id", OAuthConfig.CLIENT_ID)
                .build()

            val request = Request.Builder()
                .url("$haUrl/auth/token")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw TokenRefreshException("Empty response body from token endpoint")

            if (!response.isSuccessful) {
                Log.e(
                    TAG,
                    "Token refresh failed: ${response.code} - $responseBody"
                )
                throw TokenRefreshException("Token refresh failed: ${response.code}")
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
            val newRefreshToken = tokenResponse.refreshToken ?: tokens.refreshToken

            storage.saveTokens(
                accessToken = tokenResponse.accessToken,
                refreshToken = newRefreshToken,
                expiresIn = tokenResponse.expiresIn,
                haUrl = haUrl
            )

            Log.d(TAG, "Successfully refreshed access token")
            return@withContext tokenResponse.accessToken
        } catch (e: TokenRefreshException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during token refresh", e)
            throw TokenRefreshException("Token refresh failed", e)
        }
    }

    suspend fun exchangeCodeForTokens(authCode: String): OAuthTokens =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Exchanging authorization code for tokens")
                val requestBody = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", authCode)
                    .add("client_id", OAuthConfig.CLIENT_ID)
                    .build()

                val request = Request.Builder()
                    .url("$haUrl/auth/token")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                    ?: throw TokenRefreshException("Empty response body from token endpoint")

                if (!response.isSuccessful) {
                    Log.e(
                        TAG,
                        "Code exchange failed: ${response.code} - $responseBody"
                    )
                    throw TokenRefreshException("Code exchange failed: ${response.code}")
                }

                val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
                val oauthTokens = OAuthTokens(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken
                        ?: throw TokenRefreshException("No refresh token in response"),
                    expiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)
                )

                storage.saveTokens(
                    accessToken = oauthTokens.accessToken,
                    refreshToken = oauthTokens.refreshToken,
                    expiresIn = tokenResponse.expiresIn,
                    haUrl = haUrl
                )

                Log.d(TAG, "Successfully exchanged authorization code for tokens")
                return@withContext oauthTokens
            } catch (e: TokenRefreshException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during code exchange", e)
                throw TokenRefreshException("Code exchange failed", e)
            }
        }
}
