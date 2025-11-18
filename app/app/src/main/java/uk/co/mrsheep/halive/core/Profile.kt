package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import java.util.UUID

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
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val toolFilterMode: ToolFilterMode = ToolFilterMode.ALL,
    val selectedToolNames: Set<String> = emptySet(),
    val enableTranscription: Boolean = SystemPromptConfig.DEFAULT_ENABLE_TRANSCRIPTION,
    val autoStartChat: Boolean = false,
    val interruptable: Boolean = SystemPromptConfig.DEFAULT_INTERRUPTABLE
) {
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
                isDefault = true,
                toolFilterMode = ToolFilterMode.ALL,
                selectedToolNames = emptySet(),
                enableTranscription = SystemPromptConfig.DEFAULT_ENABLE_TRANSCRIPTION,
                autoStartChat = false,
                interruptable = SystemPromptConfig.DEFAULT_INTERRUPTABLE
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
