package uk.co.mrsheep.halive.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.TranscriptionSpeaker
import uk.co.mrsheep.halive.core.TranscriptionTurn

class TranscriptionAdapter : RecyclerView.Adapter<TranscriptionAdapter.TranscriptionViewHolder>() {

    private val turns = mutableListOf<TranscriptionTurn>()

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MODEL = 2
        private const val VIEW_TYPE_MODEL_THOUGHT = 3
    }

    abstract class TranscriptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(turn: TranscriptionTurn)
    }

    class UserViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(turn: TranscriptionTurn) {
            messageText.text = turn.fullText
        }
    }

    class ModelViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(turn: TranscriptionTurn) {
            messageText.text = turn.fullText
        }
    }

    class ModelThoughtViewHolder(itemView: View) : TranscriptionViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        override fun bind(turn: TranscriptionTurn) {
            messageText.text = turn.fullText.trim()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (turns[position].speaker) {
            TranscriptionSpeaker.USER -> VIEW_TYPE_USER
            TranscriptionSpeaker.MODEL -> VIEW_TYPE_MODEL
            TranscriptionSpeaker.MODELTHOUGHT -> VIEW_TYPE_MODEL_THOUGHT
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
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: TranscriptionViewHolder, position: Int) {
        holder.bind(turns[position])
    }

    override fun getItemCount(): Int = turns.size

    fun updateTurns(newTurns: List<TranscriptionTurn>) {
        turns.clear()
        turns.addAll(newTurns)
        notifyDataSetChanged()
    }
}
