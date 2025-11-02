package uk.co.mrsheep.halive.services

// NOTE: This is a STUB for Task 1. Full implementation in Task 3.
// This class will handle:
// - Opening SSE connection to /api/mcp
// - MCP initialization handshake (initialize -> initialized)
// - Sending JSON-RPC requests and correlating responses
// - Graceful shutdown

class McpClientManager(
    private val haBaseUrl: String,
    private val haToken: String
) {
    /**
     * Phase 1: Initialize the MCP connection.
     * - Opens SSE connection
     * - Sends 'initialize' request
     * - Sends 'initialized' notification
     */
    suspend fun initialize(): Boolean {
        // TODO (Task 3): Implement SSE connection + handshake
        return true // Stub: always succeeds
    }

    /**
     * Phase 3: Gracefully shut down the connection.
     */
    fun shutdown() {
        // TODO (Task 3): Close SSE connection
    }

    // TODO (Task 3): Add getTools() method
    // TODO (Task 3): Add callTool() method
}
