package uk.co.mrsheep.halive.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile

/**
 * RecyclerView adapter for displaying a list of profiles.
 *
 * Displays profile name and active profile indicators (badge and card stroke).
 * Primary action (Edit) is visible; secondary actions (Duplicate/Export/Delete)
 * are in an overflow menu. Provides callbacks for various profile actions.
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
        private val defaultBadge: TextView = itemView.findViewById(R.id.defaultBadge)
        private val editButton: Button = itemView.findViewById(R.id.editButton)
        private val overflowButton: ImageButton = itemView.findViewById(R.id.overflowButton)
        private val cardView: MaterialCardView = itemView as MaterialCardView

        fun bind(profile: Profile) {
            profileNameText.text = profile.name

            // Check if this profile is active
            val isActive = profile.id == activeProfileId

            // Show/hide active badge
            defaultBadge.visibility = if (isActive) View.VISIBLE else View.GONE

            // Apply card stroke (always 2dp to prevent layout shift, just change color)
            val strokeWidth = (2 * itemView.context.resources.displayMetrics.density).toInt()
            cardView.strokeWidth = strokeWidth

            if (isActive) {
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.active_profile_stroke)
            } else {
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.divider_light)
            }

            // Item click to set as active
            itemView.setOnClickListener {
                onItemClick(profile)
            }

            // Edit button (primary action)
            editButton.setOnClickListener {
                onEdit(profile)
            }

            // Overflow menu (secondary actions)
            overflowButton.setOnClickListener { view ->
                showOverflowMenu(view, profile)
            }
        }

        private fun showOverflowMenu(view: View, profile: Profile) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.profile_overflow_menu, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_duplicate -> {
                        onDuplicate(profile)
                        true
                    }
                    R.id.action_export -> {
                        onExport(profile)
                        true
                    }
                    R.id.action_delete -> {
                        onDelete(profile)
                        true
                    }
                    else -> false
                }
            }

            popup.show()
        }
    }
}
