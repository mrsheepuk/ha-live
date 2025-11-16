package uk.co.mrsheep.halive.services.geminidirect.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName

// --- Part (text and inlineData variants) ---


/** Interface representing data sent to and received from requests. */
public interface Part {
}

/** Represents text or string based data sent to and received from requests. */
@Serializable
public class TextPart
constructor(
    public val text: String,
) : Part {
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
data class Content(
    val role: String?,
    val parts: List<TextPart>
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
)
