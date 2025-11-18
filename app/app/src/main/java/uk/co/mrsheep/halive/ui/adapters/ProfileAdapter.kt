package uk.co.mrsheep.halive.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile

/**
 * RecyclerView adapter for displaying a list of profiles.
 *
 * Displays profile name, system prompt preview, and active profile indicators
 * (radio button, badge, and card stroke).
 * Provides callbacks for various profile actions.
 */
class ProfileAdapter(
    private val onItemClick: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDuplicate: (Profile) -> Unit,
    private val onExport: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private var profiles: List<Profile> = emptyList()
    private var activeProfileId: String? = null

    /**
     * Updates the list of profiles and active profile ID, then refreshes the RecyclerView.
     */
    fun submitList(newProfiles: List<Profile>, newActiveProfileId: String? = null) {
        profiles = newProfiles
        activeProfileId = newActiveProfileId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profiles[position]
        holder.bind(profile)
    }

    override fun getItemCount(): Int = profiles.size

    /**
     * ViewHolder for a single profile item.
     */
    inner class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profileNameText: TextView = itemView.findViewById(R.id.profileNameText)
        private val promptPreviewText: TextView = itemView.findViewById(R.id.promptPreviewText)
        private val defaultBadge: TextView = itemView.findViewById(R.id.defaultBadge)
        private val activeRadioButton: ImageView = itemView.findViewById(R.id.activeRadioButton)
        private val editButton: Button = itemView.findViewById(R.id.editButton)
        private val duplicateButton: Button = itemView.findViewById(R.id.duplicateButton)
        private val exportButton: Button = itemView.findViewById(R.id.exportButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        private val cardView: MaterialCardView = itemView as MaterialCardView

        fun bind(profile: Profile) {
            profileNameText.text = profile.name
            promptPreviewText.text = profile.getCombinedPrompt()

            // Check if this profile is active
            val isActive = profile.id == activeProfileId

            // Show/hide active badge and radio button
            defaultBadge.visibility = if (isActive) View.VISIBLE else View.GONE
            activeRadioButton.visibility = if (isActive) View.VISIBLE else View.GONE

            // Apply card stroke for active profile (no background tint)
            if (isActive) {
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.active_profile_stroke)
                cardView.strokeWidth = (2 * itemView.context.resources.displayMetrics.density).toInt()
            } else {
                cardView.strokeWidth = 0
            }

            // Item click to set as active
            itemView.setOnClickListener {
                onItemClick(profile)
            }

            // Action button callbacks
            editButton.setOnClickListener {
                onEdit(profile)
            }

            duplicateButton.setOnClickListener {
                onDuplicate(profile)
            }

            exportButton.setOnClickListener {
                onExport(profile)
            }

            deleteButton.setOnClickListener {
                onDelete(profile)
            }
        }
    }
}
