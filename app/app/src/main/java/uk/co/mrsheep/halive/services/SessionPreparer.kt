package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.serialization.json.Json
import uk.co.mrsheep.halive.core.AppLogger
import uk.co.mrsheep.halive.core.LogEntry
import uk.co.mrsheep.halive.core.Profile
import uk.co.mrsheep.halive.core.SystemPromptConfig
import uk.co.mrsheep.halive.core.ToolFilterMode
import uk.co.mrsheep.halive.services.mcp.McpTool
import uk.co.mrsheep.halive.services.mcp.McpToolsListResult
import uk.co.mrsheep.halive.services.conversation.ConversationService

/**
 * Encapsulates the heavy initialization logic for preparing a conversation session.
 * Extracts all logic from MainViewModel.initializeGemini() for better separation of concerns.
 *
 * This class:
 * - Fetches raw MCP tools
 * - Applies profile-based tool filtering
 * - Renders Jinja2 templates from the HA API
 * - Fetches live context if enabled
 * - Builds the final system prompt with all sections
 * - Initializes the conversation service with tools and prompt
 */
class SessionPreparer(
    private val toolExecutor: ToolExecutor,
    private val haApiClient: HomeAssistantApiClient,
    private val logger: AppLogger
) {
    companion object {
        private const val TAG = "SessionPreparer"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Main entry point: prepares and initializes the conversation session.
     *
     * @param profile The active profile (may be null to use defaults)
     * @param conversationService The conversation service to initialize with tools and prompt
     * @param defaultSystemPrompt Fallback system prompt if profile is null
     * @throws Exception on any failure (caller handles state transitions)
     */
    suspend fun prepareAndInitialize(
        profile: Profile,
        conversationService: ConversationService,
    ) {
        val timestamp = createTimestamp()

        try {
            // Fetch raw MCP tools and apply filtering
            val (filteredTools, toolNames, totalToolCount) = fetchAndFilterTools(profile)

            // Create McpToolsListResult with filtered tools
            val mcpToolsResult = McpToolsListResult(filteredTools ?: emptyList())

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
            logger.addLogEntry(
                LogEntry(
                    timestamp = timestamp,
                    toolName = "System Startup",
                    parameters = "Model: $model, Voice: $voice",
                    success = true,
                    result = "$toolsSection\n\nGenerated System Prompt:\n\n$systemPrompt"
                )
            )

            val transcriptor: ((String?, String?) -> Unit)? =
                if (profile?.enableTranscription == true) {
                    { userTranscript: String?, modelTranscript: String? ->
                        if (userTranscript != null) {
                            logger.addLogEntry(
                                LogEntry(
                                    timestamp = timestamp,
                                    toolName = "🎤 User",
                                    parameters = "",
                                    success = true,
                                    result = userTranscript
                                )
                            )
                        }
                        if (modelTranscript != null) {
                            logger.addLogEntry(
                                LogEntry(
                                    timestamp = timestamp,
                                    toolName = "🔊 Model",
                                    parameters = "",
                                    success = true,
                                    result = modelTranscript
                                )
                            )
                        }
                    }
                } else {
                    null
                }

            // Initialize the conversation service with tools and prompt
            conversationService.initialize(
                mcpToolsResult.tools,
                systemPrompt,
                model,
                voice,
                toolExecutor,
                transcriptor
            )

        } catch (e: Exception) {
            // Log initialization error to tool log
            logger.addLogEntry(
                LogEntry(
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
        val tools = toolExecutor.getTools()

        val totalToolCount = tools?.size ?: 0

        // Apply profile-based tool filtering
        val filteredTools = tools.let { result ->
            when (profile?.toolFilterMode) {
                ToolFilterMode.SELECTED -> {
                    val selected = profile.selectedToolNames
                    val available = result.filter { it.name in selected }

                    // Log warning if some selected tools are missing from HA
                    val missing = selected - available.map { it.name }.toSet()
                    if (missing.isNotEmpty()) {
                        logger.addLogEntry(
                            LogEntry(
                                timestamp = timestamp,
                                toolName = "System Startup",
                                parameters = "Tool Filtering",
                                success = false,
                                result = "Selected tools not available in Home Assistant: ${
                                    missing.joinToString(
                                        ", "
                                    )
                                }"
                            )
                        )
                    }

                    available
                }

                else -> tools // ToolFilterMode.ALL or null
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
            val response = toolExecutor.callTool("GetLiveContext", emptyMap())
            response.let { result ->
                result.content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch live context: ${e.message}")
            // Log the error to the tool log
            logger.addLogEntry(
                LogEntry(
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
        profile: Profile,
        renderedBgInfo: String,
        liveContext: String,
    ): String {
        return if (profile.includeLiveContext && liveContext.isNotEmpty()) {
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
    }
}
