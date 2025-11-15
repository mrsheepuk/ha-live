package uk.co.mrsheep.halive.services.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.Json

// --- Server Messages (received from Gemini Live API) ---

/**
 * Sealed class for polymorphic server messages
 * Uses custom deserializer to detect message type based on present key
 */
@Serializable(with = ServerMessageSerializer::class)
sealed class ServerMessage {
    @Serializable
    data class SetupComplete(val empty: String = "") : ServerMessage()

    @Serializable
    data class Content(
        @SerialName("server_content")
        val serverContent: ServerContent
    ) : ServerMessage()

    @Serializable
    data class ToolCall(
        @SerialName("tool_call")
        val toolCall: ToolCallData
    ) : ServerMessage()

    @Serializable
    data class ToolCallCancellation(
        @SerialName("tool_call_cancellation")
        val toolCallCancellation: CancellationData
    ) : ServerMessage()
}

// --- ServerContent structure ---

@Serializable
data class ServerContent(
    @SerialName("model_turn")
    val modelTurn: ModelTurn? = null,
    @SerialName("turn_complete")
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    @SerialName("grounding_metadata")
    val groundingMetadata: JsonElement? = null
)

@Serializable
data class ModelTurn(
    val parts: List<ServerPart>? = null
)

/**
 * Server-side Part (can contain text, audio, or other inline data)
 */
@Serializable
sealed class ServerPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ServerPart()

    @Serializable
    @SerialName("inlineData")
    data class InlineDataPart(
        @SerialName("inline_data")
        val inlineData: InlineData
    ) : ServerPart()
}

// --- Tool Call Types ---

@Serializable
data class ToolCallData(
    @SerialName("function_calls")
    val functionCalls: List<FunctionCall>? = null
)

@Serializable
data class FunctionCall(
    val id: String,
    val name: String,
    val args: JsonElement? = null
)

@Serializable
data class CancellationData(
    val id: String? = null
)

// --- Custom Serializer for polymorphic ServerMessage ---

object ServerMessageSerializer : KSerializer<ServerMessage> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ServerMessage") {
        element<JsonElement>("raw")
    }

    override fun deserialize(decoder: Decoder): ServerMessage {
        val jsonDecoder = decoder as JsonDecoder
        val jsonElement = jsonDecoder.decodeJsonElement()

        return when {
            jsonElement is JsonObject && jsonElement.containsKey("setupComplete") -> {
                ServerMessage.SetupComplete()
            }

            jsonElement is JsonObject && jsonElement.containsKey("serverContent") -> {
                val serverContent = jsonElement.jsonObject["serverContent"]?.let { content ->
                    json.decodeFromJsonElement(ServerContent.serializer(), content)
                } ?: ServerContent()
                ServerMessage.Content(serverContent)
            }

            jsonElement is JsonObject && jsonElement.containsKey("toolCall") -> {
                val toolCall = jsonElement.jsonObject["toolCall"]?.let { call ->
                    json.decodeFromJsonElement(ToolCallData.serializer(), call)
                } ?: ToolCallData()
                ServerMessage.ToolCall(toolCall)
            }

            jsonElement is JsonObject && jsonElement.containsKey("toolCallCancellation") -> {
                val cancellation = jsonElement.jsonObject["toolCallCancellation"]?.let { cancel ->
                    json.decodeFromJsonElement(CancellationData.serializer(), cancel)
                } ?: CancellationData()
                ServerMessage.ToolCallCancellation(cancellation)
            }

            else -> {
                throw IllegalArgumentException("Unknown server message type: $jsonElement")
            }
        }
    }

    override fun serialize(encoder: Encoder, value: ServerMessage) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonElement = when (value) {
            is ServerMessage.SetupComplete -> {
                JsonObject(mapOf("setupComplete" to JsonPrimitive(true)))
            }

            is ServerMessage.Content -> {
                JsonObject(mapOf(
                    "serverContent" to json.encodeToJsonElement(ServerContent.serializer(), value.serverContent)
                ))
            }

            is ServerMessage.ToolCall -> {
                JsonObject(mapOf(
                    "toolCall" to json.encodeToJsonElement(ToolCallData.serializer(), value.toolCall)
                ))
            }

            is ServerMessage.ToolCallCancellation -> {
                JsonObject(mapOf(
                    "toolCallCancellation" to json.encodeToJsonElement(CancellationData.serializer(), value.toolCallCancellation)
                ))
            }
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}
