package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.pow

/**
 * Represents a wake word detection model configuration.
 * Stores metadata about an ONNX model file for wake word detection.
 *
 * @param id Unique identifier for this model (UUID or timestamp-based)
 * @param displayName User-friendly name for display in UI
 * @param fileName Name of the ONNX file (e.g., "ok_computer.onnx")
 * @param isBuiltIn Whether this model is bundled with the app (cannot be deleted)
 * @param filePath Absolute path to the ONNX model file on disk
 * @param addedDate Timestamp (milliseconds since epoch) when model was added/imported
 * @param fileSizeBytes Size of the ONNX file in bytes
 */
@Serializable
data class WakeWordModelConfig(
    val id: String,
    val displayName: String,
    val fileName: String,
    val isBuiltIn: Boolean,
    val filePath: String,
    val addedDate: Long,
    val fileSizeBytes: Long
) {
    /**
     * Returns human-readable file size (e.g., "206 KB", "1.2 MB").
     * Uses binary (1024-based) units.
     *
     * @return Formatted file size string (e.g., "206 KB", "1.2 MB", "512 B")
     */
    fun getFileSizeFormatted(): String {
        if (fileSizeBytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val unitIndex = (log10(fileSizeBytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val displaySize = fileSizeBytes / 1024.0.pow(unitIndex.toDouble())

        return when {
            unitIndex == 0 -> "${fileSizeBytes} B" // Bytes are always integers
            displaySize < 10 -> String.format("%.1f %s", displaySize, units[unitIndex])
            else -> String.format("%.0f %s", displaySize, units[unitIndex])
        }
    }
}
