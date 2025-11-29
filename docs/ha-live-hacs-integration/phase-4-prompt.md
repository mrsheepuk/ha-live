# Phase 4: Shared Profile Sync

## Goal

Enable profiles to be synced between the Android app and Home Assistant, allowing household members to share conversation profiles. Local-only profiles are also supported.

## Prerequisites

- Phase 2 (HACS integration) deployed
- Phase 3 (integration detection + shared config fetch) complete

## Context

Currently, profiles are stored locally on each device via `ProfileManager.kt`. After this phase:
1. Profiles can be either LOCAL (device only) or SHARED (synced with HA)
2. Shared profiles are fetched from HA and displayed alongside local profiles
3. Editing a shared profile pushes changes to HA
4. Local profiles can be "uploaded" to become shared
5. Name conflicts are handled when uploading

## Current State

Key files:
- `core/Profile.kt` - Profile data class
- `core/ProfileManager.kt` - Local profile CRUD
- `ui/ProfileManagementActivity.kt` - Profile list UI
- `ui/ProfileEditorActivity.kt` - Profile edit UI
- `ui/adapters/ProfileAdapter.kt` - RecyclerView adapter
- `services/SharedConfigRepository.kt` - From Phase 3

## Implementation

### 1. Update Profile Data Class

Modify `core/Profile.kt`:

```kotlin
package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable

/**
 * Where a profile is stored.
 */
enum class ProfileSource {
    LOCAL,   // Stored only on this device
    SHARED   // Synced with Home Assistant
}

@Serializable
data class Profile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String = "",
    val personality: String = "",
    val backgroundInfo: String = "",
    val model: String = "gemini-2.0-flash-exp",
    val voice: String = "Aoede",
    val toolFilterMode: ToolFilterMode = ToolFilterMode.ALL,
    val selectedTools: List<String> = emptyList(),
    val includeLiveContext: Boolean = true,
    val enableTranscription: Boolean = false,
    val autoStartChat: Boolean = false,
    val initialMessage: String = "",

    // Shared config metadata
    val source: ProfileSource = ProfileSource.LOCAL,
    val lastModified: String? = null,
    val modifiedBy: String? = null,
    val schemaVersion: Int = 1
) {
    /**
     * Convert to format expected by HA integration.
     */
    fun toSharedFormat(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "system_prompt" to systemPrompt,
        "personality" to personality,
        "background_info" to backgroundInfo,
        "model" to model,
        "voice" to voice,
        "tool_filter_mode" to toolFilterMode.name,
        "selected_tools" to selectedTools,
        "include_live_context" to includeLiveContext,
        "enable_transcription" to enableTranscription,
        "auto_start_chat" to autoStartChat,
        "initial_message" to initialMessage
    )

    companion object {
        /**
         * Create Profile from shared config format.
         */
        fun fromShared(shared: uk.co.mrsheep.halive.services.SharedProfile): Profile {
            return Profile(
                id = shared.id,
                name = shared.name,
                systemPrompt = shared.systemPrompt,
                personality = shared.personality,
                backgroundInfo = shared.backgroundInfo,
                model = shared.model,
                voice = shared.voice,
                toolFilterMode = try {
                    ToolFilterMode.valueOf(shared.toolFilterMode)
                } catch (e: Exception) {
                    ToolFilterMode.ALL
                },
                selectedTools = shared.selectedTools,
                includeLiveContext = shared.includeLiveContext,
                enableTranscription = shared.enableTranscription,
                autoStartChat = shared.autoStartChat,
                initialMessage = shared.initialMessage,
                source = ProfileSource.SHARED,
                lastModified = shared.lastModified,
                modifiedBy = shared.modifiedBy,
                schemaVersion = shared.schemaVersion
            )
        }
    }
}
```

### 2. Add Methods to SharedConfigRepository

Add to `services/SharedConfigRepository.kt`:

```kotlin
/**
 * Create or update a shared profile.
 * @return The profile ID if successful, null otherwise.
 */
suspend fun upsertProfile(profile: Profile): String? {
    return try {
        val response = haClient.callService(
            domain = DOMAIN,
            service = "upsert_profile",
            data = mapOf("profile" to profile.toSharedFormat()),
            returnResponse = true
        )
        response?.get("id")?.jsonPrimitive?.content
    } catch (e: Exception) {
        Log.e(TAG, "Failed to upsert profile", e)
        null
    }
}

/**
 * Delete a shared profile.
 */
suspend fun deleteProfile(profileId: String): Boolean {
    return try {
        haClient.callService(
            domain = DOMAIN,
            service = "delete_profile",
            data = mapOf("profile_id" to profileId)
        )
        Log.i(TAG, "Deleted profile: $profileId")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete profile", e)
        false
    }
}
```

### 3. Update ProfileManager

Major updates to `core/ProfileManager.kt`:

```kotlin
package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.mrsheep.halive.services.SharedConfigRepository

class ProfileNameConflictException(message: String) : Exception(message)

class ProfileManager(private val context: Context) {
    companion object {
        private const val TAG = "ProfileManager"
        private const val PREFS_NAME = "profiles"
        private const val KEY_PROFILES = "profile_list"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }

    private var sharedConfigRepo: SharedConfigRepository? = null
    private var cachedSharedProfiles: List<Profile> = emptyList()
    private val mutex = Mutex()

    /**
     * Set the shared config repository for syncing.
     */
    fun setSharedConfigRepository(repo: SharedConfigRepository?) {
        sharedConfigRepo = repo
    }

    /**
     * Refresh shared profiles from Home Assistant.
     */
    suspend fun refreshSharedProfiles(): List<Profile> {
        val repo = sharedConfigRepo ?: return emptyList()

        return try {
            val config = repo.getSharedConfig()
            cachedSharedProfiles = config?.profiles?.map { Profile.fromShared(it) } ?: emptyList()
            Log.d(TAG, "Refreshed ${cachedSharedProfiles.size} shared profiles")
            cachedSharedProfiles
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh shared profiles", e)
            cachedSharedProfiles // Return cached on error
        }
    }

    /**
     * Get all profiles (shared + local).
     * Shared profiles appear first.
     */
    suspend fun getAllProfiles(): List<Profile> = mutex.withLock {
        val localProfiles = getLocalProfiles()
        return cachedSharedProfiles + localProfiles
    }

    /**
     * Get only local profiles.
     */
    fun getLocalProfiles(): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<Profile>>(json)
                .map { it.copy(source = ProfileSource.LOCAL) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local profiles", e)
            emptyList()
        }
    }

    /**
     * Get only shared profiles (from cache).
     */
    fun getSharedProfiles(): List<Profile> = cachedSharedProfiles

    /**
     * Save a profile. Routes to local or shared storage based on source.
     */
    suspend fun saveProfile(profile: Profile): Boolean = mutex.withLock {
        return when (profile.source) {
            ProfileSource.SHARED -> saveSharedProfile(profile)
            ProfileSource.LOCAL -> {
                saveLocalProfile(profile)
                true
            }
        }
    }

    private suspend fun saveSharedProfile(profile: Profile): Boolean {
        val repo = sharedConfigRepo
            ?: throw IllegalStateException("Shared config repository not available")

        val result = repo.upsertProfile(profile)
        if (result != null) {
            // Update cache
            cachedSharedProfiles = cachedSharedProfiles.map {
                if (it.id == profile.id) profile else it
            }
            return true
        }
        return false
    }

    private fun saveLocalProfile(profile: Profile) {
        val profiles = getLocalProfiles().toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == profile.id }

        if (existingIndex >= 0) {
            profiles[existingIndex] = profile.copy(source = ProfileSource.LOCAL)
        } else {
            profiles.add(profile.copy(source = ProfileSource.LOCAL))
        }

        persistLocalProfiles(profiles)
    }

    /**
     * Delete a profile.
     */
    suspend fun deleteProfile(profile: Profile): Boolean = mutex.withLock {
        return when (profile.source) {
            ProfileSource.SHARED -> deleteSharedProfile(profile.id)
            ProfileSource.LOCAL -> {
                deleteLocalProfile(profile.id)
                true
            }
        }
    }

    private suspend fun deleteSharedProfile(profileId: String): Boolean {
        val repo = sharedConfigRepo ?: return false
        val success = repo.deleteProfile(profileId)
        if (success) {
            cachedSharedProfiles = cachedSharedProfiles.filter { it.id != profileId }
        }
        return success
    }

    private fun deleteLocalProfile(profileId: String) {
        val profiles = getLocalProfiles().filter { it.id != profileId }
        persistLocalProfiles(profiles)
    }

    /**
     * Upload a local profile to shared storage.
     * @param deleteLocal If true, deletes the local copy after successful upload.
     * @throws ProfileNameConflictException if name is already taken.
     */
    suspend fun uploadToShared(profile: Profile, deleteLocal: Boolean = true): Profile {
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
            deleteLocalProfile(profile.id)
        }

        Log.i(TAG, "Uploaded profile '${profile.name}' to shared storage")
        return uploadedProfile
    }

    /**
     * Create a new shared profile directly.
     * @throws ProfileNameConflictException if name is already taken.
     */
    suspend fun createSharedProfile(profile: Profile): Profile {
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

        return createdProfile
    }

    /**
     * Download a shared profile to local storage.
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

        saveLocalProfile(localProfile)
        return localProfile
    }

    /**
     * Get profile by ID (searches both local and shared).
     */
    suspend fun getProfile(id: String): Profile? {
        return cachedSharedProfiles.find { it.id == id }
            ?: getLocalProfiles().find { it.id == id }
    }

    // Active profile management (unchanged)
    fun getActiveProfileId(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE, null)
    }

    fun setActiveProfileId(id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PROFILE, id)
            .apply()
    }

    suspend fun getActiveProfile(): Profile? {
        val id = getActiveProfileId() ?: return null
        return getProfile(id)
    }

    private fun persistLocalProfiles(profiles: List<Profile>) {
        val json = kotlinx.serialization.json.Json.encodeToString(profiles)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, json)
            .apply()
    }
}
```

### 4. Update ProfileManagementActivity

Modify `ui/ProfileManagementActivity.kt`:

```kotlin
class ProfileManagementActivity : AppCompatActivity() {
    private lateinit var profileManager: ProfileManager
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_management)

        val app = application as HAGeminiApp
        profileManager = ProfileManager(this)
        profileManager.setSharedConfigRepository(app.sharedConfigRepo)

        setupRecyclerView()
        setupAddButton()

        loadProfiles()
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            showLoading(true)

            // Refresh shared profiles from HA
            profileManager.refreshSharedProfiles()

            // Get all profiles
            val profiles = profileManager.getAllProfiles()
            adapter.submitList(profiles)

            showLoading(false)
        }
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(
            onProfileClick = { profile -> openProfile(profile) },
            onProfileLongClick = { profile -> showProfileContextMenu(profile) }
        )
        recyclerView.adapter = adapter
    }

    private fun showProfileContextMenu(profile: Profile) {
        val options = mutableListOf<String>()

        options.add("Edit")
        options.add("Duplicate")

        if (profile.source == ProfileSource.LOCAL) {
            if ((application as HAGeminiApp).isSharedConfigAvailable()) {
                options.add("Upload to Shared...")
            }
        } else {
            options.add("Save as Local Copy")
        }

        options.add("Delete")

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Edit" -> openProfile(profile)
                    "Duplicate" -> duplicateProfile(profile)
                    "Upload to Shared..." -> showUploadDialog(profile)
                    "Save as Local Copy" -> downloadToLocal(profile)
                    "Delete" -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun showUploadDialog(profile: Profile) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_upload_profile, null)
        val deleteLocalCheckbox = dialogView.findViewById<CheckBox>(R.id.deleteLocalCheckbox)
        deleteLocalCheckbox.isChecked = true

        AlertDialog.Builder(this)
            .setTitle("Upload to Shared Storage")
            .setMessage("This will share '${profile.name}' with everyone in your household.")
            .setView(dialogView)
            .setPositiveButton("Upload") { _, _ ->
                uploadProfile(profile, deleteLocalCheckbox.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadProfile(profile: Profile, deleteLocal: Boolean) {
        lifecycleScope.launch {
            showLoading(true)
            try {
                profileManager.uploadToShared(profile, deleteLocal)
                Toast.makeText(
                    this@ProfileManagementActivity,
                    "Profile uploaded successfully",
                    Toast.LENGTH_SHORT
                ).show()
                loadProfiles()
            } catch (e: ProfileNameConflictException) {
                showNameConflictDialog(profile, e.message ?: "Name already exists")
            } catch (e: Exception) {
                Toast.makeText(
                    this@ProfileManagementActivity,
                    "Upload failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showNameConflictDialog(profile: Profile, message: String) {
        val input = EditText(this).apply {
            setText(profile.name)
            hint = "Enter a different name"
        }

        AlertDialog.Builder(this)
            .setTitle("Name Already Taken")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Upload") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != profile.name) {
                    uploadProfile(profile.copy(name = newName), true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadToLocal(profile: Profile) {
        val localProfile = profileManager.downloadToLocal(profile)
        Toast.makeText(this, "Saved as '${localProfile.name}'", Toast.LENGTH_SHORT).show()
        loadProfiles()
    }

    private fun confirmDelete(profile: Profile) {
        val message = if (profile.source == ProfileSource.SHARED) {
            "Delete '${profile.name}' from shared storage? This will remove it for all household members."
        } else {
            "Delete '${profile.name}'?"
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> deleteProfile(profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(profile: Profile) {
        lifecycleScope.launch {
            val success = profileManager.deleteProfile(profile)
            if (success) {
                loadProfiles()
            } else {
                Toast.makeText(
                    this@ProfileManagementActivity,
                    "Failed to delete profile",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupAddButton() {
        addButton.setOnClickListener {
            showAddProfileDialog()
        }
    }

    private fun showAddProfileDialog() {
        val app = application as HAGeminiApp
        val hasSharedConfig = app.isSharedConfigAvailable()

        if (hasSharedConfig) {
            // Offer choice: Local or Shared
            AlertDialog.Builder(this)
                .setTitle("Create Profile")
                .setItems(arrayOf("Local (this device only)", "Shared (with household)")) { _, which ->
                    when (which) {
                        0 -> createNewProfile(ProfileSource.LOCAL)
                        1 -> createNewProfile(ProfileSource.SHARED)
                    }
                }
                .show()
        } else {
            createNewProfile(ProfileSource.LOCAL)
        }
    }

    private fun createNewProfile(source: ProfileSource) {
        val intent = Intent(this, ProfileEditorActivity::class.java).apply {
            putExtra("source", source.name)
        }
        startActivity(intent)
    }
}
```

### 5. Update ProfileAdapter

Modify `ui/adapters/ProfileAdapter.kt` to show source icons:

```kotlin
class ProfileAdapter(
    private val onProfileClick: (Profile) -> Unit,
    private val onProfileLongClick: (Profile) -> Unit
) : ListAdapter<Profile, ProfileAdapter.ViewHolder>(DiffCallback()) {

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)

        holder.nameText.text = profile.name

        // Show source icon
        val icon = if (profile.source == ProfileSource.SHARED) {
            R.drawable.ic_home  // Home icon for shared
        } else {
            R.drawable.ic_phone // Phone icon for local
        }
        holder.sourceIcon.setImageResource(icon)

        // Show "modified by" for shared profiles
        if (profile.source == ProfileSource.SHARED && profile.modifiedBy != null) {
            holder.subtitleText.visibility = View.VISIBLE
            holder.subtitleText.text = formatModifiedBy(profile)
        } else if (profile.source == ProfileSource.LOCAL) {
            holder.subtitleText.visibility = View.VISIBLE
            holder.subtitleText.text = "Local only"
        } else {
            holder.subtitleText.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onProfileClick(profile) }
        holder.itemView.setOnLongClickListener {
            onProfileLongClick(profile)
            true
        }
    }

    private fun formatModifiedBy(profile: Profile): String {
        val by = profile.modifiedBy ?: "Unknown"
        val time = profile.lastModified?.let { formatRelativeTime(it) } ?: ""
        return if (time.isNotEmpty()) "Modified by $by, $time" else "Modified by $by"
    }

    private fun formatRelativeTime(isoTime: String): String {
        // Parse ISO time and return relative string like "2h ago", "yesterday"
        // Implementation omitted for brevity
        return ""
    }
}
```

### 6. Update ProfileEditorActivity

Modify `ui/ProfileEditorActivity.kt` for shared profile support:

```kotlin
class ProfileEditorActivity : AppCompatActivity() {
    private var profile: Profile? = null
    private var targetSource: ProfileSource = ProfileSource.LOCAL
    private lateinit var profileManager: ProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_editor)

        val app = application as HAGeminiApp
        profileManager = ProfileManager(this)
        profileManager.setSharedConfigRepository(app.sharedConfigRepo)

        val profileId = intent.getStringExtra("profile_id")
        val sourceName = intent.getStringExtra("source")

        if (sourceName != null) {
            targetSource = ProfileSource.valueOf(sourceName)
        }

        if (profileId != null) {
            loadProfile(profileId)
        } else {
            // New profile
            profile = null
            setupForNewProfile()
        }

        setupUI()
    }

    private fun setupUI() {
        // Show banner for shared profiles
        if (profile?.source == ProfileSource.SHARED || targetSource == ProfileSource.SHARED) {
            sharedBanner.visibility = View.VISIBLE
            sharedBanner.text = "Changes will sync to all household members"
        } else {
            sharedBanner.visibility = View.GONE
        }

        saveButton.setOnClickListener { saveProfile() }
        saveLocalButton.setOnClickListener { saveAsLocal() }

        // Show "Save as Local Copy" option for shared profiles
        saveLocalButton.visibility = if (profile?.source == ProfileSource.SHARED) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun saveProfile() {
        val updatedProfile = buildProfileFromInputs()

        lifecycleScope.launch {
            showLoading(true)
            try {
                if (targetSource == ProfileSource.SHARED && profile == null) {
                    // New shared profile
                    profileManager.createSharedProfile(updatedProfile)
                } else {
                    profileManager.saveProfile(updatedProfile)
                }
                finish()
            } catch (e: ProfileNameConflictException) {
                showError("A profile with this name already exists")
            } catch (e: Exception) {
                showError("Failed to save: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun saveAsLocal() {
        val currentProfile = profile ?: return
        val localCopy = profileManager.downloadToLocal(currentProfile)
        Toast.makeText(this, "Saved as '${localCopy.name}'", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buildProfileFromInputs(): Profile {
        return Profile(
            id = profile?.id ?: java.util.UUID.randomUUID().toString(),
            name = nameInput.text.toString().trim(),
            systemPrompt = systemPromptInput.text.toString(),
            personality = personalityInput.text.toString(),
            backgroundInfo = backgroundInfoInput.text.toString(),
            model = modelSpinner.selectedItem.toString(),
            voice = voiceSpinner.selectedItem.toString(),
            toolFilterMode = if (allToolsRadio.isChecked) ToolFilterMode.ALL else ToolFilterMode.SELECTED,
            selectedTools = selectedTools,
            includeLiveContext = liveContextSwitch.isChecked,
            enableTranscription = transcriptionSwitch.isChecked,
            autoStartChat = autoStartSwitch.isChecked,
            initialMessage = initialMessageInput.text.toString(),
            source = profile?.source ?: targetSource
        )
    }
}
```

### 7. Layout Updates

Create `res/layout/dialog_upload_profile.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <CheckBox
        android:id="@+id/deleteLocalCheckbox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Delete local copy after upload"
        android:checked="true" />

</LinearLayout>
```

Update profile list item layout to include source icon and subtitle.

## Acceptance Criteria

1. [ ] Profile list shows both shared and local profiles
2. [ ] Shared profiles display home icon, local profiles display phone icon
3. [ ] Shared profiles show "Modified by X, time ago"
4. [ ] Creating new profile offers Local vs Shared choice (if integration installed)
5. [ ] Editing shared profile saves to HA
6. [ ] Editing shared profile shows sync banner
7. [ ] "Upload to Shared" converts local profile to shared
8. [ ] Upload detects and handles name conflicts
9. [ ] "Save as Local Copy" creates local duplicate of shared profile
10. [ ] Deleting shared profile removes from HA
11. [ ] Delete confirmation explains impact on household
12. [ ] Profile list refreshes shared profiles on open

## Testing Checklist

- [ ] Create local profile → saved locally only
- [ ] Create shared profile → appears in HA
- [ ] Edit shared profile → changes sync to HA
- [ ] Upload local to shared → profile moves to HA
- [ ] Upload with name conflict → error shown, can rename
- [ ] Download shared to local → local copy created
- [ ] Delete local profile → only affects this device
- [ ] Delete shared profile → removed from HA
- [ ] Other household member sees profile changes (manual verify)
- [ ] Offline: local profiles work, shared shows cached

## Dependencies

- Phase 2: HACS integration must be installed
- Phase 3: Integration detection and SharedConfigRepository

## What This Enables

- Phase 5: Polish, conflict handling, offline improvements
- Full household profile sharing functionality
