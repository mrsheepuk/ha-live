# Phase 5: Polish & Edge Cases

## Goal

Handle edge cases, improve offline experience, add sync status indicators, and provide migration path for existing users. This phase polishes the shared config feature for production use.

## Prerequisites

- Phases 1-4 complete and tested

## Context

After Phase 4, the core shared config functionality works. This phase addresses:
1. Offline mode - graceful degradation when HA is unreachable
2. Sync status - visual indicators for sync state
3. Conflict detection - handling concurrent edits
4. Migration - helping existing users adopt shared config
5. Pull-to-refresh - manual sync trigger
6. Error handling - user-friendly error messages

## Implementation Areas

### 1. Offline Mode

When Home Assistant is unreachable, the app should:
- Use cached shared profiles (read-only)
- Show offline indicator
- Queue edits for sync when online (optional, can skip for v1)
- Allow local profiles to work normally

**Update SharedConfigCache** (`core/SharedConfigCache.kt`):

```kotlin
class SharedConfigCache(context: Context) {
    // ... existing code ...

    fun isOffline(): Boolean {
        // Consider offline if last successful fetch > 5 minutes ago
        // and we have cached data
        val lastFetch = getLastFetchTime()
        val cacheAge = System.currentTimeMillis() - lastFetch
        return cacheAge > 5 * 60 * 1000 && getConfig() != null
    }

    fun setLastFetchFailed(failed: Boolean) {
        prefs.edit().putBoolean("last_fetch_failed", failed).apply()
    }

    fun didLastFetchFail(): Boolean {
        return prefs.getBoolean("last_fetch_failed", false)
    }
}
```

**Update ProfileManagementActivity**:

```kotlin
private var isOffline = false

private fun loadProfiles() {
    lifecycleScope.launch {
        showLoading(true)

        try {
            profileManager.refreshSharedProfiles()
            isOffline = false
            hideOfflineBanner()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh shared profiles", e)
            isOffline = true
            showOfflineBanner()
        }

        val profiles = profileManager.getAllProfiles()
        adapter.submitList(profiles)
        showLoading(false)
    }
}

private fun showOfflineBanner() {
    offlineBanner.visibility = View.VISIBLE
    offlineBanner.text = "Offline - showing cached profiles"
}

private fun hideOfflineBanner() {
    offlineBanner.visibility = View.GONE
}

// Disable shared profile editing when offline
private fun showProfileContextMenu(profile: Profile) {
    if (profile.source == ProfileSource.SHARED && isOffline) {
        AlertDialog.Builder(this)
            .setTitle("Offline")
            .setMessage("Cannot edit shared profiles while offline. You can save a local copy instead.")
            .setPositiveButton("Save Local Copy") { _, _ -> downloadToLocal(profile) }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }
    // ... existing menu code ...
}
```

### 2. Sync Status Indicators

Show visual feedback for sync operations.

**Create SyncStatus enum**:

```kotlin
enum class SyncStatus {
    SYNCED,      // Up to date with HA
    SYNCING,     // Currently syncing
    PENDING,     // Local changes pending sync
    ERROR,       // Sync failed
    OFFLINE      // HA unreachable
}
```

**Update ProfileAdapter**:

```kotlin
override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val profile = getItem(position)

    // ... existing binding ...

    // Show sync status for shared profiles
    if (profile.source == ProfileSource.SHARED) {
        when (getSyncStatus(profile)) {
            SyncStatus.SYNCING -> {
                holder.syncIcon.visibility = View.VISIBLE
                holder.syncIcon.setImageResource(R.drawable.ic_sync)
                // Optionally animate rotation
            }
            SyncStatus.ERROR -> {
                holder.syncIcon.visibility = View.VISIBLE
                holder.syncIcon.setImageResource(R.drawable.ic_sync_error)
            }
            SyncStatus.OFFLINE -> {
                holder.syncIcon.visibility = View.VISIBLE
                holder.syncIcon.setImageResource(R.drawable.ic_cloud_off)
            }
            else -> {
                holder.syncIcon.visibility = View.GONE
            }
        }
    } else {
        holder.syncIcon.visibility = View.GONE
    }
}
```

**Add sync progress in ProfileEditorActivity**:

```kotlin
private fun saveProfile() {
    val updatedProfile = buildProfileFromInputs()

    lifecycleScope.launch {
        if (updatedProfile.source == ProfileSource.SHARED) {
            showSyncingState() // "Saving to Home Assistant..."
        }

        try {
            profileManager.saveProfile(updatedProfile)

            if (updatedProfile.source == ProfileSource.SHARED) {
                showSyncedState() // Brief checkmark animation
                delay(500)
            }

            finish()
        } catch (e: Exception) {
            showSyncErrorState(e.message)
        }
    }
}

private fun showSyncingState() {
    saveButton.isEnabled = false
    syncProgressBar.visibility = View.VISIBLE
    syncStatusText.text = "Saving to Home Assistant..."
}

private fun showSyncedState() {
    syncProgressBar.visibility = View.GONE
    syncStatusText.text = "Saved!"
    // Show checkmark icon
}

private fun showSyncErrorState(message: String?) {
    saveButton.isEnabled = true
    syncProgressBar.visibility = View.GONE
    syncStatusText.text = "Sync failed: ${message ?: "Unknown error"}"
    // Show error icon with retry option
}
```

### 3. Conflict Detection

Handle case where two users edit the same profile simultaneously.

**Simple approach: Last-write-wins with warning**

```kotlin
// In ProfileEditorActivity
private var originalLastModified: String? = null

private fun loadProfile(profileId: String) {
    lifecycleScope.launch {
        profile = profileManager.getProfile(profileId)
        originalLastModified = profile?.lastModified
        populateFields()
    }
}

private fun saveProfile() {
    lifecycleScope.launch {
        // Check if profile was modified since we loaded it
        if (profile?.source == ProfileSource.SHARED) {
            val currentProfile = profileManager.getProfile(profile!!.id)
            if (currentProfile?.lastModified != originalLastModified) {
                showConflictDialog(currentProfile)
                return@launch
            }
        }

        // Proceed with save
        doSave()
    }
}

private fun showConflictDialog(serverProfile: Profile?) {
    val modifiedBy = serverProfile?.modifiedBy ?: "someone"

    AlertDialog.Builder(this)
        .setTitle("Profile Modified")
        .setMessage("This profile was modified by $modifiedBy while you were editing. What would you like to do?")
        .setPositiveButton("Overwrite with my changes") { _, _ ->
            doSave()
        }
        .setNegativeButton("Discard my changes") { _, _ ->
            finish()
        }
        .setNeutralButton("Save as new profile") { _, _ ->
            saveAsNewProfile()
        }
        .show()
}

private fun saveAsNewProfile() {
    val newProfile = buildProfileFromInputs().copy(
        id = java.util.UUID.randomUUID().toString(),
        name = "${profile?.name} (My Version)"
    )
    lifecycleScope.launch {
        profileManager.saveProfile(newProfile)
        finish()
    }
}
```

### 4. Migration for Existing Users

When existing users get the integration, offer to upload their profiles.

**Add migration prompt in ProfileManagementActivity**:

```kotlin
private fun checkForMigrationOpportunity() {
    val app = application as HAGeminiApp
    val prefs = getSharedPreferences("migration", MODE_PRIVATE)
    val migrationPromptShown = prefs.getBoolean("upload_prompt_shown", false)

    if (!migrationPromptShown && app.isSharedConfigAvailable()) {
        val localProfiles = profileManager.getLocalProfiles()
        val sharedProfiles = profileManager.getSharedProfiles()

        // Show prompt if user has local profiles but no shared ones
        if (localProfiles.isNotEmpty() && sharedProfiles.isEmpty()) {
            showMigrationDialog(localProfiles)
        }

        prefs.edit().putBoolean("upload_prompt_shown", true).apply()
    }
}

private fun showMigrationDialog(localProfiles: List<Profile>) {
    AlertDialog.Builder(this)
        .setTitle("Share Your Profiles?")
        .setMessage(
            "You have ${localProfiles.size} local profile(s). Would you like to " +
            "upload them to Home Assistant so other household members can use them?"
        )
        .setPositiveButton("Upload All") { _, _ ->
            uploadAllProfiles(localProfiles)
        }
        .setNegativeButton("Choose Profiles") { _, _ ->
            showProfileSelectionDialog(localProfiles)
        }
        .setNeutralButton("Not Now", null)
        .show()
}

private fun showProfileSelectionDialog(profiles: List<Profile>) {
    val names = profiles.map { it.name }.toTypedArray()
    val selected = BooleanArray(profiles.size) { true }

    AlertDialog.Builder(this)
        .setTitle("Select Profiles to Upload")
        .setMultiChoiceItems(names, selected) { _, which, isChecked ->
            selected[which] = isChecked
        }
        .setPositiveButton("Upload") { _, _ ->
            val toUpload = profiles.filterIndexed { index, _ -> selected[index] }
            uploadAllProfiles(toUpload)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun uploadAllProfiles(profiles: List<Profile>) {
    lifecycleScope.launch {
        var successCount = 0
        var failCount = 0

        for (profile in profiles) {
            try {
                profileManager.uploadToShared(profile, deleteLocal = true)
                successCount++
            } catch (e: ProfileNameConflictException) {
                // Skip profiles with conflicting names
                failCount++
            } catch (e: Exception) {
                failCount++
            }
        }

        val message = when {
            failCount == 0 -> "Uploaded $successCount profile(s) successfully"
            successCount == 0 -> "Upload failed for all profiles"
            else -> "Uploaded $successCount, failed $failCount (name conflicts)"
        }

        Toast.makeText(this@ProfileManagementActivity, message, Toast.LENGTH_LONG).show()
        loadProfiles()
    }
}
```

### 5. Pull-to-Refresh

Add swipe-to-refresh for manual sync.

**Update activity_profile_management.xml**:

```xml
<androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    android:id="@+id/swipeRefresh"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
```

**Update ProfileManagementActivity**:

```kotlin
private fun setupSwipeRefresh() {
    swipeRefresh.setOnRefreshListener {
        loadProfiles()
    }
}

private fun loadProfiles() {
    lifecycleScope.launch {
        // Don't show loading spinner if swipe refresh is active
        if (!swipeRefresh.isRefreshing) {
            showLoading(true)
        }

        try {
            profileManager.refreshSharedProfiles()
            isOffline = false
            hideOfflineBanner()
        } catch (e: Exception) {
            isOffline = true
            showOfflineBanner()
        }

        val profiles = profileManager.getAllProfiles()
        adapter.submitList(profiles)

        showLoading(false)
        swipeRefresh.isRefreshing = false
    }
}
```

### 6. Improved Error Messages

Create user-friendly error messages for common scenarios.

**Create ErrorMessages helper**:

```kotlin
object ErrorMessages {
    fun forSyncError(e: Exception): String {
        return when {
            e is java.net.UnknownHostException ->
                "Cannot reach Home Assistant. Check your connection."
            e is java.net.SocketTimeoutException ->
                "Connection timed out. Home Assistant may be slow or unreachable."
            e.message?.contains("401") == true ->
                "Authentication failed. Try logging in again."
            e.message?.contains("403") == true ->
                "Access denied. Check your Home Assistant permissions."
            e.message?.contains("404") == true ->
                "HA Live Config integration not found. Is it installed?"
            e is ProfileNameConflictException ->
                e.message ?: "A profile with this name already exists."
            else ->
                "Sync failed: ${e.message ?: "Unknown error"}"
        }
    }

    fun forDeleteError(e: Exception, isShared: Boolean): String {
        val target = if (isShared) "shared profile" else "profile"
        return when {
            e is java.net.UnknownHostException ->
                "Cannot delete $target while offline."
            else ->
                "Failed to delete $target: ${e.message}"
        }
    }
}
```

### 7. Settings: Clear Cache Option

Add option to clear cached shared config in settings.

**Update SettingsActivity**:

```kotlin
private fun setupCacheSection() {
    val app = application as HAGeminiApp
    val cache = app.sharedConfigCache

    if (cache != null && cache.isIntegrationInstalled()) {
        cacheSection.visibility = View.VISIBLE

        val lastFetch = cache.getLastFetchTime()
        if (lastFetch > 0) {
            lastSyncText.text = "Last synced: ${formatTime(lastFetch)}"
        } else {
            lastSyncText.text = "Never synced"
        }

        clearCacheButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Cache")
                .setMessage("This will clear cached shared profiles. They will be re-fetched from Home Assistant on next launch.")
                .setPositiveButton("Clear") { _, _ ->
                    cache.clear()
                    Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                    lastSyncText.text = "Never synced"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        forceSyncButton.setOnClickListener {
            forceSync()
        }
    } else {
        cacheSection.visibility = View.GONE
    }
}

private fun forceSync() {
    lifecycleScope.launch {
        forceSyncButton.isEnabled = false
        syncProgressBar.visibility = View.VISIBLE

        try {
            val app = application as HAGeminiApp
            app.fetchSharedConfig()
            Toast.makeText(this@SettingsActivity, "Sync complete", Toast.LENGTH_SHORT).show()
            setupCacheSection() // Refresh display
        } catch (e: Exception) {
            Toast.makeText(this@SettingsActivity, ErrorMessages.forSyncError(e), Toast.LENGTH_LONG).show()
        }

        forceSyncButton.isEnabled = true
        syncProgressBar.visibility = View.GONE
    }
}
```

### 8. Last Modified Display

Show human-readable "modified" times.

**Create TimeFormatter utility**:

```kotlin
object TimeFormatter {
    fun formatRelative(isoTime: String?): String {
        if (isoTime == null) return ""

        return try {
            val instant = java.time.Instant.parse(isoTime)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(instant, now)

            when {
                duration.toMinutes() < 1 -> "just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() < 7 -> "${duration.toDays()}d ago"
                else -> {
                    val formatter = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d")
                        .withZone(java.time.ZoneId.systemDefault())
                    formatter.format(instant)
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
```

## New Layouts Required

### res/layout/banner_offline.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="#FFF3E0"
    android:padding="12dp"
    android:gravity="center_vertical"
    android:orientation="horizontal">

    <ImageView
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:src="@drawable/ic_cloud_off"
        android:tint="#E65100" />

    <TextView
        android:id="@+id/offlineText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="8dp"
        android:text="Offline - showing cached profiles"
        android:textColor="#E65100" />

</LinearLayout>
```

### Icons needed

- `ic_sync` - Sync arrows
- `ic_sync_error` - Sync with error indicator
- `ic_cloud_off` - Cloud with slash (offline)
- `ic_home` - Home icon (shared profiles)
- `ic_phone` - Phone icon (local profiles)

## Acceptance Criteria

1. [ ] Offline: App shows cached profiles when HA unreachable
2. [ ] Offline: Banner indicates offline state
3. [ ] Offline: Shared profile editing blocked with helpful message
4. [ ] Sync status: Progress indicator while saving shared profiles
5. [ ] Sync status: Error state shown on failure with retry option
6. [ ] Conflict: Warning shown if profile edited by another user
7. [ ] Conflict: Options to overwrite, discard, or save as new
8. [ ] Migration: Prompt to upload local profiles when integration first detected
9. [ ] Migration: Bulk upload with progress
10. [ ] Pull-to-refresh: Manual sync trigger works
11. [ ] Settings: Clear cache option available
12. [ ] Settings: Force sync button works
13. [ ] Modified times: Show relative times (e.g., "2h ago")
14. [ ] Error messages: User-friendly for all error scenarios

## Testing Checklist

- [ ] Turn off network → app shows offline banner
- [ ] Offline → can view but not edit shared profiles
- [ ] Offline → local profiles work normally
- [ ] Restore network → pull-to-refresh syncs
- [ ] Save shared profile → progress shown
- [ ] Save fails → error shown with retry
- [ ] Two devices edit same profile → conflict detected
- [ ] Existing user installs integration → migration prompt shown
- [ ] Bulk upload → progress and results shown
- [ ] Settings → clear cache works
- [ ] Settings → force sync works
- [ ] Relative times display correctly

## Dependencies

- Phases 1-4 complete

## Completion

After this phase, the shared config feature is production-ready:
- Robust offline handling
- Clear sync status feedback
- Graceful conflict handling
- Smooth migration path
- User-friendly error messages
