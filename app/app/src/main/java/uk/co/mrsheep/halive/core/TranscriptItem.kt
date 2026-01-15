package uk.co.mrsheep.halive.core

/**
 * Represents an item in the transcript - either speech or a tool call.
 * Used for the raw log entries before grouping.
 */
sealed class TranscriptItem {
    data class Speech(
        val speaker: TranscriptionSpeaker,
        val chunk: String
    ) : TranscriptItem()

    data class ToolCall(
        val toolName: String,
        val parameters: String,
        val success: Boolean,
        val result: String,
        val timestamp: String
    ) : TranscriptItem()
}

/**
 * Represents a display item in the transcript after grouping.
 * Speech entries are grouped into turns, tool calls remain individual.
 */
sealed class TranscriptDisplayItem {
    data class SpeechTurn(
        val speaker: TranscriptionSpeaker,
        val fullText: String
    ) : TranscriptDisplayItem()

    data class ToolCallItem(
        val toolName: String,
        val parameters: String,
        val success: Boolean,
        val result: String
    ) : TranscriptDisplayItem()
}
