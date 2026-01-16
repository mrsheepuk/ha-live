package uk.co.mrsheep.halive.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.TranscriptDisplayItem
import uk.co.mrsheep.halive.core.TranscriptDisplayItem.SpeechTurn
import uk.co.mrsheep.halive.core.TranscriptDisplayItem.ToolCallItem
import uk.co.mrsheep.halive.core.TranscriptionSpeaker

class TranscriptionAdapter : RecyclerView.Adapter<TranscriptionAdapter.TranscriptionViewHolder>() {

    private val items = mutableListOf<TranscriptDisplayItem>()
    private val expandedThoughts = mutableSetOf<Int>()
    private val expandedToolCalls = mutableSetOf<Int>()

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MODEL = 2
        private const val VIEW_TYPE_MODEL_THOUGHT = 3
        private const val VIEW_TYPE_TOOL_CALL = 4
    }

    abstract class TranscriptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(item: TranscriptDisplayItem, position: Int)
    }

    class UserViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(item: TranscriptDisplayItem, position: Int) {
            val speechTurn = item as SpeechTurn
            messageText.text = speechTurn.fullText
        }
    }

    class ModelViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(item: TranscriptDisplayItem, position: Int) {
            val speechTurn = item as SpeechTurn
            messageText.text = speechTurn.fullText
        }
    }

    inner class ModelThoughtViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(item: TranscriptDisplayItem, position: Int) {
            val speechTurn = item as SpeechTurn
            val isExpanded = expandedThoughts.contains(position)

            messageText.text = if (isExpanded) {
                speechTurn.fullText.trim()
            } else {
                "(thinking... tap to expand)"
            }

            itemView.setOnClickListener {
                if (expandedThoughts.contains(position)) {
                    expandedThoughts.remove(position)
                } else {
                    expandedThoughts.add(position)
                }
                notifyItemChanged(position)
            }
        }
    }

    inner class ToolCallViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.toolCallCard)
        private val toolNameText: TextView = itemView.findViewById(R.id.toolNameText)
        private val statusIcon: TextView = itemView.findViewById(R.id.statusIcon)
        private val actualToolNameText: TextView = itemView.findViewById(R.id.actualToolNameText)
        private val parametersText: TextView = itemView.findViewById(R.id.parametersText)
        private val resultText: TextView = itemView.findViewById(R.id.resultText)
        private val detailsSection: View = itemView.findViewById(R.id.detailsSection)
        private val expandIndicator: TextView = itemView.findViewById(R.id.expandIndicator)

        override fun bind(item: TranscriptDisplayItem, position: Int) {
            val toolCall = item as ToolCallItem
            val isExpanded = expandedToolCalls.contains(position)

            toolNameText.text = humanizeToolName(toolCall.toolName)
            actualToolNameText.text = toolCall.toolName

            if (toolCall.success) {
                statusIcon.text = "\u2713"
                statusIcon.setTextColor(itemView.context.getColor(R.color.tool_call_success))
            } else {
                statusIcon.text = "\u2717"
                statusIcon.setTextColor(itemView.context.getColor(R.color.tool_call_failure))
            }

            parametersText.text = toolCall.parameters
            resultText.text = toolCall.result

            detailsSection.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandIndicator.text = if (isExpanded) "\u25B2" else "\u25BC"  // ▲ or ▼

            card.setOnClickListener {
                if (expandedToolCalls.contains(position)) {
                    expandedToolCalls.remove(position)
                } else {
                    expandedToolCalls.add(position)
                }
                notifyItemChanged(position)
            }
        }

        private fun humanizeToolName(toolName: String): String {
            // Remove Hass prefix if present
            var name = toolName
            if (name.startsWith("Hass")) {
                name = name.removePrefix("Hass")
            }

            // Split into words based on format
            val words = when {
                name.contains("_") -> name.split("_")  // snake_case
                name.contains("-") -> name.split("-")  // kebab-case
                else -> {
                    // PascalCase or camelCase - split before uppercase letters
                    name.split(Regex("(?=[A-Z])")).filter { it.isNotEmpty() }
                }
            }

            // Join with spaces: capitalize first word, lowercase the rest
            return words.mapIndexed { index, word ->
                if (index == 0) word.replaceFirstChar { it.uppercaseChar() }
                else word.lowercase()
            }.joinToString(" ")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is SpeechTurn -> when (item.speaker) {
                TranscriptionSpeaker.USER -> VIEW_TYPE_USER
                TranscriptionSpeaker.MODEL -> VIEW_TYPE_MODEL
                TranscriptionSpeaker.MODELTHOUGHT -> VIEW_TYPE_MODEL_THOUGHT
            }
            is ToolCallItem -> VIEW_TYPE_TOOL_CALL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranscriptionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_transcription_user, parent, false)
                UserViewHolder(view)
            }
            VIEW_TYPE_MODEL -> {
                val view = inflater.inflate(R.layout.item_transcription_model, parent, false)
                ModelViewHolder(view)
            }
            VIEW_TYPE_MODEL_THOUGHT -> {
                val view = inflater.inflate(R.layout.item_transcription_model_thought, parent, false)
                ModelThoughtViewHolder(view)
            }
            VIEW_TYPE_TOOL_CALL -> {
                val view = inflater.inflate(R.layout.item_transcription_tool_call, parent, false)
                ToolCallViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: TranscriptionViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<TranscriptDisplayItem>) {
        items.clear()
        items.addAll(newItems)
        expandedThoughts.clear()
        expandedToolCalls.clear()
        notifyDataSetChanged()
    }
}
