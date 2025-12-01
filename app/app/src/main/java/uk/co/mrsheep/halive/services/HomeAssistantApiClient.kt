package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
                            put(key, anyToJsonElement(value))
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

    /**
     * Recursively converts Any value to JsonElement, handling nested maps and lists.
     */
    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) ->
                    if (k is String) {
                        put(k, anyToJsonElement(v))
                    }
                }
            }
            is List<*> -> buildJsonArray {
                value.forEach { add(anyToJsonElement(it)) }
            }
            is Set<*> -> buildJsonArray {
                value.forEach { add(anyToJsonElement(it)) }
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    /**
     * Get all entity states from Home Assistant.
     * Optionally filter by domain.
     */
    suspend fun getStates(domain: String? = null): List<EntityState> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching entity states" + (domain?.let { " for domain: $it" } ?: ""))

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                val token = tokenManager.getValidToken()

                val request = Request.Builder()
                    .url("$baseUrl/api/states")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 401 && retryCount < maxRetries) {
                    Log.d(TAG, "Received 401, retrying getStates...")
                    response.body?.close()
                    retryCount++
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Failed to get states: ${response.code}")
                }

                val body = response.body?.string() ?: throw Exception("Empty response from states API")
                val states = json.decodeFromString<List<EntityState>>(body)

                return@withContext if (domain != null) {
                    states.filter { it.entityId.startsWith("$domain.") }
                } else {
                    states
                }

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Exception during getStates, retrying: ${e.message}")
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("getStates failed after $maxRetries retries")
    }

    /**
     * Get all camera entities from Home Assistant.
     * Returns list of camera entity states with friendly names.
     */
    suspend fun getCameraEntities(): List<CameraEntity> {
        return getStates("camera").map { state ->
            CameraEntity(
                entityId = state.entityId,
                friendlyName = state.attributes["friendly_name"]?.jsonPrimitive?.contentOrNull
                    ?: state.entityId.removePrefix("camera.").replace("_", " ").capitalizeWords(),
                state = state.state
            )
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Get a snapshot from a Home Assistant camera.
     * Returns JPEG image data.
     *
     * @param entityId The camera entity ID (e.g., "camera.front_door")
     * @return JPEG image data as ByteArray
     * @throws Exception if snapshot fetch fails
     */
    suspend fun getCameraSnapshot(entityId: String): ByteArray = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching camera snapshot: $entityId")

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                val token = tokenManager.getValidToken()

                val request = Request.Builder()
                    .url("$baseUrl/api/camera_proxy/$entityId")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 401 && retryCount < maxRetries) {
                    Log.d(TAG, "Received 401, retrying getCameraSnapshot...")
                    response.body?.close()
                    retryCount++
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Failed to get camera snapshot: ${response.code}")
                }

                return@withContext response.body?.bytes()
                    ?: throw Exception("Empty response from camera proxy")

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Exception during getCameraSnapshot, retrying: ${e.message}")
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("getCameraSnapshot failed after $maxRetries retries")
    }

    companion object {
        private const val TAG = "HomeAssistantApiClient"
    }
}

@Serializable
data class EntityState(
    @SerialName("entity_id")
    val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement> = emptyMap()
)

data class CameraEntity(
    val entityId: String,
    val friendlyName: String,
    val state: String  // "idle", "streaming", "recording", "unavailable"
)
