package uk.co.mrsheep.halive.services.mcp

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
    val type: String, // Always "object"
    val properties: Map<String, McpProperty>, // e.g., "name" -> { "type": "string" }
    val required: List<String>? = null // Optional, only present on some tools
)

@Serializable
data class McpProperty(
    val type: String? = null, // "string", "integer", "array", etc. (can be null if anyOf is present)
    val description: String? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val enum: List<String>? = null,
    val items: McpItems? = null, // For array types
    val anyOf: List<McpAnyOfOption>? = null // For union types (e.g., HassSetVolumeRelative)
)

@Serializable
data class McpItems(
    val type: String, // e.g., "string"
    val enum: List<String>? = null
)

@Serializable
data class McpAnyOfOption(
    val type: String, // e.g., "string", "integer"
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null
)
