package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Where a profile is stored.
 */
@Serializable
enum class ProfileSource {
    LOCAL,   // Stored only on this device
    SHARED   // Synced with Home Assistant
}

/**
 * Defines the mode for filtering tools in a profile.
 */
@Serializable
enum class ToolFilterMode {
    ALL,
    SELECTED
}

/**
 * Represents a profile with a custom system prompt.
 *
 * Each profile defines a unique "personality" for the AI assistant.
 * Profiles are stored in SharedPreferences as JSON.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String = SystemPromptConfig.DEFAULT_SYSTEM_PROMPT,
    val personality: String = SystemPromptConfig.DEFAULT_PERSONALITY,
    val backgroundInfo: String = SystemPromptConfig.DEFAULT_BACKGROUND_INFO,
    val initialMessageToAgent: String = "",
    val model: String = SystemPromptConfig.DEFAULT_MODEL,
    val voice: String = SystemPromptConfig.DEFAULT_VOICE,
    val includeLiveContext: Boolean = SystemPromptConfig.DEFAULT_INCLUDE_LIVE_CONTEXT,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val toolFilterMode: ToolFilterMode = ToolFilterMode.ALL,
    val selectedToolNames: Set<String> = emptySet(),
    val enableTranscription: Boolean = SystemPromptConfig.DEFAULT_ENABLE_TRANSCRIPTION,
    val autoStartChat: Boolean = false,
    val interruptable: Boolean = SystemPromptConfig.DEFAULT_INTERRUPTABLE,
    // Shared config metadata
    val source: ProfileSource = ProfileSource.LOCAL,
    val lastModified: String? = null,
    val modifiedBy: String? = null,
    val schemaVersion: Int = 1
) {
    /**
     * Convert to format expected by HA integration.
     */
    fun toSharedFormat(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "system_prompt" to systemPrompt,
        "personality" to personality,
        "background_info" to backgroundInfo,
        "model" to model,
        "voice" to voice,
        "tool_filter_mode" to toolFilterMode.name,
        "selected_tools" to selectedToolNames.toList(),
        "include_live_context" to includeLiveContext,
        "enable_transcription" to enableTranscription,
        "auto_start_chat" to autoStartChat,
        "initial_message" to initialMessageToAgent,
        "interruptable" to interruptable
    )

    companion object {
        /**
         * Creates a default profile with the standard system prompt.
         */
        fun createDefault(): Profile {
            return Profile(
                name = "Default",
                systemPrompt = SystemPromptConfig.DEFAULT_SYSTEM_PROMPT,
                personality = SystemPromptConfig.DEFAULT_PERSONALITY,
                backgroundInfo = SystemPromptConfig.DEFAULT_BACKGROUND_INFO,
                initialMessageToAgent = SystemPromptConfig.DEFAULT_INITIAL_MESSAGE_TO_AGENT,
                model = SystemPromptConfig.DEFAULT_MODEL,
                voice = SystemPromptConfig.DEFAULT_VOICE,
                includeLiveContext = SystemPromptConfig.DEFAULT_INCLUDE_LIVE_CONTEXT,
                toolFilterMode = ToolFilterMode.ALL,
                selectedToolNames = emptySet(),
                enableTranscription = SystemPromptConfig.DEFAULT_ENABLE_TRANSCRIPTION,
                autoStartChat = false,
                interruptable = SystemPromptConfig.DEFAULT_INTERRUPTABLE
            )
        }

        /**
         * Create Profile from shared config format.
         */
        fun fromShared(shared: uk.co.mrsheep.halive.services.SharedProfile): Profile {
            return Profile(
                id = shared.id,
                name = shared.name,
                systemPrompt = shared.systemPrompt,
                personality = shared.personality,
                backgroundInfo = shared.backgroundInfo,
                model = shared.model,
                voice = shared.voice,
                toolFilterMode = try {
                    ToolFilterMode.valueOf(shared.toolFilterMode)
                } catch (e: Exception) {
                    ToolFilterMode.ALL
                },
                selectedToolNames = shared.selectedTools.toSet(),
                includeLiveContext = shared.includeLiveContext,
                enableTranscription = shared.enableTranscription,
                autoStartChat = shared.autoStartChat,
                initialMessageToAgent = shared.initialMessage,
                interruptable = shared.interruptable,
                source = ProfileSource.SHARED,
                lastModified = shared.lastModified,
                modifiedBy = shared.modifiedBy,
                schemaVersion = shared.schemaVersion
            )
        }
    }

    /**
     * Updates the lastUsedAt timestamp.
     */
    fun markAsUsed(): Profile {
        return copy(lastUsedAt = System.currentTimeMillis())
    }

    /**
     * Combines the system prompt, personality, and background info into a single formatted prompt.
     */
    fun getCombinedPrompt(): String {
        return """
            <system_prompt>
            $systemPrompt
            </system_prompt>

            <personality>
            $personality
            </personality>

            <background_info>
            $backgroundInfo
            </background_info>
        """.trimIndent()
    }

    /**
     * Returns a JSON string suitable for clipboard sharing.
     */
    fun toJsonString(): String {
        val toolNamesJson = selectedToolNames.joinToString(",") { "\"$it\"" }
        return """
        {
            "name": "$name",
            "systemPrompt": "${systemPrompt.replace("\"", "\\\"")}",
            "personality": "${personality.replace("\"", "\\\"")}",
            "backgroundInfo": "${backgroundInfo.replace("\"", "\\\"")}",
            "initialMessageToAgent": "${initialMessageToAgent.replace("\"", "\\\"")}",
            "model": "${model.replace("\"", "\\\"")}",
            "voice": "${voice.replace("\"", "\\\"")}",
            "includeLiveContext": $includeLiveContext,
            "enableTranscription": $enableTranscription,
            "autoStartChat": $autoStartChat,
            "interruptable": $interruptable,
            "toolFilterMode": "$toolFilterMode",
            "selectedToolNames": [$toolNamesJson]
        }
        """.trimIndent()
    }
}
