package uk.co.mrsheep.halive.services.mcp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import uk.co.mrsheep.halive.core.OAuthTokenManager
import uk.co.mrsheep.halive.services.ToolExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpClientManager(
    private val haBaseUrl: String,
    private val tokenManager: OAuthTokenManager
): ToolExecutor {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // Infinite for SSE
        .build()

    private var eventSource: EventSource? = null
    private var endpoint: String? = null
    private var isInitialized = false
    private val nextRequestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonRpcResponse>>()

    // Coroutine scope for background processing
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Connection state tracking
    private var isConnectionAlive: Boolean = false
    private val reconnectionMutex = Mutex()

    /**
     * Phase 1: Initialize the MCP connection.
     * Opens SSE connection and performs the handshake.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to: \"$haBaseUrl/mcp_server/sse\"")

            // 1. Open SSE connection
            val token = tokenManager.getValidToken()
            val request = Request.Builder()
                .url("$haBaseUrl/mcp_server/sse")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .build()

            val sseListener = McpEventSourceListener()
            eventSource = EventSources.createFactory(client)
                .newEventSource(request, sseListener)

            // Wait for connection to open
            sseListener.waitForConnection()
            endpoint = sseListener.waitForEndpoint()

            // 2. Send initialize request
            val initParams = InitializeParams(
//                protocolVersion = "2024-11-05",
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo(
                    name = "HALiveAndroid",
                    version = "1.0.0"
                )
            )

            val initRequest = JsonRpcRequest(
//                jsonrpc = "2.0",
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

            Log.d(TAG, "MCP initialized with server: ${initResult.serverInfo.name} v${initResult.serverInfo.version}")

            // 3. Send initialized notification
            val initializedNotification = JsonRpcNotification(
//                jsonrpc = "2.0",
                method = "notifications/initialized"
            )
            sendNotification(initializedNotification)

            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MCP connection: ${e.toString()}", e)
            shutdown()
            throw McpException("Failed to initialize MCP connection", e)
        }
    }

    /**
     * Fetch tools from the MCP server.
     */
    override suspend fun getTools(): List<McpTool> = withContext(Dispatchers.IO) {
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
        ).tools
    }

    /**
     * Execute a tool on the MCP server.
     */
    override suspend fun callTool(
        name: String,
        arguments: Map<String, JsonElement>
    ): ToolCallResult = withContext(Dispatchers.IO) {
        require(isInitialized) { "Must call initialize() first" }

        val toolCallParams = ToolCallParams(
            name = name,
            arguments = arguments
        )

        val request = JsonRpcRequest(
            jsonrpc = "2.0",
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
     * Send a notification and wait for it to complete.
     * Notifications have no response, but we wait to ensure proper ordering.
     */
    private suspend fun sendNotification(notification: JsonRpcNotification) {
        val message = json.encodeToString(notification)
        sendMessage(message)
    }

    /**
     * Send a raw message over the SSE connection.
     * If connection is dead, automatically reconnects first.
     * Handles 401 responses by triggering reconnection (fresh token).
     * Note: SSE is unidirectional (server -> client), but MCP uses
     * a technique where we POST each request as a separate HTTP call
     * to the same endpoint, and responses come back via SSE.
     */
    private suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        // Check if we need to reconnect
        if (!isConnectionAlive) {
            reconnectionMutex.withLock {
                // Double-check after acquiring lock (another coroutine might have reconnected)
                if (!isConnectionAlive) {
                    Log.w(TAG, "SSE connection lost, attempting reconnection...")
                    try {
                        resetConnection()  // Clean up old connection (but keep scope/client alive)
                        connect()       // Establish fresh connection
                        Log.i(TAG, "Auto-reconnection successful")
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-reconnection failed", e)
                        throw McpException("Failed to reconnect: ${e.message}", e)
                    }
                }
            }
        }

        // Now send the message over the (possibly fresh) connection
        try {
            Log.d(TAG, "Sending $message")
            // Get fresh token for this request
            val token = tokenManager.getValidToken()
            val request = Request.Builder()
                .url("$haBaseUrl$endpoint")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(message.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                // Handle 401 Unauthorized - trigger reconnection to get fresh token
                if (response.code == 401) {
                    Log.w(TAG, "Received 401 Unauthorized, triggering reconnection for fresh token...")
                    isConnectionAlive = false
                    // This will cause automatic reconnection on next message
                    throw McpException("Unauthorized (401) - will reconnect for fresh token")
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to send message: ${response.code}")
                    throw McpException("Failed to send message: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is McpException) throw e
            Log.e(TAG, "Error sending message", e)
            throw McpException("Error sending message: ${e.message}", e)
        }
    }

    /**
     * Clean up just the connection state without destroying the client.
     * Used during auto-reconnection.
     */
    private fun resetConnection() {
        isInitialized = false
        isConnectionAlive = false
        eventSource?.cancel()
        eventSource = null
        pendingRequests.clear()
        // Note: We do NOT cancel scope or shutdown OkHttpClient - those stay alive
        Log.d(TAG, "MCP connection reset for reconnection")
    }

    /**
     * Phase 3: Gracefully shut down the connection and clean up all resources.
     * This is a final shutdown - the client cannot be reused after this.
     *
     * Note: OkHttp cleanup operations are wrapped in try-catch because they can
     * throw exceptions with HTTPS connections due to SSL/TLS resource cleanup
     * complexity and connection pool race conditions.
     */
    fun shutdown() {
        isInitialized = false
        isConnectionAlive = false
        eventSource?.cancel()
        eventSource = null
        pendingRequests.clear()
        scope.cancel()
        // Clean up OkHttpClient resources - wrapped in try-catch because these
        // can throw with HTTPS connections during SSL/TLS cleanup
        try {
            client.dispatcher.executorService.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down dispatcher executor: ${e.message}")
        }
        try {
            client.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error evicting connection pool: ${e.message}")
        }
        Log.d(TAG, "MCP client shut down")
    }

    /**
     * EventSource listener that processes incoming SSE messages.
     */
    private inner class McpEventSourceListener : EventSourceListener() {
        private val connectionEstablished = CompletableDeferred<Unit>()
        private val endpointSet = CompletableDeferred<String>()

        suspend fun waitForConnection() {
            connectionEstablished.await()
        }

        suspend fun waitForEndpoint(): String {
            return endpointSet.await()
        }

        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.d(TAG, "SSE connection opened, ${response.body.toString()}")
            isConnectionAlive = true
            connectionEstablished.complete(Unit)
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String
        ) {
            Log.d(TAG, "Received event of type ${type} (ID: ${id})")
            when (type) {
                "endpoint" -> {
                    endpointSet.complete(data )
                    Log.d(TAG, "Endpoint set to $data")
                    return
                }
            }

            scope.launch {
                try {
                    // Parse incoming JSON-RPC message
                    val jsonObject = json.parseToJsonElement(data).jsonObject

                    if (jsonObject.containsKey("id") && (jsonObject.containsKey("result") || jsonObject.containsKey("error"))) {
                        // It's a response
                        val response = json.decodeFromString<JsonRpcResponse>(data)
                        pendingRequests.remove(response.id)?.complete(response)
                    } else if (jsonObject.containsKey("method")) {
                        // It's a request or notification from server
                        handleServerMessage(data)
                    }
                } catch (e: Exception) {
                    // It's not a JSON message - likely the channel establishment message, ignore.
                    Log.w(TAG, "Failed to parse message, ignoring: $data", e)
                }
            }
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?
        ) {
            Log.e(TAG, "SSE connection failed", t)
            isConnectionAlive = false
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
            Log.d(TAG, "SSE connection closed")
            isConnectionAlive = false
            
            // Fail pending requests from old connection
            // They can retry and will trigger auto-reconnection
            pendingRequests.values.forEach {
                it.completeExceptionally(McpException("Connection closed, will auto-reconnect on retry"))
            }
            pendingRequests.clear()
        }
    }

    /**
     * Handle incoming requests/notifications from server.
     */
    private fun handleServerMessage(data: String) {
        // TODO: Handle server-initiated requests (e.g., sampling)
        // TODO: Handle notifications (e.g., tools/listChanged)
        Log.d(TAG, "Received server message: $data")
    }

    companion object {
        private const val TAG = "McpClientManager"
    }
}

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)
