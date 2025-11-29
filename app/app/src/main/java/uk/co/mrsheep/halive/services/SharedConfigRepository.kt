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
            val hasService = services.any { element ->
                element.jsonObject["domain"]?.jsonPrimitive?.contentOrNull == DOMAIN
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

    /**
     * Create or update a shared profile.
     * @return The profile ID if successful, null otherwise.
     */
    suspend fun upsertProfile(profile: uk.co.mrsheep.halive.core.Profile): String? {
        return try {
            val response = haClient.callService(
                domain = DOMAIN,
                service = "upsert_profile",
                data = mapOf("profile" to profile.toSharedFormat()),
                returnResponse = true
            )
            response?.get("id")?.jsonPrimitive?.content ?: profile.id
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
                domain = DOMAIN,
                service = "delete_profile",
                data = mapOf("profile_id" to profileId)
            )
            Log.i(TAG, "Deleted profile: $profileId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete profile", e)
            false
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
