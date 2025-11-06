package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.ui.adapters.ProfileAdapter

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

    private lateinit var profilesRecyclerView: RecyclerView
    private lateinit var fabCreateProfile: FloatingActionButton
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView

    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_management)

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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
