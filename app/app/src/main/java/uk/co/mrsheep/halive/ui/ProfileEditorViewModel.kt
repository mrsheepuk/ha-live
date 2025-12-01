package uk.co.mrsheep.halive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ToolFilterMode
import uk.co.mrsheep.halive.core.ProfileSource
import uk.co.mrsheep.halive.core.ProfileNameConflictException
import uk.co.mrsheep.halive.core.ProfileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _editorState = MutableStateFlow<ProfileEditorState>(ProfileEditorState.Idle)
    val editorState: StateFlow<ProfileEditorState> = _editorState

    private val profileService: ProfileService
        get() = (getApplication() as HAGeminiApp).profileService

    /**
     * Loads an existing profile for editing.
     */
    fun loadProfile(profileId: String) {
        viewModelScope.launch {
            val profile = profileService.getProfileById(profileId)
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
            val original = profileService.getProfileById(profileId)
            if (original != null) {
                // Create a temporary profile with "Copy of" prefix
                val duplicate = original.copy(
                    id = "", // New ID will be generated on save
                    name = "Copy of ${original.name}"
                )
                _editorState.value = ProfileEditorState.Loaded(duplicate)
            } else {
                _editorState.value = ProfileEditorState.SaveError("Profile not found")
            }
        }
    }

    /**
     * Saves a profile (create or update based on existingId).
     * Handles both LOCAL and SHARED profiles.
     *
     * @param name The profile name
     * @param systemPrompt The system prompt text
     * @param personality The personality prompt text
     * @param backgroundInfo The background information text
     * @param initialMessageToAgent The initial message to send to the agent
     * @param model The AI model to use
     * @param voice The voice to use for audio responses
     * @param includeLiveContext Whether to include live context with system prompt
     * @param enableTranscription Whether to enable transcription display
     * @param autoStartChat Whether to auto-start chat when app opens
     * @param interruptable Whether the conversation is interruptable
     * @param toolFilterMode Whether to use all tools or only selected tools
     * @param selectedToolNames Set of tool names to use if in SELECTED mode
     * @param allowedModelCameras Set of camera entity IDs the model can access
     * @param existingId The ID of existing profile (null for create)
     * @param targetSource The target source for new profiles (default LOCAL)
     * @param originalLastModified The original lastModified timestamp for conflict detection
     * @param forceOverwrite Whether to force overwrite in case of conflict (default false)
     */
    fun saveProfile(
        name: String,
        systemPrompt: String,
        personality: String,
        backgroundInfo: String,
        initialMessageToAgent: String,
        model: String,
        voice: String,
        includeLiveContext: Boolean,
        enableTranscription: Boolean,
        autoStartChat: Boolean,
        interruptable: Boolean,
        toolFilterMode: ToolFilterMode,
        selectedToolNames: Set<String>,
        allowedModelCameras: Set<String>,
        existingId: String?,
        targetSource: ProfileSource = ProfileSource.LOCAL,
        originalLastModified: String? = null,
        forceOverwrite: Boolean = false
    ) {
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
                    val existing = profileService.getProfileById(existingId)
                    if (existing == null) {
                        _editorState.value = ProfileEditorState.SaveError("Profile not found")
                        return@launch
                    }

                    // Check for conflict on shared profiles
                    if (existing.source == ProfileSource.SHARED && !forceOverwrite) {
                        // Check if profile has been modified since we loaded it
                        val currentProfile = profileService.getProfileById(existingId)
                        if (currentProfile?.lastModified != originalLastModified) {
                            _editorState.value = ProfileEditorState.ConflictDetected(currentProfile)
                            return@launch
                        }
                    }

                    val updated = existing.copy(
                        name = name.trim(),
                        systemPrompt = systemPrompt,
                        personality = personality,
                        backgroundInfo = backgroundInfo,
                        model = model,
                        voice = voice,
                        includeLiveContext = includeLiveContext,
                        enableTranscription = enableTranscription,
                        autoStartChat = autoStartChat,
                        interruptable = interruptable,
                        initialMessageToAgent = initialMessageToAgent,
                        toolFilterMode = toolFilterMode,
                        selectedToolNames = selectedToolNames,
                        allowedModelCameras = allowedModelCameras
                    )

                    profileService.updateProfile(updated)
                } else {
                    // Create new profile
                    val newProfile = Profile(
                        name = name.trim(),
                        systemPrompt = systemPrompt,
                        personality = personality,
                        backgroundInfo = backgroundInfo,
                        model = model,
                        voice = voice,
                        includeLiveContext = includeLiveContext,
                        enableTranscription = enableTranscription,
                        autoStartChat = autoStartChat,
                        interruptable = interruptable,
                        initialMessageToAgent = initialMessageToAgent,
                        toolFilterMode = toolFilterMode,
                        selectedToolNames = selectedToolNames,
                        allowedModelCameras = allowedModelCameras,
                        source = targetSource
                    )

                    profileService.createProfile(newProfile, targetSource)
                }

                _editorState.value = ProfileEditorState.SaveSuccess
            } catch (e: ProfileNameConflictException) {
                _editorState.value = ProfileEditorState.SaveError(
                    e.message ?: "A profile with this name already exists"
                )
            } catch (e: IllegalArgumentException) {
                // Handle duplicate name or other validation errors
                _editorState.value = ProfileEditorState.SaveError(
                    e.message ?: "Failed to save profile"
                )
            } catch (e: IllegalStateException) {
                _editorState.value = ProfileEditorState.SaveError(
                    e.message ?: "Shared config not available"
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
    data class ConflictDetected(val serverProfile: Profile?) : ProfileEditorState()
}
