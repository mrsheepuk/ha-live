package uk.co.mrsheep.halive.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.ui.adapters.ProfileAdapter

/**
 * Activity for configuring a conversation widget.
 *
 * This activity allows users to select a profile for a widget instance.
 * When a profile is selected:
 * - The profile ID is saved to SharedPreferences with key "widget_profile_${appWidgetId}"
 * - The widget is updated via ConversationWidgetProvider
 * - RESULT_OK is returned with the widget ID
 * - The activity finishes
 *
 * If no profiles exist, a message is displayed.
 * If the user closes the activity without selecting a profile, RESULT_CANCELED is returned.
 */
class ConversationWidgetConfigActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var profilesRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyStateText: TextView

    private lateinit var profileAdapter: ProfileAdapter
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)

        // Get widget ID from intent extras
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        // If widget ID is invalid, finish with CANCELED
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Default to RESULT_CANCELED (important for widget add flow)
        setResult(RESULT_CANCELED)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        setupRecyclerView()
        loadProfiles()
    }

    private fun initViews() {
        profilesRecyclerView = findViewById(R.id.profilesRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyStateText = findViewById(R.id.emptyStateText)
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onItemClick = { profile ->
                selectProfile(profile)
            },
            onEdit = { },      // No-op for widget config
            onDuplicate = { }, // No-op for widget config
            onExport = { },    // No-op for widget config
            onDelete = { }     // No-op for widget config
        )

        profilesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConversationWidgetConfigActivity)
            adapter = profileAdapter
        }
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            ProfileManager.profiles.collect { profiles ->
                updateUIWithProfiles(profiles)
            }
        }
    }

    private fun updateUIWithProfiles(profiles: List<Profile>) {
        if (profiles.isEmpty()) {
            // No profiles exist - show empty state
            loadingIndicator.visibility = View.GONE
            profilesRecyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
        } else {
            // Profiles exist - show list
            loadingIndicator.visibility = View.GONE
            profilesRecyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE

            // Submit the profiles to the adapter (no active profile highlighting needed)
            profileAdapter.submitList(profiles, activeProfileId = null)
        }
    }

    /**
     * Called when a profile is selected for the widget.
     * - Saves the profile ID to SharedPreferences
     * - Updates the widget via ConversationWidgetProvider
     * - Sets RESULT_OK with widget ID
     * - Finishes the activity
     */
    private fun selectProfile(profile: Profile) {
        // Save profile ID to SharedPreferences using ConversationWidgetProvider helper
        ConversationWidgetProvider.setWidgetProfileId(this, appWidgetId, profile.id)

        // Update widget via ConversationWidgetProvider
        val appWidgetManager = AppWidgetManager.getInstance(this)
        ConversationWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)

        // Return RESULT_OK with widget ID
        val resultIntent = Intent()
        resultIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultIntent)

        // Finish activity
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
