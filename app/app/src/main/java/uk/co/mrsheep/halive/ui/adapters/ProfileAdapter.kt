package uk.co.mrsheep.halive.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.Profile

/**
 * RecyclerView adapter for displaying a list of profiles.
 *
 * Displays profile name, system prompt preview, and default badge.
 * Provides callbacks for various profile actions.
 */
class ProfileAdapter(
    private val onItemClick: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDuplicate: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private var profiles: List<Profile> = emptyList()

    /**
     * Updates the list of profiles and refreshes the RecyclerView.
     */
    fun submitList(newProfiles: List<Profile>) {
        profiles = newProfiles
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
        private val editButton: Button = itemView.findViewById(R.id.editButton)
        private val duplicateButton: Button = itemView.findViewById(R.id.duplicateButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)

        fun bind(profile: Profile) {
            profileNameText.text = profile.name
            promptPreviewText.text = profile.systemPrompt

            // Show/hide default badge
            defaultBadge.visibility = if (profile.isDefault) View.VISIBLE else View.GONE

            // Item click to set as default
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

            deleteButton.setOnClickListener {
                onDelete(profile)
            }
        }
    }
}
