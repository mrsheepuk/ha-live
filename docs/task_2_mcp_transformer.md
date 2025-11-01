# Task 1: MCP Discovery & Transformation

## 1. Objective

This task defines "Glue Task #1." Its purpose is to fetch the "menu" of available tools from the Home Assistant MCP Server and transform that "menu" into a format the Gemini API can understand.

This task is **not** responsible for *executing* tools, only *discovering* them.

## 2. Core Components

1.  **`HomeAssistantRepository`:** Will be expanded to include a new function:
    * `suspend fun getTools(): List<Tool>`
2.  **`HAApiService` (Retrofit/Ktor Interface):** Will be expanded to include the GET request for the MCP endpoint.
3.  **Data Models (Data Classes):** We need to create Kotlin data classes that match the JSON structure of the MCP server's output and the Gemini API's input.

## 3. Step 1: Define the Data Structures

We need to model the *source* (MCP JSON) and the *destination* (Gemini `Tool`).

### Source: MCP JSON (Assumed Structure)

We must *assume* the HA MCP Server's output format. Based on the MCP spec, it will likely be a JSON object containing a list of "tools."

```kotlin
// --- Assumed MCP Data Models ---

// Represents the full response from /api/mcp
data class McpServerResponse(
    val tools: List<McpTool>
    // May also include 'resources' or 'prompts', which we will ignore.
)

data class McpTool(
    val name: String, // e.g., "light.turn_on"
    val description: String, // e.g., "Turns on a light"
    val parameters: McpParameters
)

data class McpParameters(
    val type: String, // "object"
    val properties: Map<String, McpParameterProperty>, // e.g., "entity_id" -> { "type": "string" }
    val required: List<String> // e.g., ["entity_id"]
)

data class McpParameterProperty(
    val type: String, // "string", "integer", "boolean", "enum"
    val description: String,
    val enum: List<String>? = null
)
```

### Destination: Gemini `Tool` (Known Structure)

This is the format required by the Firebase AI Logic SDK.

```kotlin
// --- Gemini's Known Data Models ---
// (These are the actual classes from the SDK)

import com.google.firebase.vertexai.type.Tool
import com.google.firebase.vertexai.type.FunctionDeclaration
import com.google.firebase.vertexai.type.Schema
import com.google.firebase.vertexai.type.Type

/*
// This is what we need to build:
Tool(
    functionDeclarations = listOf(
        FunctionDeclaration(
            name = "light.turn_on",
            description = "Turns on a light",
            parameters = Schema(
                type = Type.OBJECT,
                properties = mapOf(
                    "entity_id" to Schema(
                        type = Type.STRING,
                        description = "The entity ID of the light to turn on"
                    )
                ),
                required = listOf("entity_id")
            )
        )
    )
)
*/
```

## 4. Step 2: Implement the Network Call

We'll add the discovery endpoint to our (not-yet-created) `HAApiService`.

```kotlin
// In services/HomeAssistantRepository.kt

// This interface will be built out more in Task 2
interface HAApiService {
    @GET("/api/mcp") // <-- This path is an assumption!
    suspend fun getMcpTools(@Header("Authorization") token: String): McpServerResponse
}
```

## 5. Step 3: Build the Transformer

This is the core "glue" logic for this task. It lives inside the `HomeAssistantRepository`.

```kotlin
// In services/HomeAssistantRepository.kt

import com.google.firebase.vertexai.type.Tool
import com.google.firebase.vertexai.type.FunctionDeclaration
import com.google.firebase.vertexai.type.Schema
import com.google.firebase.vertexai.type.Type

class HomeAssistantRepository(
    private val haApiService: HAApiService,
    private val userBearerToken: String // e.g., "Bearer <LONG_LIVED_TOKEN>"
) {

    /**
     * Fetches tools from HA MCP Server and transforms them for Gemini.
     */
    suspend fun getTools(): List<Tool> {
        val mcpResponse = haApiService.getMcpTools(userBearerToken)
        
        // Use map to transform each McpTool into a Gemini Tool
        return mcpResponse.tools.map { mcpTool ->
            transformMcpToGeminiTool(mcpTool)
        }
    }

    private fun transformMcpToGeminiTool(mcpTool: McpTool): Tool {
        // 1. Transform the properties
        val geminiProperties = mcpTool.parameters.properties.mapValues { (_, mcpProp) ->
            Schema(
                type = mcpProp.type.toGeminiType(),
                description = mcpProp.description,
                enum = mcpProp.enum
            )
        }

        // 2. Create the parameter schema
        val geminiParameters = Schema(
            type = Type.OBJECT,
            properties = geminiProperties,
            required = mcpTool.parameters.required
        )

        // 3. Create the function declaration
        val functionDeclaration = FunctionDeclaration(
            name = mcpTool.name,
            description = mcpTool.description,
            parameters = geminiParameters
        )

        // 4. Wrap in a Tool object
        return Tool(
            functionDeclarations = listOf(functionDeclaration)
        )
    }

    // Helper extension function to map strings to Gemini's enum
    private fun String.toGeminiType(): Type {
        return when (this.lowercase()) {
            "string" -> Type.STRING
            "integer" -> Type.INTEGER
            "number" -> Type.NUMBER
            "boolean" -> Type.BOOLEAN
            else -> Type.STRING // Default fallback
        }
    }

    // ... executeTool function will be added in Task 2 ...
}
```

## 6. Assumptions & Risks

1.  **MCP Endpoint:** We are *assuming* the MCP server exposes its tools at a simple `/api/mcp` GET endpoint. This is a major assumption.
      * **Mitigation:** If it's more complex (e.g., requires a POST or WebSocket), this task's "Network Call" step will need to be revised. The *transformation logic*, however, will remain the same.
2.  **MCP JSON Schema:** We are *assuming* the structure of the JSON.
      * **Mitigation:** The `Mcp*` data classes will need to be adjusted once we have a real sample payload from the HA MCP Server.
