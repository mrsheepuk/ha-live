package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable
import java.util.UUID

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
    val systemPrompt: String,
    val personality: String,
    val backgroundInfo: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
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
                isDefault = true
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
        return """
        {
            "name": "$name",
            "systemPrompt": "${systemPrompt.replace("\"", "\\\"")}",
            "personality": "${personality.replace("\"", "\\\"")}",
            "backgroundInfo": "${backgroundInfo.replace("\"", "\\\"")}"
        }
        """.trimIndent()
    }
}
