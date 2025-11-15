package uk.co.mrsheep.halive.services

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import uk.co.mrsheep.halive.services.protocol.ServerMessage
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
        private const val API_ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val MESSAGE_QUEUE_CAPACITY = 64
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
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private var isConnected = false
    private val connectionMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Expose the message flow for consumers
     */
    fun messages(): Flow<ServerMessage> = messageFlow

    /**
     * Establish WebSocket connection to Gemini Live API
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            try {
                if (isConnected) {
                    Log.d(TAG, "Already connected")
                    return@withContext true
                }

                Log.d(TAG, "Connecting to Gemini Live API...")

                val url = "$API_ENDPOINT?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .build()

                webSocket = httpClient.newWebSocket(request, this@GeminiLiveClient)

                // Wait for connection to establish
                isConnected = true
                Log.d(TAG, "WebSocket connection initiated")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                isConnected = false
                false
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
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    // --- WebSocketListener Implementation ---

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.d(TAG, "WebSocket opened")
        scope.launch {
            connectionMutex.withLock {
                isConnected = true
            }
        }
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
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        Log.d(TAG, "Received binary message of size ${bytes.size}")
        // Binary frames are not typically used in Gemini Live protocol
        // (audio is sent as base64-encoded strings in JSON)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
        scope.launch {
            connectionMutex.withLock {
                isConnected = false
            }
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        Log.e(TAG, "WebSocket error", t)
        scope.launch {
            connectionMutex.withLock {
                isConnected = false
            }
        }
    }
}
