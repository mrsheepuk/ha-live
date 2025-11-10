package uk.co.mrsheep.halive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ProfileExportImport

/**
 * ViewModel for ProfileManagementActivity.
 *
 * Manages profile list state and handles profile operations
 * (set default, delete, duplicate).
 */
class ProfileManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ProfileManagementState>(ProfileManagementState.Loading)
    val state: StateFlow<ProfileManagementState> = _state

    init {
        loadProfiles()
    }

    /**
     * Loads profiles from ProfileManager and observes changes.
     */
    fun loadProfiles() {
        viewModelScope.launch {
            try {
                _state.value = ProfileManagementState.Loading

                // Observe the profiles StateFlow from ProfileManager
                ProfileManager.profiles.collect { profiles ->
                    _state.value = ProfileManagementState.Loaded(profiles)
                }
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to load profiles"
                )
            }
        }
    }

    /**
     * Sets a profile as the default.
     */
    fun setDefaultProfile(profileId: String) {
        viewModelScope.launch {
            try {
                ProfileManager.setDefaultProfile(profileId)
                // ProfileManager will emit updated list via StateFlow
            } catch (e: Exception) {
                _state.value = ProfileManagementState.Error(
                    e.message ?: "Failed to set default profile"
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
                ProfileManager.deleteProfile(profileId)
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
}

/**
 * State for the Profile Management screen.
 */
sealed class ProfileManagementState {
    object Loading : ProfileManagementState()
    data class Loaded(val profiles: List<Profile>) : ProfileManagementState()
    data class Error(val message: String) : ProfileManagementState()
}
