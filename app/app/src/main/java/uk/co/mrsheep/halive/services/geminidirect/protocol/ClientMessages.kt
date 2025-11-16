package uk.co.mrsheep.halive.services.geminidirect.protocol

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
    val realtimeInput: RealtimeInput? = null,
    @SerialName("tool_response")
    val toolResponse: ToolResponse? = null,
    @SerialName("client_content")
    val clientContent: ClientContent? = null
)

// --- SetupMessage (first message after connection) ---

@Serializable
data class SetupMessage(
    val model: String,
    @SerialName("generation_config")
    val generationConfig: GenerationConfig,
    @SerialName("system_instruction")
    val systemInstruction: Content? = null,
    val tools: List<ToolDeclaration>? = null,
    // Proactivity doesn't seem to work yet, rejected by API in setup:
    // val proactivity: ProactivtyConfig? = null,
    @SerialName("input_audio_transcription")
    val inputAudioTranscription: AudioTranscriptionConfig? = null,
    @SerialName("output_audio_transcription")
    val outputAudioTranscription: AudioTranscriptionConfig? = null,
)

@Serializable
data class ProactivtyConfig(
    @SerialName("proactive_audio")
    val proactiveAudio: Boolean? = null
)

@Serializable
class AudioTranscriptionConfig()

// --- GenerationConfig (output settings) ---

@Serializable
data class GenerationConfig(
    @SerialName("response_modalities")
    val responseModalities: List<String>? = null, // e.g., ["AUDIO"]
    @SerialName("speech_config")
    val speechConfig: SpeechConfig? = null,
    // affective dialog doesn't seem to work yet, rejected by API in setup:
    // @SerialName("enable_affective_dialog")
    // val enableAffectiveDialog: Boolean? = null,
)

// --- SpeechConfig (voice settings) ---

@Serializable
data class SpeechConfig(
    @SerialName("voice_config")
    val voiceConfig: VoiceConfig,
    @SerialName("language_code")
    val languageCode: String?
)

@Serializable
data class VoiceConfig(
    @SerialName("prebuilt_voice_config")
    val prebuiltVoiceConfig: PrebuiltVoiceConfig,
)

@Serializable
data class PrebuiltVoiceConfig(
    @SerialName("voice_name")
    val voiceName: String // e.g., "Aoede"
)

// --- RealtimeInputMessage (streaming audio) ---


@Serializable
data class RealtimeInput(
    @SerialName("media_chunks")
    val mediaChunks: List<MediaChunk>? = null,
    val audio: MediaChunk? = null,
    val video: MediaChunk? = null,
)

// --- ToolResponseMessage (reply to function calls) ---

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
    val turns: List<Turn>,
    @SerialName("turn_complete")
    val turnComplete: Boolean = false
)

@Serializable
data class Turn(
    val role: String, // "user"
    val parts: List<TextPart>  // Use TextPart directly instead of polymorphic Part
)
