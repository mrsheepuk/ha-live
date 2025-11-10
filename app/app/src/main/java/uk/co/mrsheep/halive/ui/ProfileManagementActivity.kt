package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile
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
 * - Click profile to set as default
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
        supportActionBar?.title = "Manage Profiles"

        initViews()
        setupRecyclerView()
        observeState()
    }

    private fun initViews() {
        profilesRecyclerView = findViewById(R.id.profilesRecyclerView)
        fabCreateProfile = findViewById(R.id.fabCreateProfile)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)

        // FAB click listener to create new profile
        fabCreateProfile.setOnClickListener {
            val intent = Intent(this, ProfileEditorActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onItemClick = { profile ->
                // Click to set as default
                viewModel.setDefaultProfile(profile.id)
            },
            onEdit = { profile ->
                val intent = Intent(this, ProfileEditorActivity::class.java)
                intent.putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile.id)
                startActivity(intent)
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

                // Update the adapter with new profiles
                profileAdapter.submitList(state.profiles)
            }
            is ProfileManagementState.Error -> {
                loadingIndicator.visibility = View.GONE
                profilesRecyclerView.visibility = View.VISIBLE
                errorText.visibility = View.GONE

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
        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Delete profile \"${profile.name}\"?\n\nThis action cannot be undone.")
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
