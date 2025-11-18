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
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

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

        // Migration: convert old isDefault to new active profile ID
        migrateDefaultToActive()
    }

    private fun migrateDefaultToActive() {
        // Check if migration already done
        if (prefs.getString(KEY_ACTIVE_PROFILE_ID, null) != null) {
            return // Already migrated
        }

        // Find first profile (we can't check isDefault anymore since Profile.kt was updated)
        val firstProfile = getAllProfiles().firstOrNull()
        if (firstProfile != null) {
            setActiveProfile(firstProfile.id)
        }
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

        // If this is the first profile, make it active
        if (existing.isEmpty()) {
            saveProfilesToStorage(listOf(profile))
            setActiveProfile(profile.id)
            return profile
        }

        saveProfilesToStorage(existing + profile)
        return profile
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
     * Returns the active profile (stored in SharedPreferences).
     * If no active profile is set, returns the first profile.
     * Returns null if no profiles exist.
     */
    fun getActiveProfile(): Profile? {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return if (activeId != null) {
            getProfileById(activeId)
        } else {
            // Fallback: return first profile if no active set
            getAllProfiles().firstOrNull()
        }
    }

    /**
     * Returns the active profile or the first profile if no active is set.
     */
    fun getActiveOrFirstProfile(): Profile? {
        return getActiveProfile()
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
        updated[index] = profile

        saveProfilesToStorage(updated)
        return profile
    }

    /**
     * Sets a profile as the active profile.
     */
    fun setActiveProfile(profileId: String) {
        val profile = getProfileById(profileId)
        require(profile != null) { "Profile with ID $profileId does not exist" }
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
    }

    /**
     * Deletes a profile by ID.
     * Throws IllegalStateException if trying to delete the last profile.
     * If deleting the active profile, sets first remaining profile as active.
     */
    fun deleteProfile(profileId: String) {
        val existing = getAllProfiles()

        if (existing.size == 1) {
            throw IllegalStateException("Cannot delete the last profile. Create another profile first.")
        }

        existing.find { it.id == profileId }
            ?: throw IllegalArgumentException("Profile with ID $profileId does not exist")

        val remaining = existing.filter { it.id != profileId }
        saveProfilesToStorage(remaining)

        // If we deleted the active profile, set first remaining as active
        if (prefs.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId && remaining.isNotEmpty()) {
            setActiveProfile(remaining.first().id)
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
            initialMessageToAgent = original.initialMessageToAgent,
            model = original.model,
            voice = original.voice,
            includeLiveContext = original.includeLiveContext,
            autoStartChat = original.autoStartChat,
            toolFilterMode = original.toolFilterMode,
            selectedToolNames = original.selectedToolNames
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
        setActiveProfile(defaultProfile.id)
    }
}
