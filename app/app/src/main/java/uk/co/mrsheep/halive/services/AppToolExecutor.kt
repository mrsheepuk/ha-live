package uk.co.mrsheep.halive.services

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.ToolCallResult
import uk.co.mrsheep.halive.ui.UiState

data class LocalTool(
    val definition: McpTool,
    val execute: ((name: String,
                  arguments: Map<String, JsonElement>
    ) -> ToolCallResult)
)

class AppToolExecutor(
    private val toolExecutor: ToolExecutor,
    private val logger: AppLogger,
    private val setUIState: ((uiState: UiState) -> Unit),
    private val localTools: Map<String, LocalTool>
) : ToolExecutor {

    override suspend fun callTool(
        name: String,
        arguments: Map<String, JsonElement>
    ): ToolCallResult {
        setUIState(UiState.ExecutingAction(name))
        val timestamp = createTimestamp()

        try {
            // Check if this is a local tool and execute that instead
            val result = if (localTools[name] != null) {
                localTools[name]!!.execute(name, arguments)
            } else {
                toolExecutor.callTool(name, arguments)
            }
            val resultText = result.content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")
            val success = result.isError != true
            logger.addLogEntry(
                LogEntry(
                    timestamp = timestamp,
                    toolName = name,
                    parameters = arguments.toString(),
                    success = success,
                    result = resultText
                )
            )
            logger.addToolCallToTranscript(
                toolName = name,
                parameters = arguments.toString(),
                success = success,
                result = resultText
            )
            return result
        } catch (e: Exception) {
            // Log failed call
            val errorResult = "Exception: ${e.message}"
            logger.addLogEntry(
                LogEntry(
                    timestamp = timestamp,
                    toolName = name,
                    parameters = arguments.toString(),
                    success = false,
                    result = errorResult
                )
            )
            logger.addToolCallToTranscript(
                toolName = name,
                parameters = arguments.toString(),
                success = false,
                result = errorResult
            )
            throw e
        } finally {
            setUIState(UiState.ChatActive)
        }
    }

    override suspend fun getTools(): List<McpTool> {
        val tools = toolExecutor.getTools().toMutableList()
        localTools.forEach { (_, value) -> tools.add(value.definition) }
        return tools
    }

    private fun createTimestamp(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
    }
}