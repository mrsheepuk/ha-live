package uk.co.mrsheep.halive.services.conversation

import uk.co.mrsheep.halive.services.mcp.McpToolsListResult

/**
 * Interface for AI conversation services.
 *
 * This interface allows multiple implementations of conversation services,
 * supporting different backends (Firebase SDK, direct WebSocket API, etc.)
 * to power the Home Assistant voice assistant. Each implementation handles
 * the specifics of connecting to an AI provider while conforming to this
 * contract for tool execution and transcription handling.
 *
 * Domain types (ToolCall, ToolResponse, TranscriptInfo) are defined in
 * [ConversationTypes.kt] and are provider-agnostic.
 */
interface ConversationService {

    /**
     * Initialize the service with tools and configuration.
     *
     * @param mcpTools Raw tools from Home Assistant MCP server
     * @param systemPrompt System instructions for the AI
     * @param modelName Model to use (e.g., "gemini-2.0-flash-exp")
     * @param voiceName Voice to use (e.g., "Aoede")
     */
    suspend fun initialize(
        mcpTools: McpToolsListResult,
        systemPrompt: String,
        modelName: String,
        voiceName: String
    )

    /**
     * Start a conversation session.
     *
     * @param onToolCall Callback when AI wants to call a tool
     * @param onTranscript Optional callback for transcription updates
     */
    suspend fun startSession(
        onToolCall: suspend (ToolCall) -> ToolResponse,
        onTranscript: ((TranscriptInfo) -> Unit)? = null
    )

    /**
     * Send a text message to the AI during an active session.
     *
     * @param message Text message to send
     */
    suspend fun sendText(message: String)

    /**
     * Stop the current session.
     */
    fun stopSession()

    /**
     * Clean up resources.
     */
    fun cleanup()
}
