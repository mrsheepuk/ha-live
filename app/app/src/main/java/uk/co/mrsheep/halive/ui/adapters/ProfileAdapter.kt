package uk.co.mrsheep.halive.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.ProfileSource
import uk.co.mrsheep.halive.core.TimeFormatter

/**
 * RecyclerView adapter for displaying a list of profiles.
 *
 * Displays profile name and active profile indicators (badge and card stroke).
 * Primary action (Edit) is visible; secondary actions (Add Shortcut/Duplicate/Export/Delete)
 * are in an overflow menu. Provides callbacks for various profile actions.
 */
class ProfileAdapter(
    private val onItemClick: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onAddShortcut: (Profile) -> Unit,
    private val onDuplicate: (Profile) -> Unit,
    private val onExport: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit,
    private val onUploadToShared: (Profile) -> Unit = {},
    private val onDownloadToLocal: (Profile) -> Unit = {}
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private var profiles: List<Profile> = emptyList()
    private var activeProfileId: String? = null
    private var isOffline: Boolean = false

    /**
     * Updates the list of profiles and active profile ID, then refreshes the RecyclerView.
     */
    fun submitList(newProfiles: List<Profile>, newActiveProfileId: String? = null, offline: Boolean = false) {
        profiles = newProfiles
        activeProfileId = newActiveProfileId
        isOffline = offline
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
        private val sourceIcon: ImageView = itemView.findViewById(R.id.sourceIcon)
        private val syncIcon: ImageView = itemView.findViewById(R.id.syncIcon)
        private val subtitleText: TextView = itemView.findViewById(R.id.subtitleText)

        fun bind(profile: Profile) {
            profileNameText.text = profile.name

            // Check if this profile is active
            val isActive = profile.id == activeProfileId

            // Show/hide active badge (use INVISIBLE to prevent layout shift)
            defaultBadge.visibility = if (isActive) View.VISIBLE else View.INVISIBLE

            // Apply card stroke (always 2dp to prevent layout shift, just change color)
            val strokeWidth = (2 * itemView.context.resources.displayMetrics.density).toInt()
            cardView.strokeWidth = strokeWidth

            if (isActive) {
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.teal_primary)
            } else {
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.divider_light)
            }

            // Show source icon
            val iconRes = if (profile.source == ProfileSource.SHARED) {
                R.drawable.ic_cloud
            } else {
                R.drawable.ic_phone
            }
            sourceIcon.setImageResource(iconRes)

            // Show sync status for shared profiles
            if (profile.source == ProfileSource.SHARED) {
                if (isOffline) {
                    syncIcon.visibility = View.VISIBLE
                    syncIcon.setImageResource(R.drawable.ic_cloud_off)
                    syncIcon.setColorFilter(
                        ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark)
                    )
                } else {
                    syncIcon.visibility = View.GONE
                }
            } else {
                syncIcon.visibility = View.GONE
            }

            // Show subtitle with relative time for shared profiles
            when {
                profile.source == ProfileSource.SHARED && profile.modifiedBy != null -> {
                    subtitleText.visibility = View.VISIBLE
                    val relativeTime = TimeFormatter.formatRelative(profile.lastModified)
                    val timeStr = if (relativeTime.isNotEmpty()) " ($relativeTime)" else ""
                    subtitleText.text = "Shared - Modified by ${profile.modifiedBy}$timeStr"
                }
                profile.source == ProfileSource.SHARED -> {
                    subtitleText.visibility = View.VISIBLE
                    val relativeTime = TimeFormatter.formatRelative(profile.lastModified)
                    val timeStr = if (relativeTime.isNotEmpty()) " - $relativeTime" else ""
                    subtitleText.text = "Shared$timeStr"
                }
                else -> {
                    subtitleText.visibility = View.VISIBLE
                    subtitleText.text = "Local only"
                }
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

            // Show/hide upload and download options based on profile source
            popup.menu.findItem(R.id.action_upload_to_shared)?.isVisible =
                profile.source == ProfileSource.LOCAL
            popup.menu.findItem(R.id.action_download_to_local)?.isVisible =
                profile.source == ProfileSource.SHARED

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_shortcut -> {
                        onAddShortcut(profile)
                        true
                    }
                    R.id.action_duplicate -> {
                        onDuplicate(profile)
                        true
                    }
                    R.id.action_upload_to_shared -> {
                        onUploadToShared(profile)
                        true
                    }
                    R.id.action_download_to_local -> {
                        onDownloadToLocal(profile)
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
