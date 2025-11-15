package uk.co.mrsheep.halive.services.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

// --- Client Messages (sent to Gemini Live API) ---

/**
 * Wrapper for all client messages - only one field should be present per message
 */
@Serializable
data class ClientMessage(
    val setup: SetupMessage? = null,
    @SerialName("realtime_input")
    val realtimeInput: RealtimeInputMessage? = null,
    @SerialName("tool_response")
    val toolResponse: ToolResponseMessage? = null,
    @SerialName("client_content")
    val clientContent: ClientContent? = null  // Direct reference, no wrapper
)

// --- SetupMessage (first message after connection) ---

@Serializable
data class SetupMessage(
    val model: String,
    @SerialName("generation_config")
    val generationConfig: GenerationConfig,
    @SerialName("system_instruction")
    val systemInstruction: Content? = null,
    val tools: List<ToolDeclaration>? = null
)

// --- GenerationConfig (output settings) ---

@Serializable
data class GenerationConfig(
    @SerialName("response_modalities")
    val responseModalities: List<String>? = null, // e.g., ["AUDIO"]
    @SerialName("speech_config")
    val speechConfig: SpeechConfig? = null
)

// --- SpeechConfig (voice settings) ---

@Serializable
data class SpeechConfig(
    @SerialName("voice_config")
    val voiceConfig: VoiceConfig
)

@Serializable
data class VoiceConfig(
    @SerialName("prebuilt_voice_config")
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@Serializable
data class PrebuiltVoiceConfig(
    @SerialName("voice_name")
    val voiceName: String // e.g., "Aoede"
)

// --- RealtimeInputMessage (streaming audio) ---

@Serializable
data class RealtimeInputMessage(
    @SerialName("realtime_input")
    val realtimeInput: RealtimeInput
)

@Serializable
data class RealtimeInput(
    @SerialName("media_chunks")
    val mediaChunks: List<MediaChunk>
)

// --- ToolResponseMessage (reply to function calls) ---

@Serializable
data class ToolResponseMessage(
    @SerialName("tool_response")
    val toolResponse: ToolResponse
)

@Serializable
data class ToolResponse(
    @SerialName("function_responses")
    val functionResponses: List<FunctionResponse>
)

@Serializable
data class FunctionResponse(
    val id: String,
    val name: String,
    val response: JsonElement? = null // The result of tool execution
)

// --- ClientContent (send text) ---

@Serializable
data class ClientContent(
    val turns: List<Turn>
)

@Serializable
data class Turn(
    val role: String, // "user"
    val parts: List<TextPart>  // Use TextPart directly instead of polymorphic Part
)
