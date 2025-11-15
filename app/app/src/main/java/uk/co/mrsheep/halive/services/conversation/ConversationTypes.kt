package uk.co.mrsheep.halive.services.conversation

import kotlinx.serialization.json.JsonElement

/**
 * Domain types for conversation services.
 *
 * These types are independent of any AI provider implementation (Firebase Gemini Live,
 * Direct Protocol, etc.) and serve as the canonical representation for conversation
 * state within the application.
 */

/**
 * Represents a tool or function call initiated by the AI model.
 *
 * This is a provider-agnostic representation that bridges between AI provider APIs
 * and Home Assistant service execution. The tool call contains all necessary information
 * for the application to route the call to the appropriate executor.
 *
 * @property id Unique identifier for this tool call. Used to match the corresponding
 *            [ToolResponse] when the tool execution completes.
 * @property name The name of the tool or function being called (e.g., "CallService",
 *          "GetLiveContext").
 * @property arguments Map of argument names to their values. Stored as [JsonElement]
 *          to support arbitrary JSON structures without provider-specific parsing.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * Represents the response back to the AI model after a tool execution.
 *
 * This pairs with [ToolCall] to complete the tool call lifecycle. The response
 * contains either the result of successful execution or an error message describing
 * what went wrong.
 *
 * @property id Must match the corresponding [ToolCall.id] so the AI can correlate
 *          the response with its request.
 * @property name Should match the corresponding [ToolCall.name] for context.
 * @property result The string representation of the tool's output on success.
 *          Contains the serialized result data or structured response.
 * @property error Optional error message if the tool call failed. If null, the call
 *          is assumed to have succeeded. Default is null.
 */
data class ToolResponse(
    val id: String,
    val name: String,
    val result: String,
    val error: String? = null
)

/**
 * Represents transcription information from a conversation exchange.
 *
 * Captures what was said by both the user and the AI model during a conversation turn.
 * Both fields are nullable to accommodate partial transcriptions or streaming scenarios
 * where either party's text may not yet be available.
 *
 * @property userText The transcribed text of what the user said. May be null if user
 *          audio was not captured or has not yet been transcribed.
 * @property modelText The generated text response from the AI model. May be null if the
 *          model has not yet generated its response.
 */
data class TranscriptInfo(
    val userText: String?,
    val modelText: String?
)
