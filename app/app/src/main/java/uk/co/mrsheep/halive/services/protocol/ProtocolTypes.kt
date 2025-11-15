package uk.co.mrsheep.halive.services.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonClassDiscriminator

// --- Part (text and inlineData variants) ---

@Serializable
@JsonClassDiscriminator("") // Suppress the "type" field - discriminator is implicit from field presence
sealed class Part {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Part()

    @Serializable
    @SerialName("inlineData")
    data class InlineDataPart(
        @SerialName("inline_data")
        val inlineData: InlineData
    ) : Part()
}

// --- InlineData (for embedding binary data like audio) ---

@Serializable
data class InlineData(
    @SerialName("mime_type")
    val mimeType: String,
    val data: String // base64-encoded data
)

// --- MediaChunk (for streaming audio) ---

@Serializable
data class MediaChunk(
    @SerialName("mime_type")
    val mimeType: String,
    val data: String // base64-encoded audio bytes
)

// --- SystemInstruction (system prompt) ---

@Serializable
data class SystemInstruction(
    val parts: List<Part>
)

// --- Tool definitions ---

@Serializable
data class ToolDeclaration(
    @SerialName("function_declarations")
    val functionDeclarations: List<FunctionDeclaration>
)

@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Schema? = null
)

// --- Schema for tool parameters ---
// Can be a simple object or use JsonObject for flexibility

@Serializable
data class Schema(
    val type: String? = null,
    val properties: Map<String, JsonElement>? = null,
    val required: List<String>? = null,
    val description: String? = null,
    val enum: List<String>? = null,
    val items: Schema? = null,
    @SerialName("additionalProperties")
    val additionalProperties: JsonElement? = null
)
