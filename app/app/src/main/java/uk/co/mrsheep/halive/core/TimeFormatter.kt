package uk.co.mrsheep.halive.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for formatting timestamps as human-readable relative times.
 */
object TimeFormatter {
    fun formatRelative(isoTime: String?): String {
        if (isoTime == null) return ""

        return try {
            val instant = java.time.Instant.parse(isoTime)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(instant, now)

            when {
                duration.toMinutes() < 1 -> "just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() < 7 -> "${duration.toDays()}d ago"
                else -> {
                    val formatter = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d")
                        .withZone(java.time.ZoneId.systemDefault())
                    formatter.format(instant)
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun formatTime(timestampMillis: Long): String {
        if (timestampMillis <= 0) return "Never"
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return sdf.format(Date(timestampMillis))
    }
}
