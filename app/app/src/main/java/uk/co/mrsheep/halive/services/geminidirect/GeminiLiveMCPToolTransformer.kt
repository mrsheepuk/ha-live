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

    // Guards against cyclic or pathologically deep $ref/anyOf chains
    private const val MAX_RESOLUTION_DEPTH = 16

    /**
     * Transforms an MCP input schema into a protocol Schema.
     */
    private fun transformMcpInputSchemaToProtocolSchema(mcpSchema: McpInputSchema): Schema {
        val defs = mcpSchema.defs.orEmpty()

        // Transform properties: Map<String, McpProperty> -> Map<String, JsonElement>
        val transformedProperties = mcpSchema.properties.mapValues { (_, mcpProp) ->
            transformMcpPropertyToJsonElement(mcpProp, defs)
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
     *
     * Gemini's function declaration schema is a limited OpenAPI subset with no
     * $ref or anyOf support, so references are resolved inline against [defs]
     * and unions are collapsed to their first concrete option.
     */
    private fun transformMcpPropertyToJsonElement(
        mcpProp: McpProperty,
        defs: Map<String, McpProperty>,
        depth: Int = 0
    ): JsonElement {
        if (depth > MAX_RESOLUTION_DEPTH) {
            Log.w(TAG, "Schema nesting exceeds $MAX_RESOLUTION_DEPTH levels, falling back to string")
            return buildPropertyJsonElement(type = "string", description = mcpProp.description)
        }

        // Resolve {"$ref": "#/$defs/Name"} against the schema's $defs
        if (mcpProp.ref != null) {
            val resolved = defs[mcpProp.ref.removePrefix("#/\$defs/")]
            if (resolved == null) {
                Log.w(TAG, "Unresolvable \$ref '${mcpProp.ref}', falling back to string")
                return buildPropertyJsonElement(type = "string", description = mcpProp.description)
            }
            return transformMcpPropertyToJsonElement(withDescription(resolved, mcpProp.description), defs, depth + 1)
        }

        // Handle 'anyOf' union types: prefer the first non-null-typed option
        if (!mcpProp.anyOf.isNullOrEmpty()) {
            val option = mcpProp.anyOf.firstOrNull { it.type != "null" } ?: mcpProp.anyOf.first()
            return transformMcpPropertyToJsonElement(withDescription(option, mcpProp.description), defs, depth + 1)
        }

        // Handle array types (items default to string if unspecified - Gemini
        // requires an item schema for arrays)
        if (mcpProp.type == "array") {
            val itemSchema = mcpProp.items?.let {
                transformMcpPropertyToJsonElement(it, defs, depth + 1)
            } ?: buildPropertyJsonElement(type = "string")
            return buildPropertyJsonElement(
                type = "array",
                description = mcpProp.description,
                items = itemSchema
            )
        }

        // Handle nested object types
        if (mcpProp.type == "object" && !mcpProp.properties.isNullOrEmpty()) {
            val nestedProperties = mcpProp.properties.mapValues { (_, nested) ->
                transformMcpPropertyToJsonElement(nested, defs, depth + 1)
            }
            val mapBuilder = mutableMapOf<String, JsonElement>(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(nestedProperties)
            )
            if (!mcpProp.description.isNullOrBlank()) {
                mapBuilder["description"] = JsonPrimitive(mcpProp.description)
            }
            mcpProp.required?.takeIf { it.isNotEmpty() }?.let { required ->
                mapBuilder["required"] = JsonArray(required.map { JsonPrimitive(it) })
            }
            return JsonObject(mapBuilder)
        }

        // Handle simple types. HA emits enum-only nodes with no "type" - treat
        // those (and anything else typeless) as string.
        return buildPropertyJsonElement(
            type = mcpProp.type ?: "string",
            description = mcpProp.description,
            enum = mcpProp.enum,
            minimum = mcpProp.minimum,
            maximum = mcpProp.maximum
        )
    }

    /** Carries an outer node's description onto a resolved/collapsed node that lacks one. */
    private fun withDescription(prop: McpProperty, description: String?): McpProperty =
        if (prop.description == null && description != null) prop.copy(description = description) else prop

    /**
     * Builds a JsonElement schema object from individual schema properties.
     */
    private fun buildPropertyJsonElement(
        type: String?,
        description: String? = null,
        enum: List<String>? = null,
        minimum: Double? = null,
        maximum: Double? = null,
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
            mapBuilder["minimum"] = numberPrimitive(minimum)
        }

        // Add maximum if present
        if (maximum != null) {
            mapBuilder["maximum"] = numberPrimitive(maximum)
        }

        // Add items for arrays
        if (items != null) {
            mapBuilder["items"] = items
        }

        return JsonObject(mapBuilder)
    }

    /** Emits whole numbers without a decimal point (5 rather than 5.0). */
    private fun numberPrimitive(value: Double): JsonPrimitive =
        if (value % 1.0 == 0.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}