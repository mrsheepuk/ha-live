package uk.co.mrsheep.halive.services

import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.serialization.json.JsonElement
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult

interface ToolExecutor {
    suspend fun getTools(): List<McpTool>
    suspend fun callTool(
        name: String,
        arguments: Map<String, JsonElement>
    ): ToolCallResult
}
