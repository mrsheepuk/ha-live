package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.HAGeminiApp
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.core.ProfileSource
import uk.co.mrsheep.halive.core.ShortcutHelper
import uk.co.mrsheep.halive.ui.adapters.ProfileAdapter
import uk.co.mrsheep.halive.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for managing profiles.
 *
 * Features:
 * - Display list of all profiles
 * - Click profile to set as active
 * - Edit, duplicate, and delete profiles
 * - Create new profiles via FAB
 * - Delete confirmation dialog
 * - Handles "last profile" deletion error
 */
class ProfileManagementActivity : AppCompatActivity() {

    private val viewModel: ProfileManagementViewModel by viewModels()

    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var profilesRecyclerView: RecyclerView
    private lateinit var fabCreateProfile: FloatingActionButton
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineBanner: LinearLayout
    private lateinit var offlineText: TextView
    private var isOffline = false

    private lateinit var profileAdapter: ProfileAdapter
    private var exportingProfileId: String? = null

    // Activity result launchers for file operations
    private val exportSingleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleExportSingle(uri)
            }
        }
    }

    private val exportAllLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleExportAll(uri)
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleImport(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_management)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Personalities"

        initViews()
        setupRecyclerView()
        observeState()
    }

    private fun initViews() {
        profilesRecyclerView = findViewById(R.id.profilesRecyclerView)
        fabCreateProfile = findViewById(R.id.fabCreateProfile)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineBanner = findViewById(R.id.offlineBanner)
        offlineText = findViewById(R.id.offlineText)

        setupSwipeRefresh()

        // FAB click listener to create new profile
        fabCreateProfile.setOnClickListener {
            val intent = Intent(this, ProfileEditorActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            viewModel.refreshProfiles()
        }
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onItemClick = { profile ->
                // Click to set as active
                viewModel.setActiveProfile(profile.id)
            },
            onEdit = { profile ->
                if (profile.source == ProfileSource.SHARED && isOffline) {
                    AlertDialog.Builder(this)
                        .setTitle("Offline")
                        .setMessage("Cannot edit shared profiles while offline. You can save a local copy instead.")
                        .setPositiveButton("Save Local Copy") { _, _ ->
                            val localCopy = viewModel.downloadToLocal(profile)
                            Toast.makeText(this, "Saved as '${localCopy.name}'", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val intent = Intent(this, ProfileEditorActivity::class.java)
                    intent.putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile.id)
                    startActivity(intent)
                }
            },
            onAddShortcut = { profile ->
                handleAddShortcut(profile)
            },
            onDuplicate = { profile ->
                val intent = Intent(this, ProfileEditorActivity::class.java)
                intent.putExtra(ProfileEditorActivity.EXTRA_DUPLICATE_FROM_ID, profile.id)
                startActivity(intent)
            },
            onExport = { profile ->
                exportingProfileId = profile.id
                val fileName = getSuggestedExportFileName(profile.name)
                val intent = FileUtils.createExportIntent(fileName)
                exportSingleLauncher.launch(intent)
            },
            onUploadToShared = { profile ->
                if (profile.source == ProfileSource.LOCAL) {
                    showUploadDialog(profile)
                }
            },
            onDownloadToLocal = { profile ->
                if (profile.source == ProfileSource.SHARED) {
                    val localCopy = viewModel.downloadToLocal(profile)
                    Toast.makeText(this, "Saved as '${localCopy.name}'", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { profile ->
                showDeleteConfirmationDialog(profile)
            }
        )

        profilesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ProfileManagementActivity)
            adapter = profileAdapter
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: ProfileManagementState) {
        when (state) {
            is ProfileManagementState.Loading -> {
                loadingIndicator.visibility = View.VISIBLE
                profilesRecyclerView.visibility = View.GONE
                errorText.visibility = View.GONE
            }
            is ProfileManagementState.Loaded -> {
                loadingIndicator.visibility = View.GONE
                profilesRecyclerView.visibility = View.VISIBLE
                errorText.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                isOffline = state.isOffline
                if (isOffline) {
                    offlineBanner.visibility = View.VISIBLE
                } else {
                    offlineBanner.visibility = View.GONE
                }

                // Update the adapter with new profiles and active profile ID
                profileAdapter.submitList(state.profiles, state.activeProfileId)
            }
            is ProfileManagementState.Error -> {
                loadingIndicator.visibility = View.GONE
                profilesRecyclerView.visibility = View.VISIBLE
                errorText.visibility = View.GONE
                swipeRefresh.isRefreshing = false

                // Show error dialog
                showErrorDialog(state.message)

                // If it's a "last profile" error, keep showing the list
                // The error will be shown in a dialog but UI stays functional
            }
        }
    }

    /**
     * Shows a confirmation dialog before deleting a profile.
     */
    private fun showDeleteConfirmationDialog(profile: Profile) {
        val message = if (profile.source == ProfileSource.SHARED) {
            "Delete profile \"${profile.name}\" from shared storage?\n\nThis will remove it for all household members. This action cannot be undone."
        } else {
            "Delete profile \"${profile.name}\"?\n\nThis action cannot be undone."
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteProfile(profile.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows an error dialog.
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Handles creating a pinned shortcut for a profile.
     */
    private fun handleAddShortcut(profile: Profile) {
        if (!ShortcutHelper.isRequestPinShortcutSupported(this)) {
            Toast.makeText(
                this,
                getString(R.string.shortcut_not_supported),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val success = ShortcutHelper.createShortcutForProfile(this, profile)
        if (success) {
            Toast.makeText(
                this,
                getString(R.string.shortcut_created, profile.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.shortcut_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.profile_management_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import_profiles -> {
                val intent = FileUtils.createImportIntent()
                importLauncher.launch(intent)
                true
            }
            R.id.action_export_all_profiles -> {
                val fileName = getSuggestedExportFileName(null)
                val intent = FileUtils.createExportIntent(fileName)
                exportAllLauncher.launch(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleExportSingle(uri: Uri) {
        val profileId = exportingProfileId ?: return
        val jsonString = viewModel.exportSingleProfile(profileId)
        if (jsonString != null) {
            val result = FileUtils.writeToUri(this, uri, jsonString)
            if (result.isSuccess) {
                Toast.makeText(this, getString(R.string.export_single_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.export_error, result.exceptionOrNull()?.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(
                this,
                getString(R.string.export_error, "Profile not found"),
                Toast.LENGTH_SHORT
            ).show()
        }
        exportingProfileId = null
    }

    private fun handleExportAll(uri: Uri) {
        lifecycleScope.launch {
            val profiles = viewModel.state.value.let { state ->
                when (state) {
                    is ProfileManagementState.Loaded -> state.profiles
                    else -> emptyList()
                }
            }
            val jsonString = viewModel.exportProfiles(profiles)
            val result = FileUtils.writeToUri(this@ProfileManagementActivity, uri, jsonString)
            if (result.isSuccess) {
                Toast.makeText(
                    this@ProfileManagementActivity,
                    getString(R.string.export_all_success, profiles.size),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@ProfileManagementActivity,
                    getString(R.string.export_error, result.exceptionOrNull()?.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val result = FileUtils.readFromUri(this@ProfileManagementActivity, uri)
            if (result.isSuccess) {
                val jsonString = result.getOrNull() ?: return@launch
                viewModel.importProfiles(jsonString) { profileCount, conflictCount ->
                    val message = if (conflictCount > 0) {
                        getString(R.string.import_success_with_renames, profileCount, conflictCount)
                    } else {
                        getString(R.string.import_success, profileCount)
                    }
                    Toast.makeText(
                        this@ProfileManagementActivity,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this@ProfileManagementActivity,
                    getString(R.string.import_error, result.exceptionOrNull()?.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Shows the upload to shared dialog.
     */
    private fun showUploadDialog(profile: Profile) {
        val app = application as HAGeminiApp
        if (!app.isSharedConfigAvailable()) {
            Toast.makeText(this, "Shared config not available", Toast.LENGTH_SHORT).show()
            return
        }

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

    /**
     * Uploads a profile to shared storage.
     */
    private fun uploadProfile(profile: Profile, deleteLocal: Boolean) {
        viewModel.uploadToShared(
            profile = profile,
            deleteLocal = deleteLocal,
            onSuccess = {
                Toast.makeText(this, "Profile uploaded successfully", Toast.LENGTH_SHORT).show()
            },
            onError = { message ->
                showNameConflictDialog(profile, message)
            }
        )
    }

    /**
     * Shows a dialog for name conflicts during upload.
     */
    private fun showNameConflictDialog(profile: Profile, message: String) {
        AlertDialog.Builder(this)
            .setTitle("Name Already Taken")
            .setMessage(message + "\n\nPlease rename the profile locally before uploading.")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Generates a suggested filename for profile export.
     * Format: "ha_profiles_YYYY-MM-DD.haprofile" for all profiles
     * Format: "profile_<name>_YYYY-MM-DD.haprofile" for single profiles
     */
    private fun getSuggestedExportFileName(profileName: String?): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateString = dateFormat.format(Date())
        return if (profileName != null && profileName.isNotBlank()) {
            // Sanitize profile name for filename (remove special characters)
            val sanitized = profileName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            "profile_${sanitized}_$dateString.haprofile"
        } else {
            "ha_profiles_$dateString.haprofile"
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshProfiles()
        checkForMigrationOpportunity()
    }

    private fun checkForMigrationOpportunity() {
        val app = application as HAGeminiApp
        val prefs = getSharedPreferences("migration", MODE_PRIVATE)
        val migrationPromptShown = prefs.getBoolean("upload_prompt_shown", false)

        if (!migrationPromptShown && app.isSharedConfigAvailable()) {
            val localProfiles = ProfileManager.getLocalProfiles()
            val sharedProfiles = ProfileManager.getSharedProfiles()

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
        viewModel.uploadAllProfiles(profiles) { successCount, failCount ->
            val message = when {
                failCount == 0 -> "Uploaded $successCount profile(s) successfully"
                successCount == 0 -> "Upload failed for all profiles"
                else -> "Uploaded $successCount, failed $failCount (name conflicts)"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
