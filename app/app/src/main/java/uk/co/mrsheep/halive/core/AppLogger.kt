package uk.co.mrsheep.halive.core

// Represents a tool call log entry
data class LogEntry(
    val timestamp: String,
    val toolName: String,
    val parameters: String,
    val success: Boolean,
    val result: String
)

public interface AppLogger {
    fun addLogEntry(log: LogEntry)
}
