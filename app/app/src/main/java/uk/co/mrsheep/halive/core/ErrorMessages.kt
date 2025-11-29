package uk.co.mrsheep.halive.core

/**
 * Provides user-friendly error messages for common sync and network scenarios.
 */
object ErrorMessages {
    fun forSyncError(e: Exception): String {
        return when {
            e is java.net.UnknownHostException ->
                "Cannot reach Home Assistant. Check your connection."
            e is java.net.SocketTimeoutException ->
                "Connection timed out. Home Assistant may be slow or unreachable."
            e.message?.contains("401") == true ->
                "Authentication failed. Try logging in again."
            e.message?.contains("403") == true ->
                "Access denied. Check your Home Assistant permissions."
            e.message?.contains("404") == true ->
                "HA Live Config integration not found. Is it installed?"
            e is ProfileNameConflictException ->
                e.message ?: "A profile with this name already exists."
            else ->
                "Sync failed: ${e.message ?: "Unknown error"}"
        }
    }

    fun forDeleteError(e: Exception, isShared: Boolean): String {
        val target = if (isShared) "shared profile" else "profile"
        return when {
            e is java.net.UnknownHostException ->
                "Cannot delete $target while offline."
            else ->
                "Failed to delete $target: ${e.message}"
        }
    }
}
