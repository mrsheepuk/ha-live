# Phase 2 Implementation Plan: Profile System Backend

**Parent Document**: [task-settings-and-profiles.md](./task-settings-and-profiles.md)

## Overview

Phase 2 implements the backend infrastructure for the multi-profile system:
- Profile data model and storage
- ProfileManager singleton for CRUD operations
- Migration logic for existing system prompts
- Unit tests for ProfileManager
- Integration with Settings screen

This phase is **backend-only** - no UI changes to MainActivity. The profile dropdown will be added in Phase 3.

---

## Prerequisites

✅ Phase 1 must be complete:
- OnboardingActivity exists
- SettingsActivity exists with read-only mode support
- MainActivity has menu navigation
- SystemPromptConfig is functional

---

## Files to Create

### Core Profile System
```
app/src/main/java/uk/co/mrsheep/halive/core/
├── Profile.kt (NEW - data model)
└── ProfileManager.kt (NEW - storage & CRUD operations)
```

### Unit Tests
```
app/src/test/java/uk/co/mrsheep/halive/core/
└── ProfileManagerTest.kt (NEW - comprehensive unit tests)
```

---

## Files to Modify

### Existing Code
```
app/src/main/java/uk/co/mrsheep/halive/
├── HAGeminiApp.kt (MODIFY - initialize ProfileManager, run migration)
├── ui/OnboardingViewModel.kt (MODIFY - create default profile on completion)
└── ui/SettingsViewModel.kt (MODIFY - load profile count)
```

---

## Detailed Implementation

### 1. Profile Data Model

**File**: `app/src/main/java/uk/co/mrsheep/halive/core/Profile.kt`

```kotlin
package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a profile with a custom system prompt.
 *
 * Each profile defines a unique "personality" for the AI assistant.
 * Profiles are stored in SharedPreferences as JSON.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Creates a default profile with the standard system prompt.
         */
        fun createDefault(): Profile {
            return Profile(
                name = "Default",
                systemPrompt = SystemPromptConfig.DEFAULT_SYSTEM_PROMPT,
                isDefault = true
            )
        }
    }

    /**
     * Updates the lastUsedAt timestamp.
     */
    fun markAsUsed(): Profile {
        return copy(lastUsedAt = System.currentTimeMillis())
    }

    /**
     * Returns a JSON string suitable for clipboard sharing.
     */
    fun toJsonString(): String {
        return """
        {
            "name": "$name",
            "systemPrompt": "${systemPrompt.replace("\"", "\\\"")}"
        }
        """.trimIndent()
    }
}
```

**Design Notes**:
- Uses `kotlinx.serialization` for JSON handling (already in project for MCP)
- Immutable data class following Kotlin best practices
- Helper methods for common operations
- `toJsonString()` will be used in Phase 3 for clipboard export

---

### 2. ProfileManager Singleton

**File**: `app/src/main/java/uk/co/mrsheep/halive/core/ProfileManager.kt`

```kotlin
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
```

**Design Notes**:
- Singleton pattern for easy access throughout app
- StateFlow for reactive updates (UI will observe in Phase 3)
- Comprehensive error handling with clear exception messages
- Migration logic to preserve existing system prompts
- All operations are synchronous (fast enough for local storage)

---

### 3. Unit Tests

**File**: `app/src/test/java/uk/co/mrsheep/halive/core/ProfileManagerTest.kt`

```kotlin
package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

class ProfileManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)

        ProfileManager.initialize(context)
    }

    @Test
    fun testCreateProfile_success() {
        val profile = Profile(name = "Test Profile", systemPrompt = "Test prompt")
        val created = ProfileManager.createProfile(profile)

        assertNotNull(created.id)
        assertEquals("Test Profile", created.name)
        assertEquals("Test prompt", created.systemPrompt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateProfile_blankName_throwsException() {
        ProfileManager.createProfile(Profile(name = "", systemPrompt = "Prompt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateProfile_duplicateName_throwsException() {
        ProfileManager.createProfile(Profile(name = "Duplicate", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Duplicate", systemPrompt = "Prompt 2"))
    }

    @Test
    fun testCreateProfile_firstProfileIsDefault() {
        val profile = ProfileManager.createProfile(Profile(name = "First", systemPrompt = "Prompt"))
        assertTrue(profile.isDefault)
    }

    @Test
    fun testGetAllProfiles_returnsCorrectCount() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        val profiles = ProfileManager.getAllProfiles()
        assertEquals(2, profiles.size)
    }

    @Test
    fun testGetProfileById_existingId_returnsProfile() {
        val created = ProfileManager.createProfile(Profile(name = "Test", systemPrompt = "Prompt"))
        val retrieved = ProfileManager.getProfileById(created.id)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved?.id)
        assertEquals(created.name, retrieved?.name)
    }

    @Test
    fun testGetProfileById_nonExistentId_returnsNull() {
        val retrieved = ProfileManager.getProfileById("nonexistent-id")
        assertNull(retrieved)
    }

    @Test
    fun testGetDefaultProfile_returnsDefaultProfile() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val default = ProfileManager.createProfile(
            Profile(name = "Default", systemPrompt = "Prompt 2", isDefault = true)
        )

        val retrieved = ProfileManager.getDefaultProfile()
        assertNotNull(retrieved)
        assertEquals(default.id, retrieved?.id)
        assertTrue(retrieved!!.isDefault)
    }

    @Test
    fun testUpdateProfile_success() {
        val created = ProfileManager.createProfile(Profile(name = "Original", systemPrompt = "Original Prompt"))
        val updated = created.copy(name = "Updated", systemPrompt = "Updated Prompt")

        ProfileManager.updateProfile(updated)

        val retrieved = ProfileManager.getProfileById(created.id)
        assertEquals("Updated", retrieved?.name)
        assertEquals("Updated Prompt", retrieved?.systemPrompt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUpdateProfile_duplicateName_throwsException() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.updateProfile(profile2.copy(name = "Profile 1"))
    }

    @Test
    fun testSetDefaultProfile_unmarksOthers() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setDefaultProfile(profile2.id)

        val allProfiles = ProfileManager.getAllProfiles()
        assertEquals(false, allProfiles.find { it.id == profile1.id }?.isDefault)
        assertEquals(true, allProfiles.find { it.id == profile2.id }?.isDefault)
    }

    @Test(expected = IllegalStateException::class)
    fun testDeleteProfile_lastProfile_throwsException() {
        val profile = ProfileManager.createProfile(Profile(name = "Only Profile", systemPrompt = "Prompt"))
        ProfileManager.deleteProfile(profile.id)
    }

    @Test
    fun testDeleteProfile_defaultProfile_promotesAnother() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        assertTrue(profile1.isDefault)

        ProfileManager.deleteProfile(profile1.id)

        val remaining = ProfileManager.getAllProfiles()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].isDefault)
    }

    @Test
    fun testDuplicateProfile_success() {
        val original = ProfileManager.createProfile(Profile(name = "Original", systemPrompt = "Original Prompt"))
        val duplicate = ProfileManager.duplicateProfile(original.id, "Duplicate")

        assertNotEquals(original.id, duplicate.id)
        assertEquals("Duplicate", duplicate.name)
        assertEquals("Original Prompt", duplicate.systemPrompt)
        assertFalse(duplicate.isDefault)
    }

    @Test
    fun testEnsureDefaultProfileExists_noProfiles_createsDefault() {
        // ProfileManager starts empty
        ProfileManager.ensureDefaultProfileExists()

        val profiles = ProfileManager.getAllProfiles()
        assertEquals(1, profiles.size)
        assertEquals("Default", profiles[0].name)
        assertTrue(profiles[0].isDefault)
    }

    @Test
    fun testMarkProfileAsUsed_updatesLastUsedId() {
        val profile = ProfileManager.createProfile(Profile(name = "Test", systemPrompt = "Prompt"))

        ProfileManager.markProfileAsUsed(profile.id)

        assertEquals(profile.id, ProfileManager.getLastUsedProfileId())
    }

    @Test
    fun testGetLastUsedOrDefaultProfile_noLastUsed_returnsDefault() {
        val profile = ProfileManager.createProfile(Profile(name = "Default", systemPrompt = "Prompt"))

        val retrieved = ProfileManager.getLastUsedOrDefaultProfile()

        assertNotNull(retrieved)
        assertEquals(profile.id, retrieved?.id)
    }

    @Test
    fun testGetLastUsedOrDefaultProfile_lastUsedDeleted_fallsBackToDefault() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.markProfileAsUsed(profile2.id)
        ProfileManager.deleteProfile(profile2.id)

        val retrieved = ProfileManager.getLastUsedOrDefaultProfile()

        assertNotNull(retrieved)
        assertEquals(profile1.id, retrieved?.id) // Falls back to default
    }
}
```

**Test Coverage**:
- ✅ Profile creation (success, blank name, duplicate name)
- ✅ First profile auto-default behavior
- ✅ Profile retrieval (by ID, all, default)
- ✅ Profile updates (success, duplicate detection)
- ✅ Default profile management
- ✅ Profile deletion (last profile protection, default promotion)
- ✅ Profile duplication
- ✅ Last used tracking and fallback logic
- ✅ Ensure default exists behavior

---

### 4. App Integration - HAGeminiApp

**File**: `app/src/main/java/uk/co/mrsheep/halive/HAGeminiApp.kt`

**Changes**:
1. Initialize ProfileManager in onCreate()
2. Run migration from SystemPromptConfig

```kotlin
class HAGeminiApp : Application() {

    var haRepository: HomeAssistantRepository? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase if configured
        FirebaseConfig.initializeFirebase(this)

        // Initialize ProfileManager (NEW)
        ProfileManager.initialize(this)

        // Run migration from SystemPromptConfig to profiles (NEW)
        ProfileManager.runMigrationIfNeeded(this)

        // Ensure at least one profile exists (NEW)
        ProfileManager.ensureDefaultProfileExists()
    }

    fun initializeHomeAssistant(baseUrl: String, token: String) {
        haRepository = HomeAssistantRepository(this, baseUrl, token)
    }
}
```

**Migration Behavior**:
- **First-time users**: No existing SystemPromptConfig → creates default profile
- **Existing users**: Migrates existing system prompt to "Default" profile
- **Users who reinstall**: Migration flag in profile storage prevents re-running

---

### 5. Onboarding Integration - OnboardingViewModel

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/OnboardingViewModel.kt`

**Changes**:
Update `saveHAConfigAndContinue()` to explicitly create default profile:

```kotlin
fun saveHAConfigAndContinue() {
    viewModelScope.launch {
        // Save the validated config
        HAConfig.saveConfig(getApplication(), currentHAUrl, currentHAToken)

        // Create default profile (NEW)
        ProfileManager.ensureDefaultProfileExists()

        // Move to final step
        _onboardingState.value = OnboardingState.Step3Complete
    }
}
```

**Rationale**: Ensures new users have a profile ready immediately after onboarding.

---

### 6. Settings Integration - SettingsViewModel

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/SettingsViewModel.kt`

**Changes**:
Update `loadSettings()` to get actual profile count:

```kotlin
fun loadSettings() {
    viewModelScope.launch {
        val (haUrl, haToken) = HAConfig.loadConfig(getApplication()) ?: Pair("Not configured", "")
        val projectId = FirebaseConfig.getProjectId(getApplication()) ?: "Not configured"
        val profileCount = ProfileManager.getAllProfiles().size // UPDATED

        _settingsState.value = SettingsState.Loaded(
            haUrl = haUrl,
            haToken = haToken,
            firebaseProjectId = projectId,
            profileCount = profileCount, // Will show correct count
            isReadOnly = isChatActive
        )
    }
}
```

**File**: `app/src/main/java/uk/co/mrsheep/halive/ui/SettingsActivity.kt`

**Changes**:
Update profile summary text to use the actual count:

```kotlin
private fun updateUIForState(state: SettingsState) {
    when (state) {
        is SettingsState.Loaded -> {
            // Update UI with current config
            haUrlText.text = state.haUrl
            haTokenText.text = "••••••••" // Masked token
            firebaseProjectIdText.text = state.firebaseProjectId
            profileSummaryText.text = "${state.profileCount} profile(s) configured" // UPDATED

            // Show/hide read-only overlay
            if (state.isReadOnly) {
                readOnlyOverlay.visibility = View.VISIBLE
                readOnlyMessage.text = "Stop chat to modify settings"
            } else {
                readOnlyOverlay.visibility = View.GONE
            }
        }
        // ... rest unchanged
    }
}
```

---

## Testing Strategy

### Unit Tests
Run the ProfileManagerTest suite:
```bash
./gradlew test --tests ProfileManagerTest
```

### Manual Testing Scenarios

**Scenario 1: Fresh Install**
1. Install app
2. Complete onboarding
3. Check: Default profile should exist
4. Open Settings → should show "1 profile(s) configured"

**Scenario 2: Existing User Migration**
1. User with existing SystemPromptConfig
2. Upgrade app
3. Launch app
4. Check: Default profile created with existing system prompt
5. Check: Original SystemPromptConfig unchanged (for safety)

**Scenario 3: Profile CRUD**
1. Create 3 new profiles via ProfileManager
2. Set profile 2 as default
3. Delete profile 1
4. Check: Profile 2 still default
5. Try to delete last profile
6. Check: Throws IllegalStateException

**Scenario 4: Duplicate Detection**
1. Create profile "House Lizard"
2. Try to create another "House Lizard" (case-insensitive)
3. Check: Throws IllegalArgumentException

**Scenario 5: Last Used Tracking**
1. Create 3 profiles
2. Mark profile 2 as last used
3. Call getLastUsedOrDefaultProfile()
4. Check: Returns profile 2
5. Delete profile 2
6. Call getLastUsedOrDefaultProfile() again
7. Check: Falls back to default

---

## Validation Checklist

Before considering Phase 2 complete:

- [ ] Profile data model compiles with kotlinx.serialization
- [ ] ProfileManager initializes without errors
- [ ] All 20+ unit tests pass
- [ ] Migration creates default profile for new users
- [ ] Migration preserves existing system prompt for existing users
- [ ] Settings screen shows correct profile count
- [ ] Onboarding creates default profile
- [ ] Can create, read, update, delete profiles via ProfileManager
- [ ] Duplicate name detection works (case-insensitive)
- [ ] Cannot delete last profile
- [ ] Default profile auto-promotion works when default is deleted
- [ ] Last used profile tracking works
- [ ] Corrupted JSON gracefully handled (returns empty list)

---

## Phase 2 Success Criteria

✅ **Backend Complete**:
- Profile storage working with SharedPreferences
- All CRUD operations functional and tested
- Migration logic preserves existing user data
- Settings screen displays profile count

✅ **No Breaking Changes**:
- Existing app functionality unchanged
- SystemPromptConfig still works (will be deprecated in Phase 3)
- Users can continue using app normally

✅ **Ready for Phase 3**:
- Profile infrastructure solid and tested
- UI can safely integrate with ProfileManager
- StateFlow ready for reactive UI updates

---

## Dependencies for Phase 3

Phase 2 provides these APIs that Phase 3 will consume:

```kotlin
// Get all profiles for dropdown
val profiles = ProfileManager.getAllProfiles()

// Get the profile to use on app launch
val activeProfile = ProfileManager.getLastUsedOrDefaultProfile()

// Mark profile as used when starting chat
ProfileManager.markProfileAsUsed(profileId)

// Observe profile changes (for live updates)
lifecycleScope.launch {
    ProfileManager.profiles.collect { profiles ->
        updateDropdown(profiles)
    }
}
```

---

## Risk Mitigation

### Data Loss Prevention
- Migration runs once, never deletes SystemPromptConfig
- `resetToDefault()` method available for recovery
- Corrupted JSON returns empty list (triggers default creation)

### Performance
- All operations synchronous (SharedPreferences is fast)
- JSON serialization efficient for small datasets (<100 profiles)
- No observable impact on app startup time

### Testing
- Comprehensive unit tests catch regressions
- Migration tested with both fresh and existing users
- Edge cases covered (last profile, duplicates, etc.)

---

## Next Steps After Phase 2

Once Phase 2 is complete and validated:

1. **Phase 3**: Profile UI & Main Screen Integration
   - Add profile dropdown to MainActivity
   - Create ProfileManagementActivity (list view)
   - Create ProfileEditorActivity (edit/create)
   - Remove SystemPromptConfig from MainActivity
   - Implement profile switching logic

2. **Phase 4**: Polish & Error Handling
   - Comprehensive error dialogs
   - Confirmation prompts
   - UI polish
   - End-to-end testing

---

## Implementation Notes

- Use Haiku subagents for parallel implementation (ProfileManager + tests can be done simultaneously)
- ProfileManager is independent of UI - can be fully tested without UI testing
- Migration is one-way (doesn't delete old data for safety)
- Phase 2 can be committed without affecting existing app functionality

Ready to implement! 🚀
