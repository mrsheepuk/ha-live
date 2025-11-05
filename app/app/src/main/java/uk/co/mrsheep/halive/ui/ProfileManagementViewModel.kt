package uk.co.mrsheep.halive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager

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
}

/**
 * State for the Profile Management screen.
 */
sealed class ProfileManagementState {
    object Loading : ProfileManagementState()
    data class Loaded(val profiles: List<Profile>) : ProfileManagementState()
    data class Error(val message: String) : ProfileManagementState()
}
