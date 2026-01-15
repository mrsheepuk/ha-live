package uk.co.mrsheep.halive.core

// Represents a tool call log entry
data class LogEntry(
    val timestamp: String,
    val toolName: String,
    val parameters: String,
    val success: Boolean,
    val result: String
)

enum class TranscriptionSpeaker {
    MODELTHOUGHT,
    MODEL,
    USER,
}

data class TranscriptionEntry(
    val spokenBy: TranscriptionSpeaker,
    val chunk: String
)

public interface AppLogger {
    fun addLogEntry(log: LogEntry)
    fun addModelTranscription(chunk: String, isThought: Boolean)
    fun addUserTranscription(chunk: String)
    fun addToolCallToTranscript(toolName: String, parameters: String, success: Boolean, result: String)
}
