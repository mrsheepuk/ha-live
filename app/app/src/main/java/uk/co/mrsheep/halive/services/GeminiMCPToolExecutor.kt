package uk.co.mrsheep.halive.services

import android.util.Log
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.services.mcp.*

class GeminiMCPToolExecutor(
    private val mcpClient: McpClientManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Executes a tool via MCP and returns the result to Gemini.
     */
    suspend fun executeTool(functionCall: FunctionCallPart): FunctionResponsePart {
        return try {
            Log.d(TAG, "Executing tool: ${functionCall.name} with args: ${functionCall.args}")

            // Convert function call arguments to JsonElement map
            val arguments = functionCall.args.mapValues { (_, value) ->
                convertToJsonElement(value)
            }

            // Call the tool via MCP
            val result = mcpClient.callTool(
                name = functionCall.name,
                arguments = arguments
            )

            // Check if it was an error
            if (result.isError == true) {
                createErrorResponse(functionCall, result)
            } else {
                createSuccessResponse(functionCall, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing tool: ${functionCall.name}", e)
            // Handle exceptions (network errors, timeouts, etc.)
            FunctionResponsePart(
                name = functionCall.name,
                id = functionCall.id,
                response = buildJsonObject {
                    put("error", "Exception: ${e.message}")
                }
            )
        }
    }

    private fun convertToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonPrimitive(null as String?)
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun createSuccessResponse(
        functionCall: FunctionCallPart,
        result: ToolCallResult
    ): FunctionResponsePart {
        // Extract text content from MCP result
        val textContent = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        Log.d(TAG, "Tool ${functionCall.name} succeeded: $textContent")

        return FunctionResponsePart(
            name = functionCall.name,
            id = functionCall.id,
            response = buildJsonObject {
                put( key="result", json.parseToJsonElement(textContent))
            }
        )
    }

    private fun createErrorResponse(
        functionCall: FunctionCallPart,
        result: ToolCallResult
    ): FunctionResponsePart {
        val errorMessage = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        Log.e(TAG, "Tool ${functionCall.name} failed: $errorMessage")

        return FunctionResponsePart(
            name = functionCall.name,
            id = functionCall.id,
            response = buildJsonObject {
                put("error", errorMessage.ifEmpty { "Unknown error" })
            }
        )
    }

    companion object {
        private const val TAG = "GeminiMCPToolExecutor"
    }
}
