package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.mrsheep.halive.services.SharedConfigRepository

/**
 * Exception thrown when a profile name is already taken.
 */
class ProfileNameConflictException(message: String) : Exception(message)

/**
 * Manages profile storage and CRUD operations.
 *
 * Profiles can be:
 * - LOCAL: Stored in SharedPreferences on this device only
 * - SHARED: Synced with Home Assistant via SharedConfigRepository
 *
 * Uses StateFlow to notify UI of profile changes.
 */
object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val PREFS_NAME = "profiles"
    private const val KEY_PROFILES = "profiles_list"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private lateinit var prefs: SharedPreferences

    // Shared config repository (set after HA authentication)
    private var sharedConfigRepo: SharedConfigRepository? = null

    // Cached shared profiles from Home Assistant
    private var cachedSharedProfiles: List<Profile> = emptyList()

    // Mutex for thread-safe operations
    private val mutex = Mutex()

    // StateFlow for reactive UI updates - combines local + shared profiles
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    /**
     * Initialize the ProfileManager. Must be called from Application.onCreate().
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        updateCombinedProfiles()

        // Migration: convert old isDefault to new active profile ID
        migrateDefaultToActive()
    }

    /**
     * Set the shared config repository for syncing with Home Assistant.
     * Call this after OAuth authentication is complete.
     */
    fun setSharedConfigRepository(repo: SharedConfigRepository?) {
        sharedConfigRepo = repo
        Log.d(TAG, "SharedConfigRepository ${if (repo != null) "set" else "cleared"}")
    }

    /**
     * Check if shared config is available.
     */
    fun isSharedConfigAvailable(): Boolean = sharedConfigRepo != null

    /**
     * Refresh shared profiles from Home Assistant.
     * Call this on app launch and when returning to profile list.
     */
    suspend fun refreshSharedProfiles(): List<Profile> = mutex.withLock {
        val repo = sharedConfigRepo
        if (repo == null) {
            Log.d(TAG, "No SharedConfigRepository, skipping refresh")
            return@withLock emptyList()
        }

        return@withLock try {
            val config = repo.getSharedConfig()
            cachedSharedProfiles = config?.profiles?.map { Profile.fromShared(it) } ?: emptyList()
            Log.d(TAG, "Refreshed ${cachedSharedProfiles.size} shared profiles")
            updateCombinedProfiles()
            cachedSharedProfiles
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh shared profiles", e)
            cachedSharedProfiles // Return cached on error
        }
    }

    /**
     * Get cached shared profiles (without network call).
     */
    fun getSharedProfiles(): List<Profile> = cachedSharedProfiles

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
     * Ensures at least one profile exists and an active profile is set.
     * - If shared profiles exist (from HA), uses the first one as active.
     * - Otherwise, creates a default local profile.
     * Safe to call multiple times.
     */
    fun ensureDefaultProfileExists() {
        val localProfiles = getLocalProfiles()
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

        if (localProfiles.isEmpty() && cachedSharedProfiles.isEmpty()) {
            // No profiles at all - create default local profile
            Log.d(TAG, "No profiles exist, creating default local profile")
            createLocalProfile(Profile.createDefault())
        } else if (localProfiles.isEmpty() && cachedSharedProfiles.isNotEmpty()) {
            // Shared profiles exist but no local - use first shared as active
            val firstShared = cachedSharedProfiles.first()
            if (activeId == null || getProfileById(activeId) == null) {
                Log.d(TAG, "Using first shared profile as active: ${firstShared.name}")
                prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, firstShared.id).apply()
            }
        } else if (activeId == null || getProfileById(activeId) == null) {
            // Has profiles but no valid active - set first available as active
            val allProfiles = cachedSharedProfiles + localProfiles
            if (allProfiles.isNotEmpty()) {
                Log.d(TAG, "Setting first available profile as active: ${allProfiles.first().name}")
                prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, allProfiles.first().id).apply()
            }
        }
    }

    // ========== Combined Profile Operations ==========

    /**
     * Returns all profiles (shared + local).
     * Shared profiles appear first.
     */
    fun getAllProfiles(): List<Profile> {
        return _profiles.value
    }

    /**
     * Returns only local profiles.
     */
    fun getLocalProfiles(): List<Profile> {
        val jsonString = prefs.getString(KEY_PROFILES, null) ?: return emptyList()

        return try {
            json.decodeFromString<List<Profile>>(jsonString)
                .map { it.copy(source = ProfileSource.LOCAL) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local profiles", e)
            emptyList()
        }
    }

    /**
     * Returns a profile by ID (searches both local and shared).
     */
    fun getProfileById(id: String): Profile? {
        return cachedSharedProfiles.find { it.id == id }
            ?: getLocalProfiles().find { it.id == id }
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
     * Sets a profile as the active profile.
     */
    fun setActiveProfile(profileId: String) {
        val profile = getProfileById(profileId)
        require(profile != null) { "Profile with ID $profileId does not exist" }
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
    }

    // ========== Local Profile Operations ==========

    /**
     * Creates a new local profile. Returns the created profile.
     * Throws IllegalArgumentException if name is blank or duplicate among local profiles.
     */
    fun createLocalProfile(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val localProfiles = getLocalProfiles()

        // Check for duplicate names among local profiles
        if (localProfiles.any { it.name.equals(profile.name, ignoreCase = true) && it.id != profile.id }) {
            throw IllegalArgumentException("A local profile with the name '${profile.name}' already exists")
        }

        val localProfile = profile.copy(source = ProfileSource.LOCAL)

        // If this is the first profile, make it active
        if (localProfiles.isEmpty() && cachedSharedProfiles.isEmpty()) {
            saveLocalProfilesToStorage(listOf(localProfile))
            setActiveProfile(localProfile.id)
            return localProfile
        }

        saveLocalProfilesToStorage(localProfiles + localProfile)
        return localProfile
    }

    /**
     * Creates a new profile (legacy method for backward compatibility).
     * Creates as LOCAL profile.
     */
    fun createProfile(profile: Profile): Profile {
        return createLocalProfile(profile)
    }

    /**
     * Updates a local profile.
     * Throws IllegalArgumentException if ID doesn't exist or name is duplicate.
     */
    fun updateLocalProfile(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        val localProfiles = getLocalProfiles()
        val index = localProfiles.indexOfFirst { it.id == profile.id }

        if (index == -1) {
            throw IllegalArgumentException("Local profile with ID ${profile.id} does not exist")
        }

        // Check for duplicate names (excluding this profile) among local profiles
        if (localProfiles.any {
                it.name.equals(profile.name, ignoreCase = true) && it.id != profile.id
            }) {
            throw IllegalArgumentException("A local profile with the name '${profile.name}' already exists")
        }

        val updated = localProfiles.toMutableList()
        updated[index] = profile.copy(source = ProfileSource.LOCAL)

        saveLocalProfilesToStorage(updated)
        return profile
    }

    /**
     * Updates a profile (legacy method - routes based on source).
     */
    fun updateProfile(profile: Profile): Profile {
        return when (profile.source) {
            ProfileSource.LOCAL -> updateLocalProfile(profile)
            ProfileSource.SHARED -> {
                // For shared profiles, we need the async method
                throw IllegalStateException("Use updateSharedProfile() for shared profiles")
            }
        }
    }

    /**
     * Deletes a local profile by ID.
     * Throws IllegalStateException if trying to delete the last profile.
     */
    fun deleteLocalProfile(profileId: String) {
        val localProfiles = getLocalProfiles()
        val totalProfiles = localProfiles.size + cachedSharedProfiles.size

        if (totalProfiles == 1) {
            throw IllegalStateException("Cannot delete the last profile. Create another profile first.")
        }

        localProfiles.find { it.id == profileId }
            ?: throw IllegalArgumentException("Local profile with ID $profileId does not exist")

        val remaining = localProfiles.filter { it.id != profileId }
        saveLocalProfilesToStorage(remaining)

        // If we deleted the active profile, set first remaining as active
        if (prefs.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
            val allRemaining = cachedSharedProfiles + remaining
            if (allRemaining.isNotEmpty()) {
                setActiveProfile(allRemaining.first().id)
            }
        }
    }

    /**
     * Deletes a profile (legacy method - routes based on source).
     */
    fun deleteProfile(profileId: String) {
        // Check if it's a shared profile
        val sharedProfile = cachedSharedProfiles.find { it.id == profileId }
        if (sharedProfile != null) {
            throw IllegalStateException("Use deleteSharedProfile() for shared profiles")
        }
        deleteLocalProfile(profileId)
    }

    /**
     * Duplicates a profile with a new name (creates as local).
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
            interruptable = original.interruptable,
            toolFilterMode = original.toolFilterMode,
            selectedToolNames = original.selectedToolNames,
            allowedModelCameras = original.allowedModelCameras,
            enableTranscription = original.enableTranscription,
            source = ProfileSource.LOCAL
        )

        return createLocalProfile(duplicate)
    }

    // ========== Shared Profile Operations ==========

    /**
     * Create a new shared profile.
     * @throws ProfileNameConflictException if name is already taken.
     */
    suspend fun createSharedProfile(profile: Profile): Profile = mutex.withLock {
        val repo = sharedConfigRepo
            ?: throw IllegalStateException("Shared config not available")

        // Check name availability
        if (!repo.isProfileNameAvailable(profile.name)) {
            throw ProfileNameConflictException(
                "A shared profile named '${profile.name}' already exists"
            )
        }

        val sharedProfile = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            source = ProfileSource.SHARED
        )

        val newId = repo.upsertProfile(sharedProfile)
            ?: throw Exception("Failed to create shared profile")

        val createdProfile = sharedProfile.copy(id = newId)
        cachedSharedProfiles = cachedSharedProfiles + createdProfile
        updateCombinedProfiles()

        Log.i(TAG, "Created shared profile: ${profile.name}")
        return@withLock createdProfile
    }

    /**
     * Update a shared profile.
     * @throws ProfileNameConflictException if name is already taken by another profile.
     */
    suspend fun updateSharedProfile(profile: Profile): Profile = mutex.withLock {
        require(profile.source == ProfileSource.SHARED) { "Profile must be shared" }

        val repo = sharedConfigRepo
            ?: throw IllegalStateException("Shared config not available")

        // Check name availability (excluding this profile)
        if (!repo.isProfileNameAvailable(profile.name, profile.id)) {
            throw ProfileNameConflictException(
                "A shared profile named '${profile.name}' already exists"
            )
        }

        val success = repo.upsertProfile(profile)
        if (success == null) {
            throw Exception("Failed to update shared profile")
        }

        // Update cache
        cachedSharedProfiles = cachedSharedProfiles.map {
            if (it.id == profile.id) profile else it
        }
        updateCombinedProfiles()

        Log.i(TAG, "Updated shared profile: ${profile.name}")
        return@withLock profile
    }

    /**
     * Delete a shared profile.
     */
    suspend fun deleteSharedProfile(profileId: String): Boolean = mutex.withLock {
        val repo = sharedConfigRepo ?: return@withLock false

        val totalProfiles = getLocalProfiles().size + cachedSharedProfiles.size
        if (totalProfiles == 1) {
            throw IllegalStateException("Cannot delete the last profile. Create another profile first.")
        }

        val success = repo.deleteProfile(profileId)
        if (success) {
            cachedSharedProfiles = cachedSharedProfiles.filter { it.id != profileId }
            updateCombinedProfiles()

            // If we deleted the active profile, set first remaining as active
            if (prefs.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
                val allRemaining = cachedSharedProfiles + getLocalProfiles()
                if (allRemaining.isNotEmpty()) {
                    setActiveProfile(allRemaining.first().id)
                }
            }
            Log.i(TAG, "Deleted shared profile: $profileId")
        }
        return@withLock success
    }

    /**
     * Upload a local profile to shared storage.
     * @param deleteLocal If true, deletes the local copy after successful upload.
     * @throws ProfileNameConflictException if name is already taken.
     */
    suspend fun uploadToShared(profile: Profile, deleteLocal: Boolean = true): Profile = mutex.withLock {
        require(profile.source == ProfileSource.LOCAL) { "Profile must be local" }

        val repo = sharedConfigRepo
            ?: throw IllegalStateException("Shared config not available")

        // Check name availability
        if (!repo.isProfileNameAvailable(profile.name)) {
            throw ProfileNameConflictException(
                "A shared profile named '${profile.name}' already exists"
            )
        }

        // Create new profile with new ID for shared storage
        val sharedProfile = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            source = ProfileSource.SHARED
        )

        val newId = repo.upsertProfile(sharedProfile)
            ?: throw Exception("Failed to upload profile to Home Assistant")

        // Add to cache
        val uploadedProfile = sharedProfile.copy(id = newId)
        cachedSharedProfiles = cachedSharedProfiles + uploadedProfile

        // Optionally delete local copy
        if (deleteLocal) {
            val localProfiles = getLocalProfiles().filter { it.id != profile.id }
            saveLocalProfilesToStorage(localProfiles)
        }

        updateCombinedProfiles()
        Log.i(TAG, "Uploaded profile '${profile.name}' to shared storage")
        return@withLock uploadedProfile
    }

    /**
     * Download a shared profile to local storage (creates a local copy).
     */
    fun downloadToLocal(profile: Profile): Profile {
        require(profile.source == ProfileSource.SHARED) { "Profile must be shared" }

        val localProfile = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            source = ProfileSource.LOCAL,
            name = "${profile.name} (Local Copy)",
            lastModified = null,
            modifiedBy = null
        )

        val localProfiles = getLocalProfiles()
        saveLocalProfilesToStorage(localProfiles + localProfile)

        Log.i(TAG, "Downloaded profile '${profile.name}' to local storage")
        return localProfile
    }

    // ========== Storage Operations ==========

    private fun saveLocalProfilesToStorage(profiles: List<Profile>) {
        val jsonString = json.encodeToString(profiles)
        prefs.edit().putString(KEY_PROFILES, jsonString).apply()
        updateCombinedProfiles()
    }

    private fun updateCombinedProfiles() {
        // Shared profiles first, then local
        _profiles.value = cachedSharedProfiles + getLocalProfiles()
    }

    /**
     * Clears all local profiles and resets to default state.
     * Used for error recovery or testing.
     */
    fun resetToDefault(context: Context) {
        prefs.edit().clear().apply()
        cachedSharedProfiles = emptyList()
        val defaultProfile = Profile.createDefault()
        saveLocalProfilesToStorage(listOf(defaultProfile))
        setActiveProfile(defaultProfile.id)
    }

    /**
     * Update the cached shared profiles from external source (e.g., SharedConfigCache).
     * Used when profiles are loaded from cache on app start.
     */
    fun updateCachedSharedProfiles(profiles: List<Profile>) {
        cachedSharedProfiles = profiles.map { it.copy(source = ProfileSource.SHARED) }
        updateCombinedProfiles()
        Log.d(TAG, "Updated cached shared profiles: ${profiles.size} profiles")
    }
}
