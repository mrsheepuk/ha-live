package uk.co.mrsheep.halive.services

import android.util.Log
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.SystemPromptConfig
import uk.co.mrsheep.halive.core.ToolFilterMode
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.McpToolsListResult
import uk.co.mrsheep.halive.services.ToolExecutor
import uk.co.mrsheep.halive.ui.ToolCallLog

/**
 * Encapsulates the heavy initialization logic for preparing a Gemini session.
 * Extracts all logic from MainViewModel.initializeGemini() for better separation of concerns.
 *
 * This class:
 * - Fetches raw MCP tools
 * - Applies profile-based tool filtering
 * - Renders Jinja2 templates from the HA API
 * - Fetches live context if enabled
 * - Builds the final system prompt with all sections
 * - Initializes the Gemini model with tools and prompt
 */
class GeminiSessionPreparer(
    private val mcpClient: McpClientManager,
    private val haApiClient: HomeAssistantApiClient,
    private val toolExecutor: ToolExecutor,
    private val onLogEntry: (ToolCallLog) -> Unit
) {
    companion object {
        private const val TAG = "GeminiSessionPreparer"
    }

    /**
     * Main entry point: prepares and initializes the Gemini session.
     *
     * @param profile The active profile (may be null to use defaults)
     * @param geminiService The Gemini service to initialize with tools and prompt
     * @param defaultSystemPrompt Fallback system prompt if profile is null
     * @throws Exception on any failure (caller handles state transitions)
     */
    suspend fun prepareAndInitialize(
        profile: Profile?,
        geminiService: GeminiService,
        defaultSystemPrompt: String
    ) {
        val timestamp = createTimestamp()

        try {
            // Fetch raw MCP tools and apply filtering
            val (filteredTools, toolNames, totalToolCount) = fetchAndFilterTools(profile)

            // Transform filtered tools to Gemini format
            val tools = filteredTools?.let {
                GeminiMCPToolTransformer.transform(
                    McpToolsListResult(it)
                )
            } ?: emptyList()

            // Render background info template if present
            val renderedBackgroundInfo = renderBackgroundInfo(profile)

            // Fetch live context if enabled
            val liveContextText = if (profile?.includeLiveContext == true) {
                fetchLiveContext(timestamp)
            } else {
                ""
            }

            // Build the final system prompt with all sections
            val systemPrompt = buildSystemPrompt(
                profile = profile,
                renderedBgInfo = renderedBackgroundInfo,
                liveContext = liveContextText,
                defaultSystemPrompt = defaultSystemPrompt
            )

            // Extract model and voice from profile or use defaults
            val model = profile?.model ?: SystemPromptConfig.DEFAULT_MODEL
            val voice = profile?.voice ?: SystemPromptConfig.DEFAULT_VOICE

            // Build tools section for logging
            val filterInfo = if (profile?.toolFilterMode == ToolFilterMode.SELECTED) {
                "Filter Mode: SELECTED (${toolNames.size}/$totalToolCount tools enabled)"
            } else {
                "Filter Mode: ALL"
            }

            val toolsSection = if (toolNames.isNotEmpty()) {
                "$filterInfo\nAvailable Tools (${toolNames.size}):\n" +
                    toolNames.joinToString("\n") { "- $it" }
            } else {
                "$filterInfo\nNo tools available"
            }

            // Log the full generated system prompt
            onLogEntry(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "Model: $model, Voice: $voice",
                    success = true,
                    result = "$toolsSection\n\nGenerated System Prompt:\n\n$systemPrompt"
                )
            )

            // Initialize the Gemini model
            geminiService.initializeModel(tools, systemPrompt, model, voice)

        } catch (e: Exception) {
            // Log initialization error to tool log
            onLogEntry(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "Gemini Initialization",
                    success = false,
                    result = "Failed to initialize Gemini: ${e.message}\n${e.stackTraceToString()}"
                )
            )
            // Throw exception for caller to handle
            throw e
        }
    }

    /**
     * Creates a formatted timestamp string.
     */
    private fun createTimestamp(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
    }

    /**
     * Fetches raw MCP tools and applies profile-based filtering.
     *
     * @param profile The active profile
     * @return Triple of (filtered tools list, sorted tool names for logging, total tool count)
     */
    private suspend fun fetchAndFilterTools(profile: Profile?): Triple<List<McpTool>?, List<String>, Int> {
        val timestamp = createTimestamp()

        // Fetch raw MCP tools
        val mcpToolsResult = mcpClient.getTools()

        val totalToolCount = mcpToolsResult?.tools?.size ?: 0

        // Apply profile-based tool filtering
        val filteredTools = mcpToolsResult?.let { result ->
            when (profile?.toolFilterMode) {
                ToolFilterMode.SELECTED -> {
                    val selected = profile.selectedToolNames
                    val available = result.tools.filter { it.name in selected }

                    // Log warning if some selected tools are missing from HA
                    val missing = selected - available.map { it.name }.toSet()
                    if (missing.isNotEmpty()) {
                        onLogEntry(
                            ToolCallLog(
                                timestamp = timestamp,
                                toolName = "System Startup",
                                parameters = "Tool Filtering",
                                success = false,
                                result = "Selected tools not available in Home Assistant: ${missing.joinToString(", ")}"
                            )
                        )
                    }

                    available
                }
                else -> result.tools // ToolFilterMode.ALL or null
            }
        }

        // Extract tool names for logging (use FILTERED tools, not all MCP tools!)
        val toolNames = filteredTools
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        return Triple(filteredTools, toolNames, totalToolCount)
    }

    /**
     * Renders the background info template via Home Assistant API if present.
     *
     * @param profile The active profile
     * @return Rendered template string, or empty string if no template
     * @throws Exception if template rendering fails
     */
    private suspend fun renderBackgroundInfo(profile: Profile?): String {
        return if (profile?.backgroundInfo?.isNotBlank() == true) {
            // Render template via HA API (throws on error)
            haApiClient.renderTemplate(profile.backgroundInfo)
        } else {
            profile?.backgroundInfo ?: ""
        }
    }

    /**
     * Fetches live context from Home Assistant via the GetLiveContext tool.
     * Gracefully degrades (returns empty string) on error.
     *
     * @param timestamp Current timestamp for logging
     * @return Live context text, or empty string on error
     */
    private suspend fun fetchLiveContext(timestamp: String): String {
        return try {
            // Create a FunctionCallPart for GetLiveContext
            val getLiveContextCall = FunctionCallPart(
                name = "GetLiveContext",
                args = emptyMap()
            )
            val response = toolExecutor.executeTool(getLiveContextCall)
            response.response.let { jsonObj ->
                // Navigate: outer "result" -> inner "result" -> text content
                jsonObj.jsonObject["result"]
                    ?.jsonObject?.get("result")
                    ?.jsonPrimitive?.content ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch live context: ${e.message}")
            // Log the error to the tool log
            onLogEntry(
                ToolCallLog(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "GetLiveContext",
                    success = false,
                    result = "Failed to fetch live context: ${e.message}"
                )
            )
            "" // Continue without live context on error
        }
    }

    /**
     * Builds the final system prompt with all sections.
     *
     * @param profile The active profile
     * @param renderedBgInfo The rendered background info template
     * @param liveContext The live context (may be empty)
     * @param defaultSystemPrompt Fallback system prompt if profile is null
     * @return The complete system prompt
     */
    private fun buildSystemPrompt(
        profile: Profile?,
        renderedBgInfo: String,
        liveContext: String,
        defaultSystemPrompt: String
    ): String {
        return if (profile != null) {
            // Check if we should include live context
            if (profile.includeLiveContext && liveContext.isNotEmpty()) {
                // Build combined prompt with live context in its own section
                """<system_prompt>
${profile.systemPrompt}
</system_prompt>

<personality>
${profile.personality}
</personality>

<background_info>
$renderedBgInfo
</background_info>

<initial_live_context>
$liveContext
</initial_live_context>""".trimIndent()
            } else {
                // Build combined prompt without live context section
                """<system_prompt>
${profile.systemPrompt}
</system_prompt>

<personality>
${profile.personality}
</personality>

<background_info>
$renderedBgInfo
</background_info>""".trimIndent()
            }
        } else {
            // Use default system prompt provided by caller
            defaultSystemPrompt
        }
    }
}
