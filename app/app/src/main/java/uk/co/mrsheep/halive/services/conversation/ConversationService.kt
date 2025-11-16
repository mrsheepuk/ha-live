package uk.co.mrsheep.halive.services.conversation

import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.services.mcp.McpTool
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
     * @param languageCode Language code for the conversation (e.g., "en-US")
     */
    suspend fun initialize(
        tools: List<McpTool>,
        systemPrompt: String,
        modelName: String,
        voiceName: String,
        languageCode: String,
        toolExecutor: ToolExecutor,
        transcriptor: ((String?, String?, Boolean) -> Unit)? = null
    )

    /**
     * Start a conversation session.
     */
    suspend fun startSession()

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
