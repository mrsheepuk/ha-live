package uk.co.mrsheep.halive.services.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- MCP Data Models (Based on Real HA Response) ---

// Represents the full tools/list response
@Serializable
data class McpToolsListResult(
    val tools: List<McpTool>
)

@Serializable
data class McpTool(
    val name: String, // e.g., "HassTurnOn", "HassLightSet", "GetLiveContext"
    val description: String, // e.g., "Turns on/opens/presses a device or entity..."
    val inputSchema: McpInputSchema
)

@Serializable
data class McpInputSchema(
    val type: String = "object", // Always "object"
    val properties: Map<String, McpProperty> = emptyMap(), // e.g., "name" -> { "type": "string" }
    val required: List<String>? = null, // Optional, only present on some tools
    // Shared definitions referenced via {"$ref": "#/$defs/Name"} - emitted by
    // Home Assistant since voluptuous-openapi 0.4.0
    @SerialName("\$defs") val defs: Map<String, McpProperty>? = null
)

/**
 * A JSON Schema node describing a tool parameter.
 *
 * Recursive: the same shape is used for array items, anyOf union options,
 * nested object properties and $defs entries. Every field is optional because
 * Home Assistant emits partial nodes - e.g. a bare {"$ref": "#/$defs/Name"}
 * or an enum-only option with no "type".
 */
@Serializable
data class McpProperty(
    val type: String? = null, // "string", "integer", "array", etc.
    val description: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val enum: List<String>? = null,
    val items: McpProperty? = null, // For array types
    val anyOf: List<McpProperty>? = null, // For union types (e.g., HassSetVolumeRelative)
    val properties: Map<String, McpProperty>? = null, // For nested object types
    val required: List<String>? = null, // For nested object types
    @SerialName("\$ref") val ref: String? = null // Reference into the schema's $defs
)
