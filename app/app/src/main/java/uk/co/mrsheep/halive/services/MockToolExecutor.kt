package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.serialization.json.JsonElement
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult
import uk.co.mrsheep.halive.services.mcp.ToolContent

class MockToolExecutor(
    private val realExecutor: ToolExecutor,
    private val passthroughTools: List<String>,
) : ToolExecutor {
    override suspend fun callTool(
        name: String,
        arguments: Map<String, JsonElement>
    ): ToolCallResult {
        if (name in passthroughTools) {
            return realExecutor.callTool(name, arguments)
        }

        // All other tools get mocked
        return createMockResponse(name, arguments)
    }

    override suspend fun getTools(): List<McpTool> {
        return realExecutor.getTools()
    }

    private fun createMockResponse(
        name: String,
        arguments: Map<String, JsonElement>
    ): ToolCallResult {
        Log.d(TAG, "Mock execution of tool: ${name} with args: ${arguments}")

        // Track the tool call for debugging
        synchronized(calledTools) {
            calledTools.add(name)
        }

        // Create a mock response that simulates successful execution
        val mockResult = "Mock execution of ${name} with args: ${arguments}"
        Log.d(TAG, "Mock response: $mockResult")

        return ToolCallResult(
            isError = false,
            content = listOf(ToolContent(type = "text", text = """
                {
                    "success": true,
                    "result": "Successfully executed action"
                }
            """.trimIndent()))
        )
    }

    companion object {
        private const val TAG = "MockToolExecutor"

        /**
         * List of tools that have been called via this executor during the current session.
         * Used for debugging and verifying which tools the Gemini model attempted to use.
         */
        private val calledTools = mutableListOf<String>()

        /**
         * Returns a copy of the list of tools that have been called via mock execution.
         *
         * @return A list of tool names in the order they were called
         */
        fun getCalledTools(): List<String> {
            return synchronized(calledTools) {
                calledTools.toList()
            }
        }

        /**
         * Clears the call history. Useful for resetting between test cases.
         */
        fun clearCallHistory() {
            synchronized(calledTools) {
                calledTools.clear()
            }
        }

        /**
         * Returns the number of tools that have been called via mock execution.
         *
         * @return Count of tool invocations
         */
        fun getCallCount(): Int {
            return synchronized(calledTools) {
                calledTools.size
            }
        }

        /**
         * Checks if a specific tool was called.
         *
         * @param toolName The name of the tool to check for
         * @return true if the tool was called at least once, false otherwise
         */
        fun wasToolCalled(toolName: String): Boolean {
            return synchronized(calledTools) {
                calledTools.contains(toolName)
            }
        }
    }
}
