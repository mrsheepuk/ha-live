# Task 2: MCP Client (Connection, Discovery & Execution)

## 1. Objective

This task implements the complete MCP client that establishes a persistent SSE (Server-Sent Events) connection to Home Assistant's MCP server. It handles:

1. **Connection Lifecycle**: Opening the SSE connection, performing the MCP initialization handshake, and graceful shutdown
2. **Tool Discovery**: Fetching the "menu" of available tools via JSON-RPC `tools/list` method
3. **Tool Transformation**: Converting MCP tool definitions into Gemini API format
4. **Tool Execution**: Calling tools via JSON-RPC `tools/call` method and returning results

This task delivers the complete MCP client. Task 1 (Gemini Live) will use this client to provide tools to Gemini and execute them when requested.

## 2. Core Components

1.  **`McpClientManager`:** NEW class that manages the SSE connection and MCP protocol lifecycle
    * `suspend fun initialize()`: Opens SSE connection and performs MCP handshake
    * `suspend fun getTools()`: Fetches tools via JSON-RPC `tools/list` method
    * `suspend fun callTool()`: Executes a tool via JSON-RPC `tools/call` method
    * `fun shutdown()`: Gracefully closes the connection
2.  **`HomeAssistantRepository`:** Wraps `McpClientManager` and provides high-level interface
    * `suspend fun getTools(): List<Tool>`: Fetches tools and transforms them for Gemini
    * `suspend fun executeTool()`: Executes a tool and returns the result to Gemini
3.  **Data Models (Data Classes):** Kotlin data classes for MCP JSON-RPC messages and tool definitions

## 3. MCP Connection Lifecycle

Before we can fetch tools, we must establish and initialize the MCP connection following the protocol specification.

### Phase 1: Initialize

The MCP spec requires a handshake before any operations:

```
Client                                    Server
  |                                         |
  |--- initialize request ---------------->|
  |    (protocolVersion, capabilities)     |
  |                                         |
  |<-- initialize response ----------------|
  |    (protocolVersion, capabilities)     |
  |                                         |
  |--- initialized notification ---------->|
  |                                         |
  |    [NOW READY FOR OPERATIONS]          |
```

### Phase 2: Operation

Once initialized, we can:
- Request tools via `tools/list`
- Call tools via `tools/call`
- Receive server notifications (e.g., `tools/listChanged`)

### Phase 3: Shutdown

When the app closes or user disconnects:
- Close the SSE connection
- Clean up resources

## 4. Step 1: Define the Data Structures

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

## 5. Step 2: Implement the SSE Connection

We'll use OkHttp's SSE support to establish a persistent connection to `/api/mcp`.

### Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // OkHttp for SSE
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### JSON-RPC Message Types

```kotlin
// In services/mcp/McpMessages.kt

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
```

### McpClientManager Implementation

```kotlin
// In services/McpClientManager.kt

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class McpClientManager(
    private val haBaseUrl: String,
    private val haToken: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // Infinite for SSE
        .build()

    private var eventSource: EventSource? = null
    private var isInitialized = false
    private val nextRequestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonRpcResponse>>()

    // Coroutine scope for background processing
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Phase 1: Initialize the MCP connection.
     * Opens SSE connection and performs the handshake.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Open SSE connection
            val request = Request.Builder()
                .url("$haBaseUrl/api/mcp")
                .header("Authorization", haToken)
                .header("Accept", "text/event-stream")
                .build()

            val sseListener = McpEventSourceListener()
            eventSource = EventSources.createFactory(client)
                .newEventSource(request, sseListener)

            // Wait for connection to open
            sseListener.waitForConnection()

            // 2. Send initialize request
            val initParams = InitializeParams(
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo(
                    name = "HALiveAndroid",
                    version = "1.0.0"
                )
            )

            val initRequest = JsonRpcRequest(
                id = nextRequestId.getAndIncrement(),
                method = "initialize",
                params = json.encodeToJsonElement(InitializeParams.serializer(), initParams)
            )

            val initResponse = sendAndAwaitResponse(initRequest)

            // Parse and validate response
            if (initResponse.error != null) {
                throw McpException("Initialize failed: ${initResponse.error.message}")
            }

            val initResult = json.decodeFromJsonElement(
                InitializeResult.serializer(),
                initResponse.result!!
            )

            // 3. Send initialized notification
            val initializedNotification = JsonRpcNotification(
                method = "notifications/initialized"
            )
            sendNotification(initializedNotification)

            isInitialized = true
            true
        } catch (e: Exception) {
            shutdown()
            throw McpException("Failed to initialize MCP connection", e)
        }
    }

    /**
     * Fetch tools from the MCP server.
     */
    suspend fun getTools(): McpToolsListResult = withContext(Dispatchers.IO) {
        require(isInitialized) { "Must call initialize() first" }

        val request = JsonRpcRequest(
            id = nextRequestId.getAndIncrement(),
            method = "tools/list",
            params = null
        )

        val response = sendAndAwaitResponse(request)

        if (response.error != null) {
            throw McpException("tools/list failed: ${response.error.message}")
        }

        json.decodeFromJsonElement(
            McpToolsListResult.serializer(),
            response.result!!
        )
    }

    /**
     * Execute a tool on the MCP server.
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any>
    ): ToolCallResult = withContext(Dispatchers.IO) {
        require(isInitialized) { "Must call initialize() first" }

        // Convert arguments to JsonElement
        val jsonArguments = arguments.mapValues { (_, value) ->
            json.encodeToJsonElement(value)
        }

        val toolCallParams = ToolCallParams(
            name = name,
            arguments = jsonArguments
        )

        val request = JsonRpcRequest(
            id = nextRequestId.getAndIncrement(),
            method = "tools/call",
            params = json.encodeToJsonElement(ToolCallParams.serializer(), toolCallParams)
        )

        val response = sendAndAwaitResponse(request, timeout = 60_000) // Longer timeout for tool execution

        if (response.error != null) {
            throw McpException("tools/call failed: ${response.error.message}")
        }

        json.decodeFromJsonElement(
            ToolCallResult.serializer(),
            response.result!!
        )
    }

    /**
     * Send a JSON-RPC request and suspend until response arrives.
     */
    private suspend fun sendAndAwaitResponse(
        request: JsonRpcRequest,
        timeout: Long = 30_000
    ): JsonRpcResponse = withTimeout(timeout) {
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[request.id] = deferred

        val message = json.encodeToString(request)
        sendMessage(message)

        try {
            deferred.await()
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    /**
     * Send a fire-and-forget notification.
     */
    private fun sendNotification(notification: JsonRpcNotification) {
        val message = json.encodeToString(notification)
        sendMessage(message)
    }

    /**
     * Send a raw message over the SSE connection.
     * Note: SSE is unidirectional (server -> client), but MCP uses
     * a technique where we POST each request as a separate HTTP call
     * to the same endpoint, and responses come back via SSE.
     */
    private fun sendMessage(message: String) {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$haBaseUrl/api/mcp")
                    .header("Authorization", haToken)
                    .header("Content-Type", "application/json")
                    .post(message.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw McpException("Failed to send message: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                // Handle send errors
                android.util.Log.e("McpClient", "Failed to send message", e)
            }
        }
    }

    /**
     * Phase 3: Gracefully shut down the connection.
     */
    fun shutdown() {
        isInitialized = false
        eventSource?.cancel()
        eventSource = null
        pendingRequests.clear()
        scope.cancel()
    }

    /**
     * EventSource listener that processes incoming SSE messages.
     */
    private inner class McpEventSourceListener : EventSourceListener() {
        private val connectionEstablished = CompletableDeferred<Unit>()

        suspend fun waitForConnection() {
            connectionEstablished.await()
        }

        override fun onOpen(eventSource: EventSource, response: Response) {
            android.util.Log.d("McpClient", "SSE connection opened")
            connectionEstablished.complete(Unit)
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String
        ) {
            scope.launch {
                try {
                    // Parse incoming JSON-RPC message
                    val jsonObject = json.parseToJsonElement(data).jsonObject

                    if (jsonObject.containsKey("id") && jsonObject.containsKey("result")) {
                        // It's a response
                        val response = json.decodeFromString<JsonRpcResponse>(data)
                        pendingRequests.remove(response.id)?.complete(response)
                    } else if (jsonObject.containsKey("method")) {
                        // It's a request or notification from server
                        handleServerMessage(data)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("McpClient", "Failed to parse message", e)
                }
            }
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?
        ) {
            android.util.Log.e("McpClient", "SSE connection failed", t)
            connectionEstablished.completeExceptionally(
                t ?: McpException("SSE connection failed")
            )

            // Fail all pending requests
            pendingRequests.values.forEach {
                it.completeExceptionally(McpException("Connection lost"))
            }
            pendingRequests.clear()
        }

        override fun onClosed(eventSource: EventSource) {
            android.util.Log.d("McpClient", "SSE connection closed")
        }
    }

    /**
     * Handle incoming requests/notifications from server.
     */
    private fun handleServerMessage(data: String) {
        // TODO: Handle server-initiated requests (e.g., sampling)
        // TODO: Handle notifications (e.g., tools/listChanged)
        android.util.Log.d("McpClient", "Received server message: $data")
    }
}

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

**Key Implementation Details:**

1. **SSE Connection**: Uses OkHttp's EventSource to maintain persistent SSE connection
2. **Request/Response Correlation**: Uses request ID to match responses to pending requests
3. **Bidirectional Communication**: Posts JSON-RPC requests as separate HTTP calls, receives responses via SSE
4. **Timeout Handling**: Uses `withTimeout` for all requests (default 30 seconds)
5. **Graceful Shutdown**: Cancels event source and clears pending requests

## 6. Step 3: Build the Transformer

This is the core "glue" logic for this task. It lives inside the `HomeAssistantRepository`.

```kotlin
// In services/HomeAssistantRepository.kt

import com.google.firebase.vertexai.type.Tool
import com.google.firebase.vertexai.type.FunctionDeclaration
import com.google.firebase.vertexai.type.Schema
import com.google.firebase.vertexai.type.Type

class HomeAssistantRepository(
    private val mcpClient: McpClientManager
) {

    /**
     * Fetches tools from HA MCP Server and transforms them for Gemini.
     */
    suspend fun getTools(): List<Tool> {
        // Fetch tools via MCP
        val mcpToolsResult = mcpClient.getTools()

        // Transform each McpTool into a Gemini FunctionDeclaration
        val functionDeclarations = mcpToolsResult.tools.map { mcpTool ->
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

    /**
     * Executes a tool via MCP and returns the result to Gemini.
     */
    suspend fun executeTool(functionCall: FunctionCallPart): FunctionResponsePart {
        return try {
            // Call the tool via MCP
            val result = mcpClient.callTool(
                name = functionCall.name,
                arguments = functionCall.args
            )

            // Check if it was an error
            if (result.isError == true) {
                createErrorResponse(functionCall.name, result)
            } else {
                createSuccessResponse(functionCall.name, result)
            }
        } catch (e: Exception) {
            // Handle exceptions (network errors, timeouts, etc.)
            FunctionResponsePart(
                name = functionCall.name,
                response = mapOf(
                    "error" to "Exception: ${e.message}"
                )
            )
        }
    }

    private fun createSuccessResponse(
        name: String,
        result: ToolCallResult
    ): FunctionResponsePart {
        // Extract text content from MCP result
        val textContent = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        return FunctionResponsePart(
            name = name,
            response = mapOf(
                "result" to textContent.ifEmpty { "Success" }
            )
        )
    }

    private fun createErrorResponse(
        name: String,
        result: ToolCallResult
    ): FunctionResponsePart {
        val errorMessage = result.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")

        return FunctionResponsePart(
            name = name,
            response = mapOf(
                "error" to errorMessage.ifEmpty { "Unknown error" }
            )
        )
    }
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
      * **Impact:** RESOLVED - Since tool execution goes through MCP `tools/call`, we just pass the name as-is
      * **Solution:** No parsing needed! MCP handles the mapping internally

7.  **SSE Connection Management:**
      * **Challenge:** Must maintain persistent connection throughout app lifecycle
      * **Handled:** Connection opens in `HAGeminiApp.initializeHomeAssistant()`, closes in `shutdown()`
      * **Future:** Add reconnection logic for network interruptions (not in MVP)

8.  **Request/Response Correlation:**
      * **Challenge:** SSE is unidirectional, but we need bidirectional JSON-RPC
      * **Solution:** POST requests to `/api/mcp`, receive responses via SSE using request ID correlation

## 7. Testing Strategy

To validate the connection, tool discovery, and transformation logic:

### Phase 1: Connection Testing
1.  **Manual SSE Test:** Create standalone Android app that just tests MCP connection
      * Verify SSE connection opens successfully
      * Verify initialization handshake completes
      * Log all incoming/outgoing messages
      * Test graceful shutdown

### Phase 2: Tool Discovery Testing
2.  **MCP Integration Test:** Test full cycle with real HA instance
      * Connect to real HA MCP server
      * Fetch tools via `tools/list`
      * Verify JSON parsing works with real data
      * Print tool count and names

### Phase 3: Transformation Testing
3.  **Unit Test:** Create test that loads `docs/examples/ha-mcp-tools-list.json` and transforms it
      * Verify all 15 tools are converted to Gemini format
      * Spot-check complex cases (anyOf, arrays, empty descriptions)
      * Validate no exceptions are thrown

4.  **Sample Output:** Manually inspect generated `Tool` objects
      * Print transformed `FunctionDeclaration` list
      * Verify names, descriptions, and parameter schemas are sensible

### Phase 4: Tool Execution Testing
5.  **MCP Tool Call Test:** Test tool execution with real HA instance
      * Call a simple tool like "HassTurnOn" with valid arguments
      * Verify JSON-RPC `tools/call` request is sent correctly
      * Verify response is parsed correctly
      * Print result content

6.  **Error Handling Test:** Test error scenarios
      * Call a tool with invalid arguments
      * Call a non-existent tool
      * Verify errors are captured and returned properly

### Phase 5: End-to-End Testing
7.  **Integration Test:** Once Task 1 (Live API) is complete
      * Pass transformed tools to `LiveSession`
      * Have Gemini call a tool during conversation
      * Verify `executeTool()` is called correctly
      * Verify result makes it back to Gemini
      * Verify Gemini responds appropriately to the result

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

## 9. Implementation Summary

This task delivers a complete MCP client implementation with three key capabilities:

### What Gets Built:

1. **McpClientManager** (`services/McpClientManager.kt`)
   - Opens persistent SSE connection to `/api/mcp`
   - Performs MCP initialization handshake (initialize → initialized)
   - Sends JSON-RPC requests and correlates responses
   - Fetches tools via `tools/list`
   - Executes tools via `tools/call`
   - Handles graceful shutdown
   - ~550 lines of code

2. **MCP Message Types** (`services/mcp/McpMessages.kt`)
   - Data classes for JSON-RPC messages (request, response, notification, error)
   - Data classes for MCP protocol types (initialize params/result, capabilities, etc.)
   - Data classes for tool definitions (McpTool, McpInputSchema, McpProperty, etc.)
   - Data classes for tool execution (ToolCallParams, ToolCallResult, ToolContent)
   - ~120 lines of code

3. **HomeAssistantRepository** (`services/HomeAssistantRepository.kt`)
   - Wraps McpClientManager
   - Fetches tools via `getTools()` and transforms to Gemini format
   - Executes tools via `executeTool()` and transforms results
   - ~200 lines of code (transformation + execution logic)

### Integration Points:

- **Task 0 (App Skeleton)**: `HAGeminiApp.initializeHomeAssistant()` calls `mcpClient.initialize()`
- **Task 1 (Gemini Live)**:
  - `GeminiService` gets tools via `haRepository.getTools()` and passes to Gemini
  - When Gemini calls a tool, `GeminiService` executes via `haRepository.executeTool()`

### Connection Flow:

```
App Start
    ↓
User Configures HA (URL + Token)
    ↓
HAGeminiApp.initializeHomeAssistant()
    ↓
McpClientManager.initialize()
    ├─ Open SSE connection to /api/mcp
    ├─ Send "initialize" request
    ├─ Wait for "initialize" response
    └─ Send "initialized" notification
    ↓
[Connection Ready - Can fetch tools and call them]
    ↓
HomeAssistantRepository.getTools()
    ├─ Call mcpClient.getTools()
    ├─ Transform to Gemini format
    └─ Return List<Tool>
    ↓
Pass to GeminiService (Task 1)
    ↓
[User talks to Gemini, Gemini decides to call a tool]
    ↓
Gemini returns FunctionCallPart
    ↓
GeminiService calls haRepository.executeTool()
    ↓
HomeAssistantRepository.executeTool()
    ├─ Call mcpClient.callTool(name, args)
    ├─ Transform ToolCallResult to FunctionResponsePart
    └─ Return to GeminiService
    ↓
GeminiService sends FunctionResponsePart back to Gemini
    ↓
Gemini processes result and responds to user
    ↓
App Closes
    ↓
HAGeminiApp.shutdownHomeAssistant()
    ↓
McpClientManager.shutdown()
    └─ Close SSE connection
```

### Key Decisions Made:

1. **OkHttp SSE** over Ktor: Simpler, more focused library
2. **kotlinx.serialization** for JSON: Type-safe, compile-time code generation
3. **POST for requests, SSE for responses**: Standard pattern for bidirectional communication over SSE
4. **Request ID correlation**: ConcurrentHashMap + CompletableDeferred for async request/response matching
5. **Single persistent connection**: Opens on HA config, closes on app shutdown (no reconnection in MVP)
