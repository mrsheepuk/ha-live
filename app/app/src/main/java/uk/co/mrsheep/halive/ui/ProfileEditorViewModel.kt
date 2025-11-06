package uk.co.mrsheep.halive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _editorState = MutableStateFlow<ProfileEditorState>(ProfileEditorState.Idle)
    val editorState: StateFlow<ProfileEditorState> = _editorState

    /**
     * Loads an existing profile for editing.
     */
    fun loadProfile(profileId: String) {
        viewModelScope.launch {
            val profile = ProfileManager.getProfileById(profileId)
            if (profile != null) {
                _editorState.value = ProfileEditorState.Loaded(profile)
            } else {
                _editorState.value = ProfileEditorState.SaveError("Profile not found")
            }
        }
    }

    /**
     * Loads a profile for duplication (pre-fills with modified name).
     */
    fun loadForDuplicate(profileId: String) {
        viewModelScope.launch {
            val original = ProfileManager.getProfileById(profileId)
            if (original != null) {
                // Create a temporary profile with "Copy of" prefix
                val duplicate = original.copy(
                    id = "", // New ID will be generated on save
                    name = "Copy of ${original.name}",
                    isDefault = false
                )
                _editorState.value = ProfileEditorState.Loaded(duplicate)
            } else {
                _editorState.value = ProfileEditorState.SaveError("Profile not found")
            }
        }
    }

    /**
     * Saves a profile (create or update based on existingId).
     *
     * @param name The profile name
     * @param systemPrompt The system prompt text
     * @param existingId The ID of existing profile (null for create)
     */
    fun saveProfile(name: String, systemPrompt: String, existingId: String?) {
        viewModelScope.launch {
            _editorState.value = ProfileEditorState.Saving

            try {
                // Validate blank name
                if (name.isBlank()) {
                    _editorState.value = ProfileEditorState.SaveError("Profile name cannot be blank")
                    return@launch
                }

                if (existingId != null) {
                    // Update existing profile
                    val existing = ProfileManager.getProfileById(existingId)
                    if (existing == null) {
                        _editorState.value = ProfileEditorState.SaveError("Profile not found")
                        return@launch
                    }

                    val updated = existing.copy(
                        name = name.trim(),
                        systemPrompt = systemPrompt
                    )

                    ProfileManager.updateProfile(updated)
                } else {
                    // Create new profile
                    val newProfile = Profile(
                        name = name.trim(),
                        systemPrompt = systemPrompt,
                        isDefault = false
                    )

                    ProfileManager.createProfile(newProfile)
                }

                _editorState.value = ProfileEditorState.SaveSuccess
            } catch (e: IllegalArgumentException) {
                // Handle duplicate name or other validation errors
                _editorState.value = ProfileEditorState.SaveError(
                    e.message ?: "Failed to save profile"
                )
            } catch (e: Exception) {
                _editorState.value = ProfileEditorState.SaveError(
                    "Unexpected error: ${e.message}"
                )
            }
        }
    }
}

// Profile Editor states
sealed class ProfileEditorState {
    object Idle : ProfileEditorState()
    object Saving : ProfileEditorState()
    object SaveSuccess : ProfileEditorState()
    data class SaveError(val message: String) : ProfileEditorState()
    data class Loaded(val profile: Profile) : ProfileEditorState()
}
