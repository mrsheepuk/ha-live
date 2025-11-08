package uk.co.mrsheep.halive.services

import android.util.Log
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import uk.co.mrsheep.halive.services.mcp.*

object GeminiMCPToolTransformer {

    private const val TAG = "GeminiMCPToolTransformer"

    /**
     * Transforms MCP tools result into Gemini Tool format.
     */
    fun transform(mcpToolsResult: McpToolsListResult): List<Tool> {
        Log.d(TAG, "Transforming ${mcpToolsResult.tools.size} MCP tools to Gemini format")

        // Transform each McpTool into a Gemini FunctionDeclaration
        val functionDeclarations = mcpToolsResult.tools.map { mcpTool ->
            transformMcpToGeminiFunctionDeclaration(mcpTool)
        }

        // Return a single Tool containing all function declarations
        return listOf(Tool.functionDeclarations(functionDeclarations = functionDeclarations))
    }

    private fun transformMcpToGeminiFunctionDeclaration(mcpTool: McpTool): FunctionDeclaration {
        // Transform the properties to Schema objects
        val geminiProperties = mcpTool.inputSchema.properties.mapValues { (propName, mcpProp) ->
            transformMcpPropertyToSchema(mcpProp)
        }

        // Compile the list of optional properties: all properties NOT in the required list
        val requiredProps = mcpTool.inputSchema.required ?: emptyList()
        val optionalProps = mcpTool.inputSchema.properties.keys.filter { propName ->
            propName !in requiredProps
        }

        // Create the function declaration using the required field from MCP
        return FunctionDeclaration(
            name = mcpTool.name,
            description = mcpTool.description.ifEmpty { "No description provided" },
            parameters = geminiProperties,
            optionalParameters = optionalProps,
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
}
