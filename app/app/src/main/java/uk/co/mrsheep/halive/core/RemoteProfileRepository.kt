package uk.co.mrsheep.halive.core

import android.util.Log
import uk.co.mrsheep.halive.services.SharedConfigRepository

/**
 * Repository for remote profiles stored in Home Assistant via the ha_live_config integration.
 *
 * This repository has NO caching - every operation goes directly to Home Assistant.
 * This ensures profiles are always up-to-date and eliminates sync issues.
 */
class RemoteProfileRepository(
    private val sharedConfigRepo: SharedConfigRepository
) : ProfileRepository {

    companion object {
        private const val TAG = "RemoteProfileRepository"
    }

    override suspend fun getAll(): List<Profile> {
        return try {
            val config = sharedConfigRepo.getSharedConfig()
            val profiles = config?.profiles?.map { Profile.fromShared(it) } ?: emptyList()
            Log.d(TAG, "Fetched ${profiles.size} remote profiles from HA")
            profiles
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch remote profiles", e)
            throw e
        }
    }

    override suspend fun getById(id: String): Profile? {
        return getAll().find { it.id == id }
    }

    override suspend fun create(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        // Check name availability
        if (!sharedConfigRepo.isProfileNameAvailable(profile.name)) {
            throw ProfileNameConflictException("A shared profile named '${profile.name}' already exists")
        }

        val sharedProfile = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            source = ProfileSource.SHARED
        )

        val newId = sharedConfigRepo.upsertProfile(sharedProfile)
            ?: throw Exception("Failed to create shared profile in Home Assistant")

        Log.i(TAG, "Created remote profile: ${profile.name} with ID: $newId")
        return sharedProfile.copy(id = newId)
    }

    override suspend fun update(profile: Profile): Profile {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }

        // Check name availability (excluding this profile)
        if (!sharedConfigRepo.isProfileNameAvailable(profile.name, profile.id)) {
            throw ProfileNameConflictException("A shared profile named '${profile.name}' already exists")
        }

        val updatedProfile = profile.copy(source = ProfileSource.SHARED)

        val success = sharedConfigRepo.upsertProfile(updatedProfile)
        if (success == null) {
            throw Exception("Failed to update shared profile in Home Assistant")
        }

        Log.i(TAG, "Updated remote profile: ${profile.name}")
        return updatedProfile
    }

    override suspend fun delete(profileId: String): Boolean {
        val success = sharedConfigRepo.deleteProfile(profileId)
        if (success) {
            Log.i(TAG, "Deleted remote profile: $profileId")
        } else {
            Log.w(TAG, "Failed to delete remote profile: $profileId")
        }
        return success
    }

    override suspend fun isNameAvailable(name: String, excludeId: String?): Boolean {
        return sharedConfigRepo.isProfileNameAvailable(name, excludeId)
    }

    override fun getSource(): ProfileSource = ProfileSource.SHARED
}
