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

### Source: MCP JSON (Actual Structure from HA)

Based on the actual response from Home Assistant's MCP server (see [ha-mcp-tools-list.json](examples/ha-mcp-tools-list.json)), we now know the real format:

```kotlin
// --- MCP Data Models (Based on Real HA Response) ---

// Represents the full JSON-RPC response
data class McpJsonRpcResponse(
    val jsonrpc: String, // "2.0"
    val id: Int, // Request ID
    val result: McpResult
)

data class McpResult(
    val tools: List<McpTool>
)

data class McpTool(
    val name: String, // e.g., "HassTurnOn", "HassLightSet", "GetLiveContext"
    val description: String, // e.g., "Turns on/opens/presses a device or entity..."
    val inputSchema: McpInputSchema
)

data class McpInputSchema(
    val type: String, // Always "object"
    val properties: Map<String, McpProperty>, // e.g., "name" -> { "type": "string" }
    val required: List<String>? = null // Optional, only present on some tools
)

data class McpProperty(
    val type: String? = null, // "string", "integer", "array", etc. (can be null if anyOf is present)
    val description: String? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val enum: List<String>? = null,
    val items: McpItems? = null, // For array types
    val anyOf: List<McpAnyOfOption>? = null // For union types (e.g., HassSetVolumeRelative)
)

data class McpItems(
    val type: String, // e.g., "string"
    val enum: List<String>? = null
)

data class McpAnyOfOption(
    val type: String, // e.g., "string", "integer"
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null
)
```

**Key Observations:**
1. **JSON-RPC wrapper:** The response uses JSON-RPC 2.0 format with `jsonrpc`, `id`, and `result` fields
2. **inputSchema not parameters:** The tool parameters are under `inputSchema`, not `parameters`
3. **Complex types:** Properties can have `anyOf` for union types (e.g., volume can be "up"/"down" or -100 to 100)
4. **Array types with items:** Properties like `device_class` are arrays with `items.enum`
5. **Optional required:** The `required` field is not always present (many tools have all optional params)

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

We'll make a JSON-RPC call to the HA MCP server. Note that this is not a simple REST GET - it's a JSON-RPC POST request.

```kotlin
// In services/HomeAssistantRepository.kt

// This interface will be built out more in Task 3
interface HAApiService {
    @POST("/api/mcp") // JSON-RPC endpoint
    @Headers("Content-Type: application/json")
    suspend fun getMcpTools(
        @Header("Authorization") token: String,
        @Body request: JsonRpcRequest
    ): McpJsonRpcResponse
}

// JSON-RPC request structure
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int = 1,
    val method: String, // e.g., "tools/list"
    val params: Map<String, Any>? = null
)
```

**Usage:**
```kotlin
val request = JsonRpcRequest(
    method = "tools/list"
)
val response = haApiService.getMcpTools(userBearerToken, request)
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
        // Build JSON-RPC request
        val request = JsonRpcRequest(method = "tools/list")

        // Make the call
        val mcpResponse = haApiService.getMcpTools(userBearerToken, request)

        // Extract tools from the result wrapper
        val tools = mcpResponse.result.tools

        // Transform each McpTool into a Gemini FunctionDeclaration
        val functionDeclarations = tools.map { mcpTool ->
            transformMcpToGeminiFunctionDeclaration(mcpTool)
        }

        // Return a single Tool containing all function declarations
        return listOf(Tool(functionDeclarations = functionDeclarations))
    }

    private fun transformMcpToGeminiFunctionDeclaration(mcpTool: McpTool): FunctionDeclaration {
        // 1. Transform the properties
        val geminiProperties = mcpTool.inputSchema.properties.mapValues { (_, mcpProp) ->
            transformMcpPropertyToSchema(mcpProp)
        }

        // 2. Create the parameter schema
        val geminiParameters = Schema(
            type = Type.OBJECT,
            properties = geminiProperties,
            required = mcpTool.inputSchema.required ?: emptyList()
        )

        // 3. Create the function declaration
        return FunctionDeclaration(
            name = mcpTool.name,
            description = mcpTool.description.ifEmpty { "No description provided" },
            parameters = geminiParameters
        )
    }

    private fun transformMcpPropertyToSchema(mcpProp: McpProperty): Schema {
        // Handle 'anyOf' union types (e.g., HassSetVolumeRelative's volume_step)
        if (mcpProp.anyOf != null) {
            // For now, pick the first option as the primary type
            // In the future, we might want to document both options in the description
            val firstOption = mcpProp.anyOf.first()
            return Schema(
                type = firstOption.type.toGeminiType(),
                description = mcpProp.description,
                enum = firstOption.enum,
                minimum = firstOption.minimum,
                maximum = firstOption.maximum
            )
        }

        // Handle array types (e.g., device_class with enum items)
        if (mcpProp.type == "array" && mcpProp.items != null) {
            return Schema(
                type = Type.ARRAY,
                description = mcpProp.description,
                items = Schema(
                    type = mcpProp.items.type.toGeminiType(),
                    enum = mcpProp.items.enum
                )
            )
        }

        // Handle simple types
        return Schema(
            type = mcpProp.type?.toGeminiType() ?: Type.STRING,
            description = mcpProp.description,
            enum = mcpProp.enum,
            minimum = mcpProp.minimum,
            maximum = mcpProp.maximum
        )
    }

    // Helper extension function to map strings to Gemini's enum
    private fun String.toGeminiType(): Type {
        return when (this.lowercase()) {
            "string" -> Type.STRING
            "integer" -> Type.INTEGER
            "number" -> Type.NUMBER
            "boolean" -> Type.BOOLEAN
            "array" -> Type.ARRAY
            "object" -> Type.OBJECT
            else -> Type.STRING // Default fallback
        }
    }

    // ... executeTool function will be added in Task 3 ...
}
```

**Key Changes from Original Plan:**
1. **Single Tool, Many Functions:** Gemini prefers a single `Tool` object containing all `FunctionDeclaration`s, rather than a list of single-function Tools
2. **JSON-RPC Unwrapping:** We extract `result.tools` from the response wrapper
3. **Complex Property Handling:**
   - `anyOf` union types: Pick the first option (with a TODO for better handling)
   - Array types with `items.enum`: Properly create nested Schema with items
4. **Empty Descriptions:** Handle tools that have empty descriptions (e.g., "describe_doorbell")
5. **Required Field:** Default to empty list if not present (many HA tools have all optional params)

## 6. Known Complexities & Edge Cases

Now that we have the actual MCP response format, here are the known complexities:

1.  **Union Types (`anyOf`):** Some properties like `HassSetVolumeRelative.volume_step` can be EITHER a string enum ("up"/"down") OR an integer (-100 to 100)
      * **Current Strategy:** Pick the first option in the transformation
      * **Future Enhancement:** Could concatenate both options into the description for better LLM understanding

2.  **Array Properties with Enums:** Properties like `device_class` are arrays of strings with enum constraints
      * **Handled:** The transformer properly creates nested `Schema` with `items` containing the enum

3.  **Optional Everything:** Many tools (like `describe_doorbell`, `GetLiveContext`) have empty or all-optional parameter schemas
      * **Handled:** Default `required` to empty list if not present

4.  **Empty Descriptions:** Some tools have empty description strings
      * **Handled:** Default to "No description provided" to avoid confusing the LLM

5.  **Tool Count:** The example response has 15 tools, but a real HA instance may have dozens or hundreds
      * **Risk:** Large tool lists might hit Gemini's context limits
      * **Mitigation:** Monitor token usage and consider filtering or chunking if needed

6.  **Tool Naming Convention:** HA uses PascalCase names (e.g., `HassTurnOn`, `HassLightSet`) rather than domain.service format
      * **Impact:** This affects Task 3 (execution) - we can't simply split on `.` to get domain/service
      * **Solution:** Task 3 will need a different strategy (likely a mapping table or parsing the tool's semantic meaning)

## 7. Testing Strategy

To validate the transformation logic before integrating with the full app:

1.  **Unit Test:** Create a test that loads `docs/examples/ha-mcp-tools-list.json` and runs it through the transformer
      * Verify all 15 tools are converted
      * Spot-check complex cases (anyOf, arrays, empty descriptions)

2.  **Sample Output:** Manually inspect the generated `Tool` object
      * Print the transformed `FunctionDeclaration` list
      * Verify names, descriptions, and parameter schemas are sensible

3.  **Integration Test:** Once Task 1 (Live API) is complete, test with dummy executor
      * Pass transformed tools to `LiveSession`
      * Verify Gemini can call the functions with appropriate parameters

## 8. Example Transformations

Here's what the transformer should produce for specific tools:

**HassTurnOn (Complex):**
```kotlin
FunctionDeclaration(
    name = "HassTurnOn",
    description = "Turns on/opens/presses a device or entity...",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "name" to Schema(type = Type.STRING),
            "area" to Schema(type = Type.STRING),
            "floor" to Schema(type = Type.STRING),
            "domain" to Schema(
                type = Type.ARRAY,
                items = Schema(type = Type.STRING)
            ),
            "device_class" to Schema(
                type = Type.ARRAY,
                items = Schema(
                    type = Type.STRING,
                    enum = listOf("tv", "speaker", "outlet", ...)
                )
            )
        ),
        required = emptyList() // All optional
    )
)
```

**HassSetVolume (With Constraints):**
```kotlin
FunctionDeclaration(
    name = "HassSetVolume",
    description = "Sets the volume percentage of a media player",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            // ... name, area, floor, domain, device_class ...
            "volume_level" to Schema(
                type = Type.INTEGER,
                description = "The volume percentage of the media player",
                minimum = 0,
                maximum = 100
            )
        ),
        required = emptyList()
    )
)
```

**describe_doorbell (Empty Schema):**
```kotlin
FunctionDeclaration(
    name = "describe_doorbell",
    description = "No description provided",
    parameters = Schema(
        type = Type.OBJECT,
        properties = emptyMap(),
        required = emptyList()
    )
)
```
