package uk.co.mrsheep.halive.services

import android.util.Log
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.mrsheep.halive.services.mcp.*

class HomeAssistantRepository(
    private val mcpClient: McpClientManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches tools from HA MCP Server and transforms them for Gemini.
     */
    suspend fun getTools(): List<Tool> {
        try {
            // Fetch tools via MCP
            val mcpToolsResult = mcpClient.getTools()

            Log.d(TAG, "Fetched ${mcpToolsResult.tools.size} tools from MCP server")

            // Transform each McpTool into a Gemini FunctionDeclaration
            val functionDeclarations = mcpToolsResult.tools.map { mcpTool ->
                transformMcpToGeminiFunctionDeclaration(mcpTool)
            }

            // Return a single Tool containing all function declarations
            return listOf(Tool.functionDeclarations(functionDeclarations = functionDeclarations))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch tools", e)
            throw e
        }
    }

    private fun transformMcpToGeminiFunctionDeclaration(mcpTool: McpTool): FunctionDeclaration {
        // Transform the properties to Schema objects
        val geminiProperties = mcpTool.inputSchema.properties.mapValues { (propName, mcpProp) ->
            transformMcpPropertyToSchema(mcpProp)
        }

        // Create the function declaration using the required field from MCP
        return FunctionDeclaration(
            name = mcpTool.name,
            description = mcpTool.description.ifEmpty { "No description provided" },
            parameters = geminiProperties,
        )
    }

    private fun transformMcpPropertyToSchema(mcpProp: McpProperty): Schema {
        // Handle 'anyOf' union types (e.g., HassSetVolumeRelative's volume_step)
        if (mcpProp.anyOf != null) {
            // For now, pick the first option as the primary type
            val firstOption = mcpProp.anyOf.first()
            return when (firstOption.type.lowercase()) {
                "string" -> if (firstOption.enum != null) {
                    Schema.enumeration(firstOption.enum, mcpProp.description, true)
                } else {
                    Schema.string(mcpProp.description, true)
                }
                "integer", "number" -> Schema.integer(mcpProp.description, true)
                "boolean" -> Schema.boolean(mcpProp.description, true)
                else -> Schema.string(mcpProp.description, true)
            }
        }

        // Handle array types (e.g., device_class with enum items)
        if (mcpProp.type == "array" && mcpProp.items != null) {
            val itemSchema = when (mcpProp.items.type.lowercase()) {
                "string" -> if (mcpProp.items.enum != null) {
                    Schema.enumeration(mcpProp.items.enum, null, true)
                } else {
                    Schema.string(null, true)
                }
                "integer", "number" -> Schema.integer(null, true)
                "boolean" -> Schema.boolean(null, true)
                else -> Schema.string(null, true)
            }
            return Schema.array(itemSchema, mcpProp.description, true)
        }

        // Handle simple types
        return when (mcpProp.type?.lowercase()) {
            "string" -> if (mcpProp.enum != null) {
                Schema.enumeration(mcpProp.enum, mcpProp.description, true)
            } else {
                Schema.string(mcpProp.description)
            }
            "integer" -> Schema.integer(mcpProp.description, true)
            "number" -> Schema.double(mcpProp.description, true)
            "boolean" -> Schema.boolean(mcpProp.description, true)
            "object" -> Schema.obj(emptyMap(), emptyList(), mcpProp.description, true)
            else -> Schema.string(mcpProp.description, true) // Default fallback
        }
    }

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
                createErrorResponse(functionCall.name, result)
            } else {
                createSuccessResponse(functionCall.name, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing tool: ${functionCall.name}", e)
            // Handle exceptions (network errors, timeouts, etc.)
            FunctionResponsePart(
                name = functionCall.name,
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
        name: String,
        result: ToolCallResult
    ): FunctionResponsePart {
        // Extract text content from MCP result
        val textContent = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        Log.d(TAG, "Tool $name succeeded: $textContent")

        return FunctionResponsePart(
            name = name,
            response = buildJsonObject {
                json.parseToJsonElement(textContent)
            }
        )
    }

    private fun createErrorResponse(
        name: String,
        result: ToolCallResult
    ): FunctionResponsePart {
        val errorMessage = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        Log.e(TAG, "Tool $name failed: $errorMessage")

        return FunctionResponsePart(
            name = name,
            response = buildJsonObject {
                put("error", errorMessage.ifEmpty { "Unknown error" })
            }
        )
    }

    companion object {
        private const val TAG = "HomeAssistantRepository"
    }
}
