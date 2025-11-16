package uk.co.mrsheep.halive.services.geminifirebase

import android.util.Log
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import uk.co.mrsheep.halive.services.mcp.McpProperty
import uk.co.mrsheep.halive.services.mcp.McpTool

object FirebaseMCPToolTransformer {

    private const val TAG = "GeminiMCPToolTransformer"

    /**
     * Transforms MCP tools result into Gemini Tool format.
     */
    fun transform(tools: List<McpTool>): List<Tool> {
        Log.d(TAG, "Transforming ${tools.size} MCP tools to Gemini format")

        // Transform each McpTool into a Gemini FunctionDeclaration
        val functionDeclarations = tools.map { mcpTool ->
            transformMcpToGeminiFunctionDeclaration(mcpTool)
        }

        // Return a single Tool containing all function declarations
        return listOf(Tool.Companion.functionDeclarations(functionDeclarations = functionDeclarations))
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
                    Schema.Companion.enumeration(firstOption.enum, mcpProp.description, true)
                } else {
                    Schema.Companion.string(mcpProp.description, true)
                }
                "integer", "number" -> Schema.Companion.integer(mcpProp.description, true)
                "boolean" -> Schema.Companion.boolean(mcpProp.description, true)
                else -> Schema.Companion.string(mcpProp.description, true)
            }
        }

        // Handle array types (e.g., device_class with enum items)
        if (mcpProp.type == "array" && mcpProp.items != null) {
            val itemSchema = when (mcpProp.items.type.lowercase()) {
                "string" -> if (mcpProp.items.enum != null) {
                    Schema.Companion.enumeration(mcpProp.items.enum, null, true)
                } else {
                    Schema.Companion.string(null, true)
                }
                "integer", "number" -> Schema.Companion.integer(null, true)
                "boolean" -> Schema.Companion.boolean(null, true)
                else -> Schema.Companion.string(null, true)
            }
            return Schema.Companion.array(itemSchema, mcpProp.description, true)
        }

        // Handle simple types
        return when (mcpProp.type?.lowercase()) {
            "string" -> if (mcpProp.enum != null) {
                Schema.Companion.enumeration(mcpProp.enum, mcpProp.description, true)
            } else {
                Schema.Companion.string(mcpProp.description)
            }
            "integer" -> Schema.Companion.integer(mcpProp.description, true)
            "number" -> Schema.Companion.double(mcpProp.description, true)
            "boolean" -> Schema.Companion.boolean(mcpProp.description, true)
            "object" -> Schema.Companion.obj(emptyMap(), emptyList(), mcpProp.description, true)
            else -> Schema.Companion.string(mcpProp.description, true) // Default fallback
        }
    }
}