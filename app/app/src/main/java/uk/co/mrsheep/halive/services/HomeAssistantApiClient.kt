package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.co.mrsheep.halive.core.OAuthTokenManager
import java.util.concurrent.TimeUnit

@Serializable
data class TemplateRequest(val template: String)

/**
 * Sealed class for providing tokens to HomeAssistantApiClient.
 * Supports both static tokens and OAuth2 token management.
 */
sealed class TokenProvider {
    abstract suspend fun getToken(): String

    /**
     * Static token provider - uses a fixed token string.
     */
    data class Static(private val token: String) : TokenProvider() {
        override suspend fun getToken(): String = token
    }

    /**
     * OAuth2 token provider - gets tokens from OAuthTokenManager.
     * Handles automatic token refresh when needed.
     */
    data class OAuth(private val tokenManager: OAuthTokenManager) : TokenProvider() {
        override suspend fun getToken(): String = tokenManager.getValidToken()
    }
}

class HomeAssistantApiClient(
    private val baseUrl: String,
    private val tokenProvider: TokenProvider
) {
    /**
     * Secondary constructor for backward compatibility with static tokens.
     * @param baseUrl Home Assistant instance URL
     * @param token Static authentication token
     */
    constructor(baseUrl: String, token: String) : this(baseUrl, TokenProvider.Static(token))

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Renders a Jinja2 template using Home Assistant's template API.
     * Gets a fresh token for each request and automatically retries on 401 (Unauthorized).
     * @param template The template string to render
     * @return The rendered template as a string
     * @throws Exception if rendering fails
     */
    suspend fun renderTemplate(template: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Rendering template: ${template.take(100)}...")

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                // Get fresh token for this request
                val token = tokenProvider.getToken()

                // Build request body
                val requestBody = json.encodeToString(
                    TemplateRequest.serializer(),
                    TemplateRequest(template)
                )

                // Build HTTP request
                val request = Request.Builder()
                    .url("$baseUrl/api/template")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                // Execute request
                val response = client.newCall(request).execute()

                // Handle 401 Unauthorized - retry once as token may need refresh
                if (response.code == 401 && retryCount < maxRetries) {
                    Log.d(TAG, "Received 401, retrying template rendering...")
                    response.body?.close()
                    retryCount++
                    continue
                }

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw Exception("Template rendering failed: ${response.code} - $errorBody")
                }

                val renderedText = response.body?.string()
                    ?: throw Exception("Empty response from template API")

                Log.d(TAG, "Template rendered successfully: ${renderedText.take(100)}...")
                return@withContext renderedText

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Exception during template rendering, retrying: ${e.message}")
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("Template rendering failed after $maxRetries retries")
    }

    companion object {
        private const val TAG = "HomeAssistantApiClient"
    }
}
