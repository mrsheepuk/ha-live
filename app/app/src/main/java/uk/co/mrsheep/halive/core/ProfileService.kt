package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.mrsheep.halive.services.SharedConfigRepository

/**
 * Service that combines local and remote profile repositories.
 *
 * This is the main entry point for profile operations in the app.
 * - Local profiles are stored in SharedPreferences (device-specific)
 * - Remote profiles are fetched directly from Home Assistant (no caching)
 * - Active profile selection is stored locally
 *
 * The service exposes a StateFlow for reactive UI updates.
 */
class ProfileService(
    private val context: Context,
    private val localRepo: LocalProfileRepository
) {
    companion object {
        private const val TAG = "ProfileService"
        private const val PREFS_NAME = "profile_service"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Remote repository - set after HA authentication
    private var remoteRepo: RemoteProfileRepository? = null

    // StateFlow for reactive UI updates
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    // Loading state for UI
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * Set the remote repository after Home Assistant authentication.
     */
    fun setRemoteRepository(sharedConfigRepo: SharedConfigRepository?) {
        remoteRepo = sharedConfigRepo?.let { RemoteProfileRepository(it) }
        Log.d(TAG, "Remote repository ${if (remoteRepo != null) "set" else "cleared"}")
    }

    /**
     * Check if remote profiles are available (HA integration installed).
     */
    fun isRemoteAvailable(): Boolean = remoteRepo != null

    /**
     * Refresh profiles from both repositories.
     * Call this when entering the profile list screen or starting a session.
     */
    suspend fun refreshProfiles() {
        _isLoading.value = true
        try {
            val remote = remoteRepo?.getAll() ?: emptyList()
            val local = localRepo.getAll()
            _profiles.value = remote + local
            Log.d(TAG, "Refreshed profiles: ${remote.size} remote, ${local.size} local")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Get all profiles (from the last refresh).
     * For fresh data, call refreshProfiles() first.
     */
    fun getAllProfiles(): List<Profile> = _profiles.value

    /**
     * Get a profile by ID.
     * Searches the current cached list first, then fetches fresh if not found.
     */
    suspend fun getProfileById(id: String): Profile? {
        // First check cached list
        _profiles.value.find { it.id == id }?.let { return it }

        // Not in cache, try to fetch fresh
        return remoteRepo?.getById(id) ?: localRepo.getById(id)
    }

    /**
     * Get the active profile.
     * Returns null if no active profile is set or the profile no longer exists.
     */
    suspend fun getActiveProfile(): Profile? {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return if (activeId != null) {
            getProfileById(activeId)
        } else {
            // Fallback: return first profile
            getAllProfiles().firstOrNull() ?: run {
                // Refresh and try again
                refreshProfiles()
                getAllProfiles().firstOrNull()
            }
        }
    }

    /**
     * Get the active profile ID.
     */
    fun getActiveProfileId(): String? = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

    /**
     * Set a profile as the active profile.
     */
    fun setActiveProfile(profileId: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        Log.d(TAG, "Set active profile: $profileId")
    }

    /**
     * Create a new profile.
     * Routes to local or remote repository based on targetSource.
     */
    suspend fun createProfile(profile: Profile, targetSource: ProfileSource = ProfileSource.LOCAL): Profile {
        val created = when (targetSource) {
            ProfileSource.LOCAL -> localRepo.create(profile)
            ProfileSource.SHARED -> {
                val repo = remoteRepo
                    ?: throw IllegalStateException("Remote repository not available")
                repo.create(profile)
            }
        }

        // Update the StateFlow
        refreshProfiles()

        // If this is the first profile, make it active
        if (getAllProfiles().size == 1) {
            setActiveProfile(created.id)
        }

        return created
    }

    /**
     * Update an existing profile.
     * Routes to the appropriate repository based on profile source.
     */
    suspend fun updateProfile(profile: Profile): Profile {
        val updated = when (profile.source) {
            ProfileSource.LOCAL -> localRepo.update(profile)
            ProfileSource.SHARED -> {
                val repo = remoteRepo
                    ?: throw IllegalStateException("Remote repository not available")
                repo.update(profile)
            }
        }

        // Update the StateFlow
        refreshProfiles()

        return updated
    }

    /**
     * Delete a profile.
     * Routes to the appropriate repository based on profile source.
     */
    suspend fun deleteProfile(profileId: String): Boolean {
        val profile = getProfileById(profileId)
            ?: throw IllegalArgumentException("Profile with ID $profileId does not exist")

        // Prevent deleting the last profile
        if (getAllProfiles().size <= 1) {
            throw IllegalStateException("Cannot delete the last profile. Create another profile first.")
        }

        val success = when (profile.source) {
            ProfileSource.LOCAL -> localRepo.delete(profileId)
            ProfileSource.SHARED -> {
                val repo = remoteRepo
                    ?: throw IllegalStateException("Remote repository not available")
                repo.delete(profileId)
            }
        }

        if (success) {
            // Update the StateFlow
            refreshProfiles()

            // If we deleted the active profile, set first remaining as active
            if (getActiveProfileId() == profileId) {
                getAllProfiles().firstOrNull()?.let { setActiveProfile(it.id) }
            }
        }

        return success
    }

    /**
     * Duplicate a profile (always creates as local).
     */
    suspend fun duplicateProfile(profileId: String, newName: String): Profile {
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

        return createProfile(duplicate, ProfileSource.LOCAL)
    }

    /**
     * Upload a local profile to remote storage.
     * @param deleteLocal If true, deletes the local copy after successful upload.
     */
    suspend fun uploadToRemote(profile: Profile, deleteLocal: Boolean = true): Profile {
        require(profile.source == ProfileSource.LOCAL) { "Profile must be local" }

        val repo = remoteRepo
            ?: throw IllegalStateException("Remote repository not available")

        // Create in remote
        val uploaded = repo.create(profile.copy(
            id = java.util.UUID.randomUUID().toString()
        ))

        // Optionally delete local
        if (deleteLocal) {
            localRepo.delete(profile.id)
        }

        // Update the StateFlow
        refreshProfiles()

        // Update active profile if needed
        if (getActiveProfileId() == profile.id) {
            setActiveProfile(uploaded.id)
        }

        Log.i(TAG, "Uploaded profile '${profile.name}' to remote storage")
        return uploaded
    }

    /**
     * Download a remote profile to local storage (creates a local copy).
     */
    suspend fun downloadToLocal(profile: Profile): Profile {
        require(profile.source == ProfileSource.SHARED) { "Profile must be shared/remote" }

        val localProfile = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            source = ProfileSource.LOCAL,
            name = "${profile.name} (Local Copy)",
            lastModified = null,
            modifiedBy = null
        )

        val created = localRepo.create(localProfile)

        // Update the StateFlow
        refreshProfiles()

        Log.i(TAG, "Downloaded profile '${profile.name}' to local storage")
        return created
    }

    /**
     * Ensure at least one profile exists.
     * Creates a default local profile if needed.
     */
    suspend fun ensureDefaultProfileExists() {
        refreshProfiles()

        if (getAllProfiles().isEmpty()) {
            Log.d(TAG, "No profiles exist, creating default local profile")
            val defaultProfile = Profile.createDefault()
            createProfile(defaultProfile, ProfileSource.LOCAL)
        }

        // Ensure active profile is set
        if (getActiveProfileId() == null || getProfileById(getActiveProfileId()!!) == null) {
            getAllProfiles().firstOrNull()?.let { setActiveProfile(it.id) }
        }
    }

    /**
     * Initialize the service with local profiles.
     * Call this on app startup before HA authentication.
     */
    suspend fun initialize() {
        val local = localRepo.getAll()
        _profiles.value = local
        Log.d(TAG, "Initialized with ${local.size} local profiles")
    }
}
