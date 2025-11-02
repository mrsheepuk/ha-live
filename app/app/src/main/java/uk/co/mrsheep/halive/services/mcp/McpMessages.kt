package uk.co.mrsheep.halive.services.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Base JSON-RPC message types
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String,
    val id: Int,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// MCP-specific message types
@Serializable
data class InitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ClientCapabilities,
    val clientInfo: ClientInfo
)

@Serializable
data class ClientCapabilities(
    val sampling: Map<String, String> = emptyMap(),
    val elicitation: Map<String, String> = emptyMap()
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
    val instructions: String? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: ResourcesCapability? = null,
    val logging: Map<String, String>? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String
)

// Tool execution types
@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: Map<String, JsonElement>? = null
)

@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    val isError: Boolean? = null
)

@Serializable
data class ToolContent(
    val type: String, // "text", "image", "resource"
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)
