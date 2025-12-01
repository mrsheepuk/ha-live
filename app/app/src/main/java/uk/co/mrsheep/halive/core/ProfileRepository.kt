package uk.co.mrsheep.halive.core

/**
 * Interface for profile storage operations.
 * Implemented by LocalProfileRepository (SharedPreferences) and RemoteProfileRepository (Home Assistant).
 */
interface ProfileRepository {
    /**
     * Get all profiles from this repository.
     */
    suspend fun getAll(): List<Profile>

    /**
     * Get a profile by ID.
     * @return The profile, or null if not found.
     */
    suspend fun getById(id: String): Profile?

    /**
     * Create a new profile.
     * @param profile The profile to create (ID will be used or generated).
     * @return The created profile.
     * @throws ProfileNameConflictException if name is already taken.
     * @throws IllegalArgumentException if validation fails.
     */
    suspend fun create(profile: Profile): Profile

    /**
     * Update an existing profile.
     * @param profile The profile with updated values.
     * @return The updated profile.
     * @throws ProfileNameConflictException if name is already taken by another profile.
     * @throws IllegalArgumentException if profile doesn't exist or validation fails.
     */
    suspend fun update(profile: Profile): Profile

    /**
     * Delete a profile by ID.
     * @param profileId The ID of the profile to delete.
     * @return True if deleted, false if not found.
     */
    suspend fun delete(profileId: String): Boolean

    /**
     * Check if a profile name is available.
     * @param name The name to check.
     * @param excludeId Optional profile ID to exclude from the check (for updates).
     * @return True if the name is available.
     */
    suspend fun isNameAvailable(name: String, excludeId: String? = null): Boolean

    /**
     * Get the source type for profiles from this repository.
     */
    fun getSource(): ProfileSource
}

/**
 * Exception thrown when a profile name is already taken.
 */
class ProfileNameConflictException(message: String) : Exception(message)
