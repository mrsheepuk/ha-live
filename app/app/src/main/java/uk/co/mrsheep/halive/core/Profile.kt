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
     * Returns a JSON string suitable for clipboard sharing.
     */
    fun toJsonString(): String {
        return """
        {
            "name": "$name",
            "systemPrompt": "${systemPrompt.replace("\"", "\\\"")}"
        }
        """.trimIndent()
    }
}
