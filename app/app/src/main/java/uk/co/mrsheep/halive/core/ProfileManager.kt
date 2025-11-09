package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages profile storage and CRUD operations.
 *
 * Profiles are stored in SharedPreferences as a JSON array.
 * Uses StateFlow to notify UI of profile changes.
 */
object ProfileManager {

    private const val PREFS_NAME = "profiles"
    private const val KEY_PROFILES = "profiles_list"
    private const val KEY_LAST_USED_ID = "last_used_profile_id"
    private const val KEY_MIGRATION_DONE = "migration_v1_done"
    private const val KEY_MIGRATION_V2_DONE = "migration_v2_tool_filter"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private lateinit var prefs: SharedPreferences

    // StateFlow for reactive UI updates (will be used in Phase 3)
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    /**
     * Initialize the ProfileManager. Must be called from Application.onCreate().
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _profiles.value = loadProfilesFromStorage()
    }

    /**
     * Migrates existing SystemPromptConfig to a default profile.
     * Only runs once. Safe to call multiple times.
     */
    fun runMigrationIfNeeded(context: Context) {
        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) {
            return // Already migrated
        }

        val profiles = getAllProfiles()
        if (profiles.isNotEmpty()) {
            // Profiles already exist, mark as migrated
            prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
            return
        }

        // Get the existing system prompt from SystemPromptConfig
        val existingPrompt = SystemPromptConfig.getSystemPrompt(context)

        // Create a default profile with the existing prompt
        val defaultProfile = Profile(
            name = "Default",
            systemPrompt = existingPrompt,
            isDefault = true
        )

        createProfile(defaultProfile)
        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
    }

    /**
     * Migrates profiles to include tool filtering fields.
     * Only runs once. Safe to call multiple times.
     * Uses ignoreUnknownKeys to handle new fields in existing profiles.
     */
    fun runToolFilterMigrationIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATION_V2_DONE, false)) return

        // kotlinx.serialization with ignoreUnknownKeys handles this automatically
        // Existing profiles will get default values: toolFilterMode = ALL, selectedToolNames = emptySet()

        prefs.edit().putBoolean(KEY_MIGRATION_V2_DONE, true).apply()
    }

    /**
     * Ensures at least one profile exists. Creates default if needed.
     * Safe to call multiple times.
     */
    fun ensureDefaultProfileExists() {
        if (getAllProfiles().isEmpty()) {
            createProfile(Profile.createDefault())
        }
    }

    // ========== CRUD Operations ==========

    /**
     * Creates a new profile. Returns the created profile with generated ID.
     * Throws IllegalArgumentException if name is blank or duplicate.
     */
    fun createProfile(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val existing = getAllProfiles()

        // Check for duplicate names
        if (existing.any { it.name.equals(profile.name, ignoreCase = true) && it.id != profile.id }) {
            throw IllegalArgumentException("A profile with the name '${profile.name}' already exists")
        }

        // If this is the first profile, make it default
        val profileToSave = if (existing.isEmpty()) {
            profile.copy(isDefault = true)
        } else {
            // If marking as default, unmark others
            if (profile.isDefault) {
                val updated = existing.map { it.copy(isDefault = false) }
                saveProfilesToStorage(updated + profile)
                return profile
            }
            profile
        }

        saveProfilesToStorage(existing + profileToSave)
        return profileToSave
    }

    /**
     * Returns all profiles.
     */
    fun getAllProfiles(): List<Profile> {
        return _profiles.value
    }

    /**
     * Returns a profile by ID, or null if not found.
     */
    fun getProfileById(id: String): Profile? {
        return _profiles.value.find { it.id == id }
    }

    /**
     * Returns the default profile (marked as isDefault = true).
     * If no default exists, returns the first profile.
     * Returns null if no profiles exist.
     */
    fun getDefaultProfile(): Profile? {
        val all = getAllProfiles()
        return all.find { it.isDefault } ?: all.firstOrNull()
    }

    /**
     * Returns the last used profile ID, or null if not set.
     */
    fun getLastUsedProfileId(): String? {
        return prefs.getString(KEY_LAST_USED_ID, null)
    }

    /**
     * Returns the last used profile, falling back to default if deleted.
     */
    fun getLastUsedOrDefaultProfile(): Profile? {
        val lastUsedId = getLastUsedProfileId()
        if (lastUsedId != null) {
            val profile = getProfileById(lastUsedId)
            if (profile != null) return profile
        }
        // Fallback to default
        return getDefaultProfile()
    }

    /**
     * Updates an existing profile.
     * Throws IllegalArgumentException if ID doesn't exist or name is duplicate.
     */
    fun updateProfile(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val existing = getAllProfiles()
        val index = existing.indexOfFirst { it.id == profile.id }

        if (index == -1) {
            throw IllegalArgumentException("Profile with ID ${profile.id} does not exist")
        }

        // Check for duplicate names (excluding this profile)
        if (existing.any {
            it.name.equals(profile.name, ignoreCase = true) && it.id != profile.id
        }) {
            throw IllegalArgumentException("A profile with the name '${profile.name}' already exists")
        }

        val updated = existing.toMutableList()

        // If marking as default, unmark others
        if (profile.isDefault) {
            updated.replaceAll {
                if (it.id == profile.id) profile else it.copy(isDefault = false)
            }
        } else {
            updated[index] = profile
        }

        saveProfilesToStorage(updated)
        return profile
    }

    /**
     * Marks a profile as the default.
     * Unmarks all other profiles.
     */
    fun setDefaultProfile(profileId: String) {
        val existing = getAllProfiles()
        val updated = existing.map {
            it.copy(isDefault = it.id == profileId)
        }
        saveProfilesToStorage(updated)
    }

    /**
     * Marks a profile as last used.
     */
    fun markProfileAsUsed(profileId: String) {
        prefs.edit().putString(KEY_LAST_USED_ID, profileId).apply()

        // Update the timestamp
        val profile = getProfileById(profileId)
        if (profile != null) {
            updateProfile(profile.markAsUsed())
        }
    }

    /**
     * Deletes a profile by ID.
     * Throws IllegalStateException if trying to delete the last profile.
     * If deleting the default profile, promotes another to default.
     */
    fun deleteProfile(profileId: String) {
        val existing = getAllProfiles()

        if (existing.size == 1) {
            throw IllegalStateException("Cannot delete the last profile. Create another profile first.")
        }

        val toDelete = existing.find { it.id == profileId }
            ?: throw IllegalArgumentException("Profile with ID $profileId does not exist")

        val remaining = existing.filter { it.id != profileId }

        // If we deleted the default, make the first remaining profile the default
        val updated = if (toDelete.isDefault) {
            remaining.mapIndexed { index, profile ->
                if (index == 0) profile.copy(isDefault = true) else profile
            }
        } else {
            remaining
        }

        saveProfilesToStorage(updated)

        // Clear last used if we deleted it
        if (getLastUsedProfileId() == profileId) {
            prefs.edit().remove(KEY_LAST_USED_ID).apply()
        }
    }

    /**
     * Duplicates a profile with a new name.
     */
    fun duplicateProfile(profileId: String, newName: String): Profile {
        val original = getProfileById(profileId)
            ?: throw IllegalArgumentException("Profile with ID $profileId does not exist")

        val duplicate = Profile(
            name = newName,
            systemPrompt = original.systemPrompt,
            personality = original.personality,
            backgroundInfo = original.backgroundInfo,
            model = original.model,
            voice = original.voice,
            includeLiveContext = original.includeLiveContext,
            isDefault = false // Duplicates are never default
        )

        return createProfile(duplicate)
    }

    // ========== Storage Operations ==========

    private fun loadProfilesFromStorage(): List<Profile> {
        val jsonString = prefs.getString(KEY_PROFILES, null) ?: return emptyList()

        return try {
            json.decodeFromString<List<Profile>>(jsonString)
        } catch (e: Exception) {
            // Corrupted data - return empty list (will trigger default creation)
            emptyList()
        }
    }

    private fun saveProfilesToStorage(profiles: List<Profile>) {
        val jsonString = json.encodeToString(profiles)
        prefs.edit().putString(KEY_PROFILES, jsonString).apply()
        _profiles.value = profiles
    }

    /**
     * Clears all profiles and resets to default state.
     * Used for error recovery or testing.
     */
    fun resetToDefault(context: Context) {
        prefs.edit().clear().apply()
        val defaultProfile = Profile.createDefault()
        saveProfilesToStorage(listOf(defaultProfile))
        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
    }
}
