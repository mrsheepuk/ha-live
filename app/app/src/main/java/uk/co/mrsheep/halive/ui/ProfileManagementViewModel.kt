package uk.co.mrsheep.halive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ProfileExportImport
import uk.co.mrsheep.halive.core.ProfileSource
import uk.co.mrsheep.halive.core.ProfileNameConflictException

/**
 * ViewModel for ProfileManagementActivity.
 *
 * Manages profile list state and handles profile operations
 * (set active, delete, duplicate).
 */
class ProfileManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ProfileManagementState>(ProfileManagementState.Loading)
    val state: StateFlow<ProfileManagementState> = _state

    init {
        loadProfiles()
    }

    private val app: HAGeminiApp
        get() = getApplication()

    /**
     * Loads profiles from ProfileManager and observes changes.
     * Also loads the active profile ID and includes it in the state.
     */
    fun loadProfiles() {
        viewModelScope.launch {
            try {
                _state.value = ProfileManagementState.Loading

                // Only refresh shared profiles if the HACS integration is installed
                if (app.isSharedConfigAvailable()) {
                    ProfileManager.refreshSharedProfiles()
                }

                // Observe the profiles StateFlow from ProfileManager
                ProfileManager.profiles.collect { profiles ->
                    val activeProfileId = ProfileManager.getActiveProfile()?.id
                    _state.value = ProfileManagementState.Loaded(
                        profiles = profiles,
                        activeProfileId = activeProfileId
                    )
                }
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to load profiles"
                )
            }
        }
    }

    /**
     * Sets a profile as the active profile.
     */
    fun setActiveProfile(profileId: String) {
        viewModelScope.launch {
            try {
                ProfileManager.setActiveProfile(profileId)

                // Manually refresh state since setActiveProfile doesn't trigger StateFlow emission
                val currentState = _state.value
                if (currentState is ProfileManagementState.Loaded) {
                    _state.value = currentState.copy(activeProfileId = profileId)
                }
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to set active profile"
                )
            }
        }
    }

    /**
     * Deletes a profile.
     * Handles the "last profile" error from ProfileManager.
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            try {
                val profile = ProfileManager.getProfileById(profileId)
                if (profile == null) {
                    _state.value = ProfileManagementState.Error("Profile not found")
                    return@launch
                }

                when (profile.source) {
                    ProfileSource.LOCAL -> ProfileManager.deleteLocalProfile(profileId)
                    ProfileSource.SHARED -> {
                        val success = ProfileManager.deleteSharedProfile(profileId)
                        if (!success) {
                            _state.value = ProfileManagementState.Error("Failed to delete shared profile")
                            return@launch
                        }
                    }
                }
                // ProfileManager will emit updated list via StateFlow
            } catch (e: IllegalStateException) {
                // This is the "last profile" error
                _state.value = ProfileManagementState.Error(
                    "Cannot delete the last profile. Create another profile first."
                )
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to delete profile"
                )
            }
        }
    }

    /**
     * Duplicates a profile with a generated name.
     */
    fun duplicateProfile(profileId: String) {
        viewModelScope.launch {
            try {
                val original = ProfileManager.getProfileById(profileId)
                if (original != null) {
                    val newName = "Copy of ${original.name}"
                    ProfileManager.duplicateProfile(profileId, newName)
                    // ProfileManager will emit updated list via StateFlow
                }
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to duplicate profile"
                )
            }
        }
    }

    /**
     * Exports a list of profiles to a JSON string.
     */
    fun exportProfiles(profiles: List<Profile>): String {
        return ProfileExportImport.exportProfiles(profiles)
    }

    /**
     * Exports a single profile by ID.
     * Returns the JSON string if found, or null if profile doesn't exist.
     */
    fun exportSingleProfile(profileId: String): String? {
        val profile = ProfileManager.getProfileById(profileId) ?: return null
        return ProfileExportImport.exportProfiles(listOf(profile))
    }

    /**
     * Imports profiles from a JSON string.
     * Handles conflict resolution and updates the state.
     * Returns the ImportResult with conflict information.
     */
    fun importProfiles(jsonString: String, onSuccess: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val existingProfiles = ProfileManager.getAllProfiles()
                val result = ProfileExportImport.importProfiles(jsonString, existingProfiles)

                result.fold(
                    onSuccess = { importResult ->
                        // Add imported profiles to ProfileManager
                        importResult.profiles.forEach { profile ->
                            try {
                                ProfileManager.createProfile(profile)
                            } catch (e: Exception) {
                                // Log duplicate name errors but continue with other imports
                            }
                        }
                        // Notify caller with count and conflicts
                        onSuccess(importResult.profiles.size, importResult.conflicts.size)
                        // State will be updated by ProfileManager's StateFlow emissions
                    },
                    onFailure = { exception ->
                        _state.value = ProfileManagementState.Error(
                            exception.message ?: "Failed to import profiles"
                        )
                    }
                )
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to import profiles"
                )
            }
        }
    }

    /**
     * Uploads a local profile to shared storage.
     */
    fun uploadToShared(profile: Profile, deleteLocal: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                ProfileManager.uploadToShared(profile, deleteLocal)
                onSuccess()
            } catch (e: ProfileNameConflictException) {
                onError(e.message ?: "Name already exists")
            } catch (e: Exception) {
                onError(e.message ?: "Upload failed")
            }
        }
    }

    /**
     * Downloads a shared profile to local storage.
     */
    fun downloadToLocal(profile: Profile): Profile {
        return ProfileManager.downloadToLocal(profile)
    }

    /**
     * Refreshes profiles from the backend.
     * Handles errors gracefully and sets offline state if refresh fails.
     * Skips network call if HACS integration is not installed.
     */
    fun refreshProfiles() {
        viewModelScope.launch {
            // Don't show loading state for refresh
            try {
                // Only attempt network refresh if HACS integration is installed
                if (app.isSharedConfigAvailable()) {
                    ProfileManager.refreshSharedProfiles()
                }
                val profiles = ProfileManager.getAllProfiles()
                val activeId = ProfileManager.getActiveProfile()?.id
                _state.value = ProfileManagementState.Loaded(
                    profiles = profiles,
                    activeProfileId = activeId,
                    isOffline = false
                )
            } catch (e: Exception) {
                // On error, show cached data but mark as offline
                val profiles = ProfileManager.getAllProfiles()
                val activeId = ProfileManager.getActiveProfile()?.id
                _state.value = ProfileManagementState.Loaded(
                    profiles = profiles,
                    activeProfileId = activeId,
                    isOffline = true
                )
            }
        }
    }

    /**
     * Uploads multiple profiles to shared storage.
     * Called as part of the migration feature.
     */
    fun uploadAllProfiles(profiles: List<Profile>, onComplete: (Int, Int) -> Unit) {
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0

            for (profile in profiles) {
                try {
                    ProfileManager.uploadToShared(profile, deleteLocal = true)
                    successCount++
                } catch (e: ProfileNameConflictException) {
                    failCount++
                } catch (e: Exception) {
                    failCount++
                }
            }

            onComplete(successCount, failCount)

            // Refresh the list
            refreshProfiles()
        }
    }
}

/**
 * State for the Profile Management screen.
 */
sealed class ProfileManagementState {
    object Loading : ProfileManagementState()
    data class Loaded(
        val profiles: List<Profile>,
        val activeProfileId: String? = null,
        val isOffline: Boolean = false
    ) : ProfileManagementState()
    data class Error(val message: String) : ProfileManagementState()
}
