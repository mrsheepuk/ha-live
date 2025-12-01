package uk.co.mrsheep.halive.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import uk.co.mrsheep.halive.R

data class SelectableCamera(
    val entityId: String,
    val friendlyName: String,
    val isSelected: Boolean
)

class CameraSelectionAdapter(
    private val onCameraToggled: (String, Boolean) -> Unit
) : ListAdapter<SelectableCamera, CameraSelectionAdapter.ViewHolder>(CameraDiffCallback()) {

    private var allCameras: List<SelectableCamera> = emptyList()

    fun submitFullList(cameras: List<SelectableCamera>) {
        allCameras = cameras
        submitList(cameras)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tool_selection, parent, false)  // Reuse tool selection layout
        return ViewHolder(view, onCameraToggled)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onCameraToggled: (String, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val checkbox: CheckBox = itemView.findViewById(R.id.toolCheckbox)
        private val nameText: TextView = itemView.findViewById(R.id.toolName)
        private val descriptionText: TextView = itemView.findViewById(R.id.toolDescription)

        fun bind(camera: SelectableCamera) {
            nameText.text = camera.friendlyName
            descriptionText.text = camera.entityId

            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = camera.isSelected

            itemView.setOnClickListener {
                val newState = !checkbox.isChecked
                checkbox.isChecked = newState
                onCameraToggled(camera.entityId, newState)
            }

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                onCameraToggled(camera.entityId, isChecked)
            }
        }
    }

    class CameraDiffCallback : DiffUtil.ItemCallback<SelectableCamera>() {
        override fun areItemsTheSame(oldItem: SelectableCamera, newItem: SelectableCamera): Boolean {
            return oldItem.entityId == newItem.entityId
        }

        override fun areContentsTheSame(oldItem: SelectableCamera, newItem: SelectableCamera): Boolean {
            return oldItem == newItem
        }
    }
}
