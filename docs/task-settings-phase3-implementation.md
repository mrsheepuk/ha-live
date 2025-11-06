# Phase 3 Implementation Plan: Profile UI & Main Screen Integration

**Parent Document**: [task-settings-and-profiles.md](./task-settings-and-profiles.md)

## Overview

Phase 3 brings profiles to the main UI:
- Profile dropdown in MainActivity for quick switching
- ProfileManagementActivity for viewing/managing profiles
- ProfileEditorActivity for creating/editing profiles
- Remove SystemPrompt UI from MainActivity (now in profiles)
- Wire up profile switching to GeminiService

---

## Prerequisites

✅ Phase 1 complete: OnboardingActivity, SettingsActivity exist
✅ Phase 2 complete: ProfileManager backend functional with tests

---

## Files to Create

### Activities & ViewModels
```
app/src/main/java/uk/co/mrsheep/halive/ui/
├── ProfileManagementActivity.kt (NEW - list view)
├── ProfileManagementViewModel.kt (NEW)
├── ProfileEditorActivity.kt (NEW - create/edit)
└── ProfileEditorViewModel.kt (NEW)
```

### Layouts
```
app/src/main/res/layout/
├── activity_profile_management.xml (NEW - RecyclerView list)
├── activity_profile_editor.xml (NEW - edit form)
└── item_profile.xml (NEW - RecyclerView item)
```

### Adapters
```
app/src/main/java/uk/co/mrsheep/halive/ui/adapters/
└── ProfileAdapter.kt (NEW - RecyclerView adapter)
```

---

## Files to Modify

### Main Activity Integration
```
app/src/main/java/uk/co/mrsheep/halive/ui/
├── MainActivity.kt (MODIFY - add profile dropdown, wire to GeminiService)
├── MainViewModel.kt (MODIFY - load profile, use profile prompt)
└── activity_main.xml (MODIFY - add profile spinner)
```

### Settings Integration
```
app/src/main/java/uk/co/mrsheep/halive/ui/
└── SettingsActivity.kt (MODIFY - wire "Manage Profiles" button)
```

### String Resources
```
app/src/main/res/values/
└── strings.xml (UPDATE - add profile UI strings)
```

### Manifest
```
app/src/main/AndroidManifest.xml (UPDATE - register new activities)
```

---

## Implementation Details

### 1. ProfileManagementActivity

**Purpose**: List all profiles, allow selection, edit, delete, duplicate

**Key Features**:
- RecyclerView with profile list
- Each item shows: name, preview of system prompt, default badge
- Action buttons: Edit, Duplicate, Delete
- FAB to create new profile
- Click item to set as default
- Delete confirmation dialog
- Handle "last profile" deletion protection

**ViewModel State**:
```kotlin
sealed class ProfileManagementState {
    object Loading : ProfileManagementState()
    data class Loaded(val profiles: List<Profile>) : ProfileManagementState()
    data class Error(val message: String) : ProfileManagementState()
}
```

---

### 2. ProfileEditorActivity

**Purpose**: Create new profile or edit existing

**Key Features**:
- EditText for profile name
- MultiLine EditText for system prompt (200dp height)
- "Save" and "Cancel" buttons
- Validation: name cannot be blank
- Handle duplicate name errors
- Pass profile ID via intent for edit mode
- Support "duplicate" action (pre-fill from existing profile)

**ViewModel State**:
```kotlin
sealed class ProfileEditorState {
    object Idle : ProfileEditorState()
    object Saving : ProfileEditorState()
    object SaveSuccess : ProfileEditorState()
    data class SaveError(val message: String) : ProfileEditorState()
    data class Loaded(val profile: Profile) : ProfileEditorState()
}
```

---

### 3. MainActivity Changes

**Add Profile Dropdown (Spinner)**:
```xml
<Spinner
    android:id="@+id/profileSpinner"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent" />
```

**Functionality**:
- Populate spinner from `ProfileManager.profiles` StateFlow
- Select last-used profile on launch
- On selection change:
  - If chat active: show "Stop chat to switch profiles" toast
  - If chat inactive: mark as last used, reinitialize Gemini with new prompt
- Disable spinner when chat is active

**Remove**:
- SystemPrompt container (already removed in Phase 1, but double-check)
- Any remaining config UI

---

### 4. MainViewModel Changes

**Load Profile on Init**:
```kotlin
init {
    // Load the active profile
    val activeProfile = ProfileManager.getLastUsedOrDefaultProfile()
    if (activeProfile != null) {
        currentProfileId = activeProfile.id
        _systemPrompt.value = activeProfile.systemPrompt
    }

    checkConfiguration()
}
```

**On Profile Switch**:
```kotlin
fun switchProfile(profileId: String) {
    if (isSessionActive) {
        // Cannot switch during active chat
        return
    }

    val profile = ProfileManager.getProfileById(profileId) ?: return
    currentProfileId = profileId
    _systemPrompt.value = profile.systemPrompt
    ProfileManager.markProfileAsUsed(profileId)

    // Reinitialize Gemini with new prompt
    viewModelScope.launch {
        initializeGemini()
    }
}
```

---

### 5. ProfileAdapter (RecyclerView)

**Item Layout** (`item_profile.xml`):
```xml
<LinearLayout>
    <TextView id="profileName" - Bold, 16sp />
    <TextView id="profilePromptPreview" - Gray, 14sp, maxLines=2 />
    <TextView id="defaultBadge" - "DEFAULT" badge, only if isDefault />
    <LinearLayout horizontal buttons>
        <Button id="editButton" text="Edit" />
        <Button id="duplicateButton" text="Duplicate" />
        <Button id="deleteButton" text="Delete" />
    </LinearLayout>
</LinearLayout>
```

**Adapter**:
```kotlin
class ProfileAdapter(
    private val onItemClick: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDuplicate: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>()
```

---

### 6. Settings Integration

**Update SettingsActivity**:
- Wire up the "Manage Profiles" button (currently disabled)
- Launch ProfileManagementActivity

```kotlin
manageProfilesButton.isEnabled = true
manageProfilesButton.setOnClickListener {
    if (viewModel.isChatActive) {
        // Show toast: "Stop chat to manage profiles"
        return@setOnClickListener
    }
    val intent = Intent(this, ProfileManagementActivity::class.java)
    startActivity(intent)
}
```

---

### 7. String Resources

Add to `strings.xml`:
```xml
<!-- Profile Management -->
<string name="profile_management_title">Manage Profiles</string>
<string name="profile_create_new">Create New Profile</string>
<string name="profile_edit">Edit Profile</string>
<string name="profile_duplicate">Duplicate Profile</string>
<string name="profile_delete">Delete</string>
<string name="profile_delete_confirm">Delete this profile?</string>
<string name="profile_delete_last_error">Cannot delete the last profile. Create another profile first.</string>
<string name="profile_default_badge">DEFAULT</string>
<string name="profile_switch_during_chat_error">Stop chat to switch profiles</string>

<!-- Profile Editor -->
<string name="profile_editor_title_create">Create Profile</string>
<string name="profile_editor_title_edit">Edit Profile</string>
<string name="profile_editor_name_hint">Profile name</string>
<string name="profile_editor_prompt_hint">System prompt…</string>
<string name="profile_editor_save">Save</string>
<string name="profile_editor_cancel">Cancel</string>
<string name="profile_editor_duplicate_name_error">A profile with this name already exists</string>
<string name="profile_editor_blank_name_error">Profile name cannot be blank</string>
```

---

## Task Groups for Parallel Execution

### Group 1: Profile Management (Parallel)
- Create `ProfileManagementActivity.kt`
- Create `ProfileManagementViewModel.kt`
- Create `activity_profile_management.xml`
- Create `item_profile.xml`
- Create `ProfileAdapter.kt`

### Group 2: Profile Editor (Parallel)
- Create `ProfileEditorActivity.kt`
- Create `ProfileEditorViewModel.kt`
- Create `activity_profile_editor.xml`

### Group 3: MainActivity Integration (Sequential after Group 1 & 2)
- Update `activity_main.xml` (add Spinner)
- Update `MainActivity.kt` (wire dropdown, observe profiles)
- Update `MainViewModel.kt` (load profile, switchProfile method)

### Group 4: Settings & Final Integration (Sequential)
- Update `SettingsActivity.kt` (enable Manage Profiles button)
- Add strings to `strings.xml`
- Update `AndroidManifest.xml`

---

## Key Implementation Notes

### Profile Dropdown Behavior
1. **On Launch**: Select last-used profile (or default)
2. **On Selection**:
   - If chat inactive: switch profile, mark as used, reinitialize Gemini
   - If chat active: prevent switch, show toast
3. **Observing Changes**: Use StateFlow to update dropdown when profiles change

### Deletion Behavior
1. Show confirmation dialog
2. Try to delete via ProfileManager
3. If IllegalStateException (last profile): show error dialog
4. If success: update UI, close activity if viewing deleted profile

### Duplicate Profile Flow
1. User clicks "Duplicate" on a profile
2. Launch ProfileEditorActivity with:
   - Intent extra: `EXTRA_DUPLICATE_FROM_ID = profileId`
3. Editor loads original profile
4. Pre-fills name as "Copy of {original name}"
5. User can edit and save as new profile

### Read-Only During Chat
- Profile dropdown: disabled
- Manage Profiles button in Settings: show toast if clicked
- Already handles read-only in SettingsActivity via overlay

---

## Validation Checklist

Before Phase 3 is complete:

- [ ] ProfileManagementActivity displays all profiles
- [ ] Can create new profile via FAB
- [ ] Can edit profile (opens ProfileEditorActivity)
- [ ] Can duplicate profile
- [ ] Can delete profile (with confirmation)
- [ ] Cannot delete last profile (error shown)
- [ ] Clicking profile sets it as default
- [ ] ProfileEditorActivity creates new profiles
- [ ] ProfileEditorActivity updates existing profiles
- [ ] Duplicate name validation works
- [ ] Blank name validation works
- [ ] MainActivity shows profile dropdown
- [ ] Dropdown populated from ProfileManager
- [ ] Last-used profile selected on launch
- [ ] Switching profile reinitializes Gemini with new prompt
- [ ] Cannot switch profile during active chat
- [ ] Settings "Manage Profiles" button works
- [ ] All strings added to strings.xml
- [ ] Both new activities registered in manifest
- [ ] No references to SystemPromptConfig in MainActivity

---

## Success Criteria

✅ **Full Profile UI**:
- Users can see all profiles
- Users can create, edit, duplicate, delete profiles
- Users can switch profiles from main screen

✅ **Profile Integration**:
- Active profile's system prompt used for Gemini
- Profile selection persists across app restarts
- Switching profiles reinitializes AI with new personality

✅ **Safety & UX**:
- Cannot delete last profile
- Cannot switch during active chat
- Confirmation dialogs for destructive actions
- Clear error messages

✅ **SystemPromptConfig Deprecated**:
- No UI references to SystemPromptConfig in MainActivity
- All customization through profiles

---

## Risk Mitigation

### Spinner State Management
- Use `onItemSelectedListener` but filter initial call to avoid unwanted reinitialization
- Track whether user triggered selection vs programmatic selection

### Gemini Reinitialization
- Switching profiles calls `initializeGemini()` which refetches tools
- This is acceptable (fast operation) and ensures consistency

### Activity Lifecycle
- ProfileManagementActivity observes StateFlow, so creating/editing profiles auto-updates list
- Use `lifecycleScope.launch` for proper lifecycle awareness

---

## Dependencies for Phase 4

Phase 3 provides complete profile management UI. Phase 4 will focus on:
- Error handling polish
- Confirmation dialogs consistency
- Import/export profiles
- Profile presets
- UI polish and animations

---

## Estimated Implementation

**Files to create**: 8 (5 Kotlin + 3 XML)
**Files to modify**: 5
**Lines of code**: ~800-1000

**Parallel work possible**:
- Group 1 and Group 2 can run simultaneously
- Group 3 and 4 are sequential

Ready to implement! 🚀
