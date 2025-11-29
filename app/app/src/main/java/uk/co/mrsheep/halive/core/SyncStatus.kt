package uk.co.mrsheep.halive.core

/**
 * Represents the sync status of a shared profile.
 */
enum class SyncStatus {
    SYNCED,      // Up to date with HA
    SYNCING,     // Currently syncing
    PENDING,     // Local changes pending sync
    ERROR,       // Sync failed
    OFFLINE      // HA unreachable
}
