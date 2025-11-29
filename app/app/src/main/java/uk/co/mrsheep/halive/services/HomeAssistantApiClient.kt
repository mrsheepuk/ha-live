package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.co.mrsheep.halive.core.OAuthTokenManager
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class TemplateRequest(val template: String)

class HomeAssistantApiClient(
    private val baseUrl: String,
    private val tokenManager: OAuthTokenManager
) {
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
                val token = tokenManager.getValidToken()

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

    /**
     * Get list of available services (to check for integration).
     */
    suspend fun getServices(): JsonArray = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching available services...")

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                val token = tokenManager.getValidToken()

                val request = Request.Builder()
                    .url("$baseUrl/api/services")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 401 && retryCount < maxRetries) {
                    Log.d(TAG, "Received 401, retrying getServices...")
                    response.body?.close()
                    retryCount++
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Failed to get services: ${response.code}")
                }

                val body = response.body?.string() ?: throw Exception("Empty response from services API")
                return@withContext Json.parseToJsonElement(body).jsonArray

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Exception during getServices, retrying: ${e.message}")
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("getServices failed after $maxRetries retries")
    }

    /**
     * Call a Home Assistant service, optionally returning the response.
     */
    suspend fun callService(
        domain: String,
        service: String,
        data: Map<String, Any>,
        returnResponse: Boolean = false
    ): JsonObject? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Calling service: $domain.$service")

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                val token = tokenManager.getValidToken()

                val url = if (returnResponse) {
                    "$baseUrl/api/services/$domain/$service?return_response=true"
                } else {
                    "$baseUrl/api/services/$domain/$service"
                }

                val body = Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        data.forEach { (key, value) ->
                            when (value) {
                                is String -> put(key, JsonPrimitive(value))
                                is Number -> put(key, JsonPrimitive(value))
                                is Boolean -> put(key, JsonPrimitive(value))
                                else -> put(key, JsonPrimitive(value.toString()))
                            }
                        }
                    }
                ).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 401 && retryCount < maxRetries) {
                    Log.d(TAG, "Received 401, retrying callService...")
                    response.body?.close()
                    retryCount++
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Service call failed: ${response.code}")
                }

                if (returnResponse) {
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) return@withContext null
                    return@withContext Json.parseToJsonElement(responseBody).jsonObject
                }
                return@withContext null

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Exception during callService, retrying: ${e.message}")
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("callService failed after $maxRetries retries")
    }

    companion object {
        private const val TAG = "HomeAssistantApiClient"
    }
}
