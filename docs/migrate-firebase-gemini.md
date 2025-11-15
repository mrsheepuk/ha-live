# Migration Plan: Firebase SDK → Direct Gemini Live API WebSocket

## Motivation

1. **Bleeding edge access** - Direct API gets new models immediately (Gemini 2.0 Flash, improved transcription)
2. **No abstraction layer bugs** - Firebase SDK is beta-on-beta, going direct removes that variable
3. **Learning value** - Understanding the WebSocket protocol deeply
4. **Technical audience** - HA users already manage API keys and configs

## Strategy: "Borrow Smart, Build Clean"

Liberally borrow Firebase SDK's proven audio pipeline code, but write our own WebSocket layer that's cleaner and more transparent.

---

## Architecture Overview

### New Components

```
app/src/main/java/uk/co/mrsheep/halive/
├── services/
│   ├── protocol/
│   │   ├── ClientMessages.kt      # Setup, RealtimeInput, ToolResponse messages
│   │   ├── ServerMessages.kt      # SetupComplete, Content, ToolCall messages
│   │   └── ProtocolTypes.kt       # Shared types (Part, MediaChunk, etc.)
│   ├── audio/
│   │   └── GeminiAudioManager.kt  # Audio recording/playback (from Firebase SDK)
│   ├── GeminiLiveClient.kt        # OkHttp WebSocket wrapper
│   ├── GeminiLiveSession.kt       # Session orchestration (3 coroutines)
│   └── GeminiProtocolToolTransformer.kt  # MCP → Protocol format
└── core/
    └── GeminiConfig.kt             # API key storage (add to existing)
```

### Modified Components

- `GeminiService.kt` - Add direct protocol mode alongside Firebase SDK
- `MainViewModel.kt` - Handle new config requirement (API key)
- `SettingsActivity.kt` - UI for API key entry

---

## Phase 1: WebSocket Foundation

### GeminiLiveClient.kt

**Purpose:** Manage WebSocket connection to Gemini Live API

**Key responsibilities:**
- Connect to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key={API_KEY}`
- Send/receive JSON messages
- Emit Flow<ServerMessage> for consumption
- Handle connection lifecycle

**Technology:** OkHttp WebSocket (we already use OkHttp for MCP SSE)

**Key difference from MCP:**
- Bidirectional WebSocket vs unidirectional SSE
- Binary frames for audio vs text-only
- Different message format

---

## Phase 2: Message Protocol

### Protocol Package Structure

All messages use kotlinx.serialization with `@SerialName` annotations for snake_case JSON.

#### ClientMessages.kt

**SetupMessage** - First message sent after connection:
```kotlin
{
  "setup": {
    "model": "models/gemini-2.0-flash-exp",
    "generation_config": {
      "response_modalities": ["AUDIO"],
      "speech_config": {
        "voice_config": {
          "prebuilt_voice_config": {
            "voice_name": "Aoede"
          }
        }
      }
    },
    "system_instruction": {
      "parts": [{"text": "..."}]
    },
    "tools": [...]
  }
}
```

**RealtimeInputMessage** - Continuous audio streaming:
```kotlin
{
  "realtime_input": {
    "media_chunks": [
      {
        "mime_type": "audio/pcm",
        "data": "<base64-pcm>"
      }
    ]
  }
}
```

**ToolResponseMessage** - Reply to function calls:
```kotlin
{
  "tool_response": {
    "function_responses": [
      {
        "id": "abc123",
        "name": "turn_on_light",
        "response": {"result": "Success"}
      }
    ]
  }
}
```

**ClientContentMessage** - Send text (for sendTextRealtime):
```kotlin
{
  "client_content": {
    "turns": [
      {
        "role": "user",
        "parts": [{"text": "Hello"}]
      }
    ]
  }
}
```

#### ServerMessages.kt

**Sealed class with polymorphic deserialization:**

```kotlin
sealed class ServerMessage {
    data class SetupComplete : ServerMessage()
    data class Content(val serverContent: ServerContent) : ServerMessage()
    data class ToolCall(val toolCall: ToolCallData) : ServerMessage()
    data class ToolCallCancellation(val toolCallCancellation: CancellationData) : ServerMessage()
}
```

**ServerContent structure:**
```kotlin
{
  "server_content": {
    "model_turn": {
      "parts": [
        {
          "inline_data": {
            "mime_type": "audio/pcm",
            "data": "<base64>"
          }
        }
      ]
    },
    "turn_complete": false,
    "interrupted": false
  }
}
```

**Custom deserializer** - Check which key exists (setupComplete, serverContent, toolCall, toolCallCancellation)

---

## Phase 3: Audio Pipeline

### GeminiAudioManager.kt

**Borrow directly from Firebase SDK** - Audio is HARD, use battle-tested code.

#### Recording Configuration (from Firebase SDK)
```kotlin
SAMPLE_RATE = 16000 Hz
CHANNEL = MONO
ENCODING = PCM_16BIT
SOURCE = AudioSource.VOICE_COMMUNICATION
```

#### Playback Configuration (from Firebase SDK)
```kotlin
SAMPLE_RATE = 24000 Hz
CHANNEL = MONO
ENCODING = PCM_16BIT
USAGE = USAGE_MEDIA
CONTENT_TYPE = CONTENT_TYPE_SPEECH
```

#### Key Features
- **Acoustic Echo Cancellation** - Enable if available via `AcousticEchoCanceler`
- **Flow-based recording** - `Flow<ByteArray>` for continuous streaming
- **Channel-based playback** - `Channel<ByteArray>` for queuing audio
- **Proper buffer sizing** - Use Firebase SDK's tested values

#### Why Borrow
Firebase SDK team already:
- Tested on dozens of Android devices
- Tuned buffer sizes for optimal latency
- Chose correct AudioSource for voice
- Configured AEC properly

---

## Phase 4: Session Orchestration

### GeminiLiveSession.kt

**Purpose:** Coordinate WebSocket, audio, and tool execution

**Architecture:** 3 concurrent coroutines (like Firebase SDK)

#### Coroutine 1: Recording Loop
```kotlin
audioManager.startRecording().collect { audioBytes ->
    val base64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
    val message = RealtimeInputMessage(...)
    client.send(json.encodeToString(message))
}
```

#### Coroutine 2: Message Handling Loop
```kotlin
messageFlow.collect { message ->
    when (message) {
        is ServerMessage.Content -> {
            // Extract audio, decode base64, send to playback
            // Extract transcription, call handler
        }
        is ServerMessage.ToolCall -> {
            // Execute via callback, send response
        }
    }
}
```

#### Coroutine 3: Playback (handled by AudioManager)
```kotlin
// AudioManager's playback channel writes to AudioTrack
for (audioData in playbackChannel) {
    audioTrack?.write(audioData, 0, audioData.size)
}
```

#### Lifecycle
1. `connect()` - Establish WebSocket
2. `sendSetup()` - Send configuration message
3. `waitForSetupComplete()` - Block until server ready
4. `start()` - Launch 3 coroutines
5. `close()` - Stop audio, close WebSocket, cancel coroutines

---

## Phase 5: Tool Integration

### GeminiProtocolToolTransformer.kt

**Purpose:** Transform MCP tools → Protocol format

**Similar to existing** `GeminiMCPToolTransformer` but outputs different structure:

```kotlin
fun transform(mcpTools: McpToolsListResult): List<ToolDeclaration> {
    return mcpTools.tools.map { mcpTool ->
        ToolDeclaration(
            functionDeclarations = listOf(
                FunctionDeclaration(
                    name = mcpTool.name,
                    description = mcpTool.description,
                    parameters = transformSchema(mcpTool.inputSchema)
                )
            )
        )
    }
}
```

### Tool Execution Adapter

**Convert protocol format ↔ existing executor:**

```kotlin
suspend fun executeToolFromProtocol(
    call: ToolCallData.FunctionCall,
    executor: GeminiMCPToolExecutor
): FunctionResponse {
    // Protocol → Firebase format
    val firebaseCall = FunctionCallPart(
        name = call.name,
        args = call.args,
        id = call.id
    )

    // Execute via existing MCP executor (no changes needed!)
    val result = executor.executeTool(firebaseCall)

    // Firebase → Protocol format
    return FunctionResponse(
        id = call.id,
        name = call.name,
        response = result.response
    )
}
```

---

## Phase 6: Configuration

### GeminiConfig.kt

**Add API key storage** (similar to HAConfig):

```kotlin
object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "gemini_api_key"

    fun saveApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, null)
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context) != null
    }

    fun clearConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
```

---

## Phase 7: Service Integration

### GeminiService.kt Updates

**Add dual-mode support** (keep Firebase SDK as fallback):

```kotlin
class GeminiService(
    private val useDirectProtocol: Boolean = true,
    private val context: Context
) {
    // Existing Firebase SDK path
    private var firebaseModel: LiveGenerativeModel? = null
    private var firebaseSession: LiveSession? = null

    // New direct protocol path
    private var directSession: GeminiLiveSession? = null

    fun initializeModel(tools: List<Tool>, systemPrompt: String, ...) {
        if (useDirectProtocol) {
            // Store for later use in startSession
        } else {
            // Existing Firebase SDK initialization
        }
    }

    suspend fun startSession(
        functionCallHandler: suspend (FunctionCallPart) -> FunctionResponsePart,
        transcriptHandler: ((Transcription?, Transcription?) -> Unit)?
    ) {
        if (useDirectProtocol) {
            startDirectSession(functionCallHandler, transcriptHandler)
        } else {
            startFirebaseSession(functionCallHandler, transcriptHandler)
        }
    }

    private suspend fun startDirectSession(...) {
        val apiKey = GeminiConfig.getApiKey(context)
            ?: throw IllegalStateException("Gemini API key not configured")

        directSession = GeminiLiveSession(apiKey, context)

        // Adapt callbacks to protocol format
        directSession?.start(
            model = modelName,
            systemPrompt = systemPrompt,
            tools = protocolTools,
            voiceName = voiceName,
            onToolCall = { toolCall -> adaptToolHandler(toolCall, functionCallHandler) },
            onTranscription = transcriptHandler
        )
    }
}
```

### MainViewModel.kt Updates

**Add API key check to configuration flow:**

```kotlin
private fun checkConfiguration() {
    viewModelScope.launch {
        // Existing checks...

        // Add: Check Gemini API key (if using direct protocol)
        if (useDirectProtocol && !GeminiConfig.isConfigured(getApplication())) {
            _uiState.value = UiState.GeminiConfigNeeded
            return@launch
        }

        // Continue with existing flow...
    }
}

fun saveGeminiApiKey(apiKey: String) {
    GeminiConfig.saveApiKey(getApplication(), apiKey)
    checkConfiguration()
}
```

---

## Testing Strategy

### Phase 1: Connection Test
```kotlin
// Validate WebSocket connection works
val client = GeminiLiveClient(apiKey)
client.connect()
client.send(setupMessage)
// Verify setupComplete received
```

### Phase 2: Echo Test
Send text message → receive audio response → log (don't play)

### Phase 3: Playback Test
Send canned message → decode and play response audio

### Phase 4: Recording Test
Record microphone → send to API → receive → play (no tools)

### Phase 5: Tool Test
Trigger HA tool call → verify execution → verify response to Gemini

### Phase 6: Full Integration
Use app normally with direct protocol, validate all features work

---

## Watch Out For

### 1. Message Format Variations
Protocol docs might not match reality. Use Firebase SDK source as ground truth when in doubt.

### 2. Base64 Encoding
**Always use `Base64.NO_WRAP`** - line breaks will break the protocol.

### 3. Audio Buffer Sizes
Borrow Firebase SDK's exact values - they're empirically tuned for Android.

### 4. Coroutine Cancellation
Ensure all 3 coroutines (recording, receiving, playback) cancel cleanly on session end.

### 5. WebSocket Closure
Proper cleanup to avoid memory leaks, especially with audio resources.

### 6. Tool Call IDs
IDs in responses must match requests exactly or Gemini gets confused.

### 7. JSON Serialization
Snake_case in JSON (server_content) vs camelCase in Kotlin (serverContent). Use `@SerialName`.

### 8. Audio Format
- Recording: 16kHz PCM 16-bit mono
- Playback: 24kHz PCM 16-bit mono
- MIME type: "audio/pcm"
- Encoding: Raw bytes, no WAV headers

---

## Benefits

1. **Immediate model access** - Gemini 2.0+, new features as they release
2. **Better transcription** - Direct from API, no SDK bugs
3. **Deep understanding** - See exact protocol messages
4. **Debugging transparency** - Full visibility into communication
5. **No Firebase dependency** - Simpler user onboarding
6. **Cross-platform knowledge** - Same protocol on iOS/Web
7. **Potential optimizations** - Custom audio formats, video support, etc.

---

## Implementation Timeline

### Day 1-2: Foundation
- [x] Write migration plan
- [ ] Create protocol message classes
- [ ] Implement WebSocket client
- [ ] Add API key configuration

### Day 3-5: Audio
- [ ] Create GeminiAudioManager (borrow from Firebase SDK)
- [ ] Test recording pipeline
- [ ] Test playback pipeline
- [ ] Validate echo cancellation

### Day 5-6: Session
- [ ] Implement GeminiLiveSession orchestrator
- [ ] Wire up 3 coroutines
- [ ] Test end-to-end audio streaming

### Day 6-7: Tools
- [ ] Create protocol tool transformer
- [ ] Adapt tool execution
- [ ] Test HA tool calls

### Day 7-8: Integration
- [ ] Update GeminiService for dual-mode
- [ ] Update MainViewModel for config
- [ ] Add settings UI
- [ ] Full app testing

**Total: 3-5 days of focused work**

---

## Migration Path

### Phase 1: Add alongside Firebase SDK
Keep both implementations. Add settings toggle to choose mode.

### Phase 2: Default to direct protocol
Make direct protocol the default for new users. Existing users keep Firebase SDK.

### Phase 3: Deprecate Firebase SDK (optional)
If direct protocol proves stable, remove Firebase SDK entirely (optional - may keep for comparison).

---

## Rollback Plan

If critical issues arise:
1. Toggle `useDirectProtocol = false` in GeminiService
2. Falls back to existing Firebase SDK path
3. No user data loss (configs are separate)

---

## References

- **Gemini Live API Docs:** https://ai.google.dev/api/live
- **Firebase SDK Source:** https://github.com/firebase/firebase-android-sdk/tree/main/firebase-ai
- **Protocol Examples:** https://gist.github.com/quartzjer/9636066e96b4f904162df706210770e4
- **Vertex AI Docs:** https://cloud.google.com/vertex-ai/generative-ai/docs/model-reference/multimodal-live

---

## Status

**Current Phase:** Implementation Complete ✓
**Implementation Date:** 2025-11-15
**Next Step:** Testing and validation

### Completed Components

- ✅ Protocol package (ClientMessages, ServerMessages, ProtocolTypes)
- ✅ WebSocket client (GeminiLiveClient with OkHttp)
- ✅ Audio manager (GeminiAudioManager - borrowed from Firebase SDK)
- ✅ Session orchestrator (GeminiLiveSession with 3 coroutines)
- ✅ Tool transformer (GeminiProtocolToolTransformer)
- ✅ Configuration (GeminiConfig for API key storage)
- ✅ Service integration (GeminiService dual-mode support)
- ✅ ViewModel integration (MainViewModel with GeminiConfigNeeded state)
- ✅ UI integration (MainActivity with API key dialog)

### Known Issues / Notes

- **Type conversion:** JsonElement ↔ Map<String, Any> conversion implemented in GeminiService
- **Timeout:** 10-second setup timeout (configurable in GeminiLiveSession)
- **Base64:** Using Base64.NO_WRAP for audio encoding (critical for protocol compliance)
- **Direct protocol is default:** `useDirectProtocol = true` in MainViewModel
