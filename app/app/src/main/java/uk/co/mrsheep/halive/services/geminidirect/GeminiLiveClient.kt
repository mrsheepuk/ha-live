package uk.co.mrsheep.halive.services.geminidirect

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import uk.co.mrsheep.halive.services.geminidirect.protocol.ServerMessage
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for Gemini Live API
 * Manages connection, message serialization/deserialization, and event emission
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : WebSocketListener() {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val API_ENDPOINT_V1ALPHA = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val API_ENDPOINT_V1BETA = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MESSAGE_QUEUE_CAPACITY = 512
        private const val CONNECTION_TIMEOUT_MS = 5000L
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // Infinite for bidirectional WebSocket
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val messageFlow = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = MESSAGE_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private var isConnected = false
    private val connectionMutex = Mutex()

    // Deferred to signal when the WebSocket connection is actually open
    private var connectionDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Expose the message flow for consumers
     */
    fun messages(): Flow<ServerMessage> = messageFlow

    /**
     * Establish WebSocket connection to Gemini Live API.
     *
     * This function initiates the WebSocket connection and waits for the onOpen callback
     * to be called before returning. It will timeout if the connection doesn't establish
     * within CONNECTION_TIMEOUT_MS.
     *
     * @return true if connection succeeded, false if it failed or timed out
     */
    suspend fun connect(): Boolean {
        return connectionMutex.withLock {
            try {
                if (isConnected) {
                    Log.d(TAG, "Already connected")
                    return@withLock true
                }

                Log.d(TAG, "Connecting to Gemini Live API...")

                // Create a new deferred for this connection attempt
                connectionDeferred = CompletableDeferred()

                val url = "$API_ENDPOINT_V1BETA?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .build()

                // Initiate the WebSocket connection (asynchronous)
                webSocket = httpClient.newWebSocket(request, this@GeminiLiveClient)
                Log.d(TAG, "WebSocket connection initiated, waiting for onOpen...")

                // Wait for onOpen() to complete the deferred, or timeout
                val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                    connectionDeferred?.await()
                } ?: false

                if (connected) {
                    Log.d(TAG, "WebSocket connected successfully")
                    isConnected = true
                    true
                } else {
                    Log.e(TAG, "WebSocket connection timed out after ${CONNECTION_TIMEOUT_MS}ms")
                    webSocket?.close(1000, "Connection timeout")
                    webSocket = null
                    isConnected = false
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                webSocket?.close(1000, "Connection error")
                webSocket = null
                isConnected = false
                false
            } finally {
                connectionDeferred = null
            }
        }
    }

    /**
     * Send a message to the server
     */
    suspend fun send(message: String) {
        withContext(Dispatchers.IO) {
            if (webSocket == null) {
                Log.e(TAG, "WebSocket not connected, cannot send message")
                return@withContext
            }

            try {
                webSocket!!.send(message)
                Log.d(TAG, "Sent message: ${message.take(100)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    /**
     * Close the WebSocket connection
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                try {
                    webSocket?.close(1000, "Client closing")
                    webSocket = null
                    isConnected = false
                    Log.d(TAG, "WebSocket closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing WebSocket", e)
                }
            }
        }
    }

    /**
     * Clean up all resources including WebSocket, coroutine scope, and HTTP client.
     * Call this when the client is no longer needed to prevent resource leaks.
     * After calling cleanup(), this client instance cannot be reused.
     */
    suspend fun cleanup() {
        try {
            // Close WebSocket first
            close()

            // Cancel coroutine scope
            scope.cancel()
            Log.d(TAG, "Coroutine scope cancelled")

            // Shutdown HTTP client resources (shutdownNow for immediate termination)
            httpClient.dispatcher.executorService.shutdownNow()
            httpClient.connectionPool.evictAll()
            Log.d(TAG, "HTTP client resources cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    // --- WebSocketListener Implementation ---

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "WebSocket opened")

        // Signal that the connection is ready FIRST (before acquiring mutex)
        // This prevents deadlock with connect() which holds the mutex while waiting
        connectionDeferred?.complete(true)
        super.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d(TAG, "Received message: ${text.take(200)}...")

        // Parse the JSON and deserialize to ServerMessage
        try {
            val message = json.decodeFromString(ServerMessage.serializer(), text)
            scope.launch {
                messageFlow.emit(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize message", e)
        }
        super.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(TAG, "Received binary message of size ${bytes.size}")
        // TODO: Check what type of binary data we've got!
        // For now, just coerce to string (feels wrong but let's see)
        try {
            val str = bytes.string(Charset.defaultCharset())
            Log.d(TAG, "Received bytes: $str")
            val message = json.decodeFromString(ServerMessage.serializer(), str)
            Log.d(TAG, "Received JSON $message")
            scope.launch {
                messageFlow.emit(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize message", e)
        }
        super.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "WebSocket closing:\ncode=$code\nreason=$reason")
        // If we're still waiting for connection, mark it as failed
        connectionDeferred?.complete(false)
//        webSocket.close(1000, null)
        super.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "WebSocket closed: code=$code, reason=$reason")
        scope.launch {
            connectionMutex.withLock {
                isConnected = false
            }
        }
        super.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val responseInfo = response?.let {
            "Response code: ${it.code}, message: ${it.message}, body: ${it.body?.string()}"
        } ?: "No response"

        Log.e(TAG, "WebSocket error - $responseInfo", t)
        Log.e(TAG, "Exception details: ${t.javaClass.simpleName}: ${t.message}")
        Log.e(TAG, "Stack trace: ${t.stackTraceToString()}")

        // Signal connection failure FIRST (before acquiring mutex)
        // This prevents deadlock with connect() which holds the mutex while waiting
        connectionDeferred?.complete(false)

        // Then update isConnected under mutex
        scope.launch {
            connectionMutex.withLock {
                isConnected = false
            }
        }
    }
}
