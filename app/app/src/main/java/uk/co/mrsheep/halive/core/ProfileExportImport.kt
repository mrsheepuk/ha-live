package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Represents exportable profile data without internal tracking fields.
 * Only includes user-configurable settings.
 */
@Serializable
data class ExportableProfile(
    val name: String,
    val systemPrompt: String,
    val personality: String,
    val backgroundInfo: String,
    val initialMessageToAgent: String,
    val model: String,
    val voice: String,
    val includeLiveContext: Boolean,
    val toolFilterMode: ToolFilterMode,
    val selectedToolNames: Set<String>,
    val autoStartChat: Boolean
)

/**
 * Represents the complete export structure for profiles.
 */
@Serializable
data class ProfileExport(
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val exportSource: String = "HA Live Android",
    val profiles: List<ExportableProfile>
)

/**
 * Represents the result of importing profiles.
 */
@Serializable
data class ImportResult(
    val profiles: List<Profile>,
    val conflicts: List<String>
)

/**
 * Handles export and import of profile configurations.
 * Supports JSON serialization with conflict resolution.
 */
object ProfileExportImport {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Exports a list of profiles to a pretty-printed JSON string.
     *
     * @param profiles List of profiles to export
     * @return JSON string containing exported profiles
     */
    fun exportProfiles(profiles: List<Profile>): String {
        val exportableProfiles = profiles.map { sanitizeForImport(it) }
        val export = ProfileExport(
            exportedAt = System.currentTimeMillis(),
            profiles = exportableProfiles
        )
        return json.encodeToString(export)
    }

    /**
     * Imports profiles from a JSON string with conflict resolution.
     *
     * Generates new UUIDs for all imported profiles and sets:
     * - isDefault = false
     * - createdAt = current time
     * - lastUsedAt = current time
     *
     * @param jsonString JSON string containing profile export
     * @param existingProfiles List of existing profiles for conflict detection
     * @return Result containing imported profiles and list of conflicts
     */
    fun importProfiles(
        jsonString: String,
        existingProfiles: List<Profile>
    ): Result<ImportResult> {
        return try {
            val importData = validateImportData(jsonString).getOrThrow()

            val conflicts = mutableListOf<String>()
            val importedProfiles = mutableListOf<Profile>()
            val existingNames = existingProfiles.map { it.name }.toMutableSet()

            for (exportable in importData.profiles) {
                var name = exportable.name
                var nameConflictCount = 1

                // Resolve name conflicts by appending " (imported)" or " (imported N)"
                while (existingNames.contains(name)) {
                    nameConflictCount++
                    name = if (nameConflictCount == 2) {
                        "${exportable.name} (imported)"
                    } else {
                        "${exportable.name} (imported ${nameConflictCount - 1})"
                    }
                }

                if (name != exportable.name) {
                    conflicts.add("Profile '${exportable.name}' renamed to '$name'")
                }

                existingNames.add(name)

                // Create new profile with sanitized data
                val importedProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    systemPrompt = exportable.systemPrompt,
                    personality = exportable.personality,
                    backgroundInfo = exportable.backgroundInfo,
                    initialMessageToAgent = exportable.initialMessageToAgent,
                    model = exportable.model,
                    voice = exportable.voice,
                    includeLiveContext = exportable.includeLiveContext,
                    isDefault = false,
                    createdAt = System.currentTimeMillis(),
                    lastUsedAt = System.currentTimeMillis(),
                    toolFilterMode = exportable.toolFilterMode,
                    selectedToolNames = exportable.selectedToolNames,
                    autoStartChat = exportable.autoStartChat
                )

                importedProfiles.add(importedProfile)
            }

            Result.success(
                ImportResult(
                    profiles = importedProfiles,
                    conflicts = conflicts
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sanitizes a profile for export by removing internal fields.
     *
     * @param profile Profile to sanitize
     * @return ExportableProfile with only user-configurable fields
     */
    fun sanitizeForImport(profile: Profile): ExportableProfile {
        return ExportableProfile(
            name = profile.name,
            systemPrompt = profile.systemPrompt,
            personality = profile.personality,
            backgroundInfo = profile.backgroundInfo,
            initialMessageToAgent = profile.initialMessageToAgent,
            model = profile.model,
            voice = profile.voice,
            includeLiveContext = profile.includeLiveContext,
            toolFilterMode = profile.toolFilterMode,
            selectedToolNames = profile.selectedToolNames,
            autoStartChat = profile.autoStartChat
        )
    }

    /**
     * Validates the structure of imported JSON data.
     *
     * @param jsonString JSON string to validate
     * @return Result containing parsed ProfileExport or error
     */
    fun validateImportData(jsonString: String): Result<ProfileExport> {
        return try {
            val export = json.decodeFromString<ProfileExport>(jsonString)

            // Validate schema version
            if (export.schemaVersion != 1) {
                return Result.failure(
                    IllegalArgumentException(
                        "Unsupported schema version: ${export.schemaVersion}. Expected 1."
                    )
                )
            }

            // Validate profiles list is not empty
            if (export.profiles.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("No profiles found in export data.")
                )
            }

            // Validate each profile has a name
            export.profiles.forEach { profile ->
                if (profile.name.isBlank()) {
                    return Result.failure(
                        IllegalArgumentException("Profile name cannot be empty.")
                    )
                }
            }

            Result.success(export)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
