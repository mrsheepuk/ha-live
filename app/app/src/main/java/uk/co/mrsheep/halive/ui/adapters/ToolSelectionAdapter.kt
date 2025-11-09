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

data class SelectableTool(
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isAvailable: Boolean = true // False if in profile but not in current MCP list
)

class ToolSelectionAdapter(
    private val onToolToggled: (String, Boolean) -> Unit
) : ListAdapter<SelectableTool, ToolSelectionAdapter.ViewHolder>(ToolDiffCallback()) {

    private var allTools: List<SelectableTool> = emptyList()
    private var filteredTools: List<SelectableTool> = emptyList()

    fun submitFullList(tools: List<SelectableTool>) {
        allTools = tools
        filteredTools = tools
        submitList(tools)
    }

    fun filter(query: String) {
        filteredTools = if (query.isBlank()) {
            allTools
        } else {
            allTools.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
            }
        }
        submitList(filteredTools)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tool_selection, parent, false)
        return ViewHolder(view, onToolToggled)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onToolToggled: (String, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val checkbox: CheckBox = itemView.findViewById(R.id.toolCheckbox)
        private val nameText: TextView = itemView.findViewById(R.id.toolName)
        private val descriptionText: TextView = itemView.findViewById(R.id.toolDescription)

        fun bind(tool: SelectableTool) {
            val displayName = if (!tool.isAvailable) {
                "${tool.name} (unavailable)"
            } else {
                tool.name
            }

            nameText.text = displayName
            descriptionText.text = tool.description

            // Clear listener before setting value to avoid duplicate callbacks
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = tool.isSelected

            // Disable checkbox if tool is not available
            checkbox.isEnabled = tool.isAvailable

            // Set click listener on the whole item
            itemView.setOnClickListener {
                if (tool.isAvailable) {
                    val newState = !checkbox.isChecked
                    checkbox.isChecked = newState
                    onToolToggled(tool.name, newState)
                }
            }

            // Set checkbox listener (after setting initial value)
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (tool.isAvailable) {
                    onToolToggled(tool.name, isChecked)
                }
            }
        }
    }

    class ToolDiffCallback : DiffUtil.ItemCallback<SelectableTool>() {
        override fun areItemsTheSame(oldItem: SelectableTool, newItem: SelectableTool): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: SelectableTool, newItem: SelectableTool): Boolean {
            return oldItem == newItem
        }
    }
}
