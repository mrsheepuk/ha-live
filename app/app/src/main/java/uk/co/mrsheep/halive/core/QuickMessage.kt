package uk.co.mrsheep.halive.core

import kotlinx.serialization.Serializable

/**
 * Represents a quick message that can be sent to the AI agent during a conversation.
 *
 * Quick messages provide users with predefined, frequently-used text inputs
 * that can be quickly sent without typing.
 */
@Serializable
data class QuickMessage(
    val id: String,              // Unique identifier for this quick message
    val label: String,           // Button/chip text displayed to the user
    val message: String,         // Text sent to the agent
    val enabled: Boolean = true  // Whether this message is available for use
)
