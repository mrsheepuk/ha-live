package uk.co.mrsheep.halive.services.geminidirect

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uk.co.mrsheep.halive.services.mcp.McpInputSchema
import uk.co.mrsheep.halive.services.mcp.McpProperty
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.geminidirect.protocol.FunctionDeclaration
import uk.co.mrsheep.halive.services.geminidirect.protocol.Schema
import uk.co.mrsheep.halive.services.geminidirect.protocol.ToolDeclaration

/**
 * Transforms MCP tool definitions into Gemini Live API protocol format.
 *
 * Similar to GeminiMCPToolTransformer, but outputs protocol.Schema instead of Firebase Schema.
 * The protocol format uses JSON-compatible Schema for proper serialization to the Gemini API.
 */
object GeminiLiveMCPToolTransformer {

    private const val TAG = "GeminiProtocolToolTransformer"

    /**
     * Transforms MCP tools result into Gemini Live API protocol tool format.
     *
     * @param mcpToolsResult The MCP tools list result from the Home Assistant MCP server
     * @return A list of ToolDeclaration objects ready for the Gemini Live API
     */
    fun transform(tools: List<McpTool>): List<ToolDeclaration> {
        Log.d(TAG, "Transforming ${tools.size} MCP tools to protocol format")

        return tools.map { mcpTool ->
            transformMcpToProtocolTool(mcpTool)
        }
    }

    /**
     * Transforms a single MCP tool into a protocol ToolDeclaration.
     */
    private fun transformMcpToProtocolTool(mcpTool: McpTool): ToolDeclaration {
        val functionDeclaration = FunctionDeclaration(
            name = mcpTool.name,
            description = mcpTool.description.ifEmpty { "No description provided" },
            parameters = transformMcpInputSchemaToProtocolSchema(mcpTool.inputSchema)
        )

        return ToolDeclaration(
            functionDeclarations = listOf(functionDeclaration)
        )
    }

    /**
     * Transforms an MCP input schema into a protocol Schema.
     */
    private fun transformMcpInputSchemaToProtocolSchema(mcpSchema: McpInputSchema): Schema {
        // Transform properties: Map<String, McpProperty> -> Map<String, JsonElement>
        val transformedProperties = mcpSchema.properties.mapValues { (_, mcpProp) ->
            transformMcpPropertyToJsonElement(mcpProp)
        }

        return Schema(
            type = "object",
            properties = transformedProperties.takeIf { it.isNotEmpty() },
            required = mcpSchema.required?.takeIf { it.isNotEmpty() },
            description = null
        )
    }

    /**
     * Transforms an MCP property into a JsonElement schema representation.
     * This is used as the value in the properties map.
     */
    private fun transformMcpPropertyToJsonElement(mcpProp: McpProperty): JsonElement {
        // Handle 'anyOf' union types
        if (mcpProp.anyOf != null && mcpProp.anyOf.isNotEmpty()) {
            val firstOption = mcpProp.anyOf.first()
            return buildPropertyJsonElement(
                type = firstOption.type,
                description = mcpProp.description,
                enum = firstOption.enum,
                minimum = firstOption.minimum,
                maximum = firstOption.maximum
            )
        }

        // Handle array types
        if (mcpProp.type == "array" && mcpProp.items != null) {
            val itemSchema = buildPropertyJsonElement(
                type = mcpProp.items.type,
                enum = mcpProp.items.enum
            )
            return buildPropertyJsonElement(
                type = "array",
                description = mcpProp.description,
                items = itemSchema
            )
        }

        // Handle simple types
        return buildPropertyJsonElement(
            type = mcpProp.type,
            description = mcpProp.description,
            enum = mcpProp.enum,
            minimum = mcpProp.minimum,
            maximum = mcpProp.maximum
        )
    }

    /**
     * Builds a JsonElement schema object from individual schema properties.
     */
    private fun buildPropertyJsonElement(
        type: String?,
        description: String? = null,
        enum: List<String>? = null,
        minimum: Int? = null,
        maximum: Int? = null,
        items: JsonElement? = null
    ): JsonElement {
        val mapBuilder = mutableMapOf<String, JsonElement>()

        // Add type
        if (type != null) {
            mapBuilder["type"] = JsonPrimitive(type)
        }

        // Add description if present
        if (description != null && description.isNotBlank()) {
            mapBuilder["description"] = JsonPrimitive(description)
        }

        // Add enum values if present
        if (enum != null && enum.isNotEmpty()) {
            mapBuilder["enum"] = JsonArray(enum.map { JsonPrimitive(it) })
        }

        // Add minimum if present
        if (minimum != null) {
            mapBuilder["minimum"] = JsonPrimitive(minimum)
        }

        // Add maximum if present
        if (maximum != null) {
            mapBuilder["maximum"] = JsonPrimitive(maximum)
        }

        // Add items for arrays
        if (items != null) {
            mapBuilder["items"] = items
        }

        return JsonObject(mapBuilder)
    }
}