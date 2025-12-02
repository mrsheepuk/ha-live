package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for local profiles stored in SharedPreferences.
 * These profiles are device-specific and not synced with Home Assistant.
 */
class LocalProfileRepository(context: Context) : ProfileRepository {

    companion object {
        private const val TAG = "LocalProfileRepository"
        private const val PREFS_NAME = "profiles"
        private const val KEY_PROFILES = "profiles_list"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override suspend fun getAll(): List<Profile> = withContext(Dispatchers.IO) {
        val jsonString = prefs.getString(KEY_PROFILES, null) ?: return@withContext emptyList()

        try {
            json.decodeFromString<List<Profile>>(jsonString)
                .map { it.copy(source = ProfileSource.LOCAL) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local profiles", e)
            emptyList()
        }
    }

    override suspend fun getById(id: String): Profile? {
        return getAll().find { it.id == id }
    }

    override suspend fun create(profile: Profile): Profile = withContext(Dispatchers.IO) {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val localProfiles = getAll()

        // Check for duplicate names
        if (localProfiles.any { it.name.equals(profile.name, ignoreCase = true) }) {
            throw ProfileNameConflictException("A local profile with the name '${profile.name}' already exists")
        }

        val localProfile = profile.copy(source = ProfileSource.LOCAL)
        saveProfiles(localProfiles + localProfile)

        Log.i(TAG, "Created local profile: ${profile.name}")
        localProfile
    }

    override suspend fun update(profile: Profile): Profile = withContext(Dispatchers.IO) {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val localProfiles = getAll()
        val index = localProfiles.indexOfFirst { it.id == profile.id }

        if (index == -1) {
            throw IllegalArgumentException("Local profile with ID ${profile.id} does not exist")
        }

        // Check for duplicate names (excluding this profile)
        if (localProfiles.any {
                it.name.equals(profile.name, ignoreCase = true) && it.id != profile.id
            }) {
            throw ProfileNameConflictException("A local profile with the name '${profile.name}' already exists")
        }

        val updated = localProfiles.toMutableList()
        updated[index] = profile.copy(source = ProfileSource.LOCAL)

        saveProfiles(updated)

        Log.i(TAG, "Updated local profile: ${profile.name}")
        profile.copy(source = ProfileSource.LOCAL)
    }

    override suspend fun delete(profileId: String): Boolean = withContext(Dispatchers.IO) {
        val localProfiles = getAll()
        val profile = localProfiles.find { it.id == profileId } ?: return@withContext false

        val remaining = localProfiles.filter { it.id != profileId }
        saveProfiles(remaining)

        Log.i(TAG, "Deleted local profile: ${profile.name}")
        true
    }

    override suspend fun isNameAvailable(name: String, excludeId: String?): Boolean {
        val profiles = getAll()
        return profiles.none {
            it.name.equals(name, ignoreCase = true) && it.id != excludeId
        }
    }

    override fun getSource(): ProfileSource = ProfileSource.LOCAL

    private fun saveProfiles(profiles: List<Profile>) {
        val jsonString = json.encodeToString(profiles)
        prefs.edit().putString(KEY_PROFILES, jsonString).apply()
    }

    /**
     * Clears all local profiles.
     * Used for testing or reset functionality.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_PROFILES).apply()
        Log.d(TAG, "Cleared all local profiles")
    }
}
