# **Project: Live Home Assistant (Gemini + MCP)**

## **1. Project Overview**

This is a fully-functional, open-source "best-in-class" voice interface for Home Assistant (HA) on Android. The app overcomes the latency and rigid, turn-based nature of traditional (STT -> LLM -> TTS) voice pipelines.

The app provides a continuous, "live" conversational experience, allowing users to speak naturally, interrupt the assistant, and have their commands actioned with minimal delay.

This is achieved using the **Gemini Live API** for real-time audio streaming and the **Home Assistant Model Context Protocol (MCP) Server** as the dynamic "source of truth" for the assistant's capabilities.

## **2. Implemented Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App                            │
│                                                             │
│  ┌────────────────┐         ┌─────────────────────────┐     │
│  │ Gemini Live    │         │   McpClientManager      │     │
│  │ Session        │         │   ├─ SSE Connection     │     │
│  │                │         │   ├─ Init handshake     │     │
│  │ ┌────────────┐ │         │   ├─ Request queue      │     │
│  │ │ Mic Input  │ │         │   └─ Message router     │     │
│  │ └────────────┘ │         └───────────┬─────────────┘     │
│  │                │                     │                   │
│  │ ┌────────────┐ │         ┌───────────▼─────────────┐     │
│  │ │Speaker Out │ │         │  HomeAssistantRepository│     │
│  │ └────────────┘ │◄────────┤  ├─ getTools()          │     │
│  │                │         │  └─ executeTool()       │     │
│  │  Tools: List   │         └─────────────────────────┘     │
│  │  [HassTurnOn,  │                                         │
│  │   HassLightSet,│                                         │
│  │   ...]         │                                         │
│  └────────────────┘                                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ SSE (persistent)
                              │
┌─────────────────────────────▼───────────────────────────────┐
│              Home Assistant MCP Server                      │
│                                                             │
│  ┌─────────────────────────────────────────────────┐        │
│  │  MCP Protocol Handler                           │        │
│  │  ├─ Initialize handshake                        │        │
│  │  ├─ tools/list → returns [HassTurnOn, ...]      │        │
│  │  └─ tools/call → executes HA service            │        │
│  └───────────────────────┬─────────────────────────┘        │
│                          │                                  │
│  ┌───────────────────────▼─────────────────────────┐        │
│  │  Home Assistant Core                            │        │
│  │  ├─ light.turn_on                               │        │
│  │  ├─ media_player.set_volume                     │        │
│  │  └─ ...                                         │        │
│  └─────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

## **3. Core Components**

The app integrates three primary systems:

1. **Android App (The "Orchestrator"):**
   * Native Android application built with Kotlin.
   * Manages device microphone and speaker through the Gemini Live API.
   * Establishes and maintains the persistent LiveSession with the Gemini Live API.
   * Acts as the central "glue" bridging Gemini and Home Assistant.
   * Implements BYOFP (Bring Your Own Firebase Project) architecture for API access.

2. **Gemini Live API (The "Brain"):**
   * Single, bidirectional API for streaming audio *in* and receiving audio *out*.
   * Handles STT, LLM reasoning, and TTS in one continuous session.
   * Supports **Function Calling (Tool Use)** with dynamic tool definitions.
   * Receives the list of available tools from the Android App at session start.

3. **Home Assistant MCP Server (The "Backend"):**
   * **'tools/list' (The "Menu"):** Exposes all specified entities, services, and scenes as machine-readable JSON via SSE protocol. This is the **discovery mechanism** providing the list of controllable tools.
   * **'tools/call' (The "Hands"):** Allows exposed tools to be called via SSE protocol, executing commands (e.g., turning on lights, running services).

## **4. Core Data Flow: Initialization**

This flow describes what happens when the app is launched, *before* the user speaks. This is how the "Brain" (Gemini) learns about the "Menu" (MCP).

1. **App Starts:** The Android app launches and checks configuration status.
2. **User Configuration:**
   * If Firebase not configured: User imports `google-services.json` via file picker.
   * If HA not configured: User enters Home Assistant URL and long-lived access token.
3. **MCP Connection:** The app establishes SSE connection to `<ha-url>/mcp_server/sse` and performs MCP initialization handshake (`initialize` → `initialized`).
4. **Fetch Tools:** The `HomeAssistantRepository.getTools()` method sends a JSON-RPC `tools/list` request via the MCP connection.
5. **Receive "Menu":** The app receives a JSON object from the MCP server detailing all available functions (e.g., `HassTurnOn`, `HassLightSet`, `GetLiveContext`).
6. **Transform Tools:** The app parses this MCP-formatted JSON and transforms it into the **Gemini Tool/Function Declaration** format, handling complex schemas (anyOf, arrays, enums).
7. **Start "Live" Session:** When user taps "Start Chat", the app connects to the Gemini Live API via `GeminiService.startSession()`, providing the transformed list of tools and the configurable system prompt.

## **5. Core Data Flow: Conversation**

This flow describes what happens when the user actively uses the assistant.

1. **User Speaks:** The user taps "Start Chat". The app begins streaming audio from the device microphone *to* the Gemini Live API via `startAudioConversation()`.
   * *User: "Hey, can you turn on the kitchen light and tell me the weather?"*

2. **Gemini Responds (Two Paths):**
   * **Path A (Chat):** For the "tell me the weather" part, Gemini streams audio *back* to the app, which plays it on the device speaker.
   * **Path B (Tool Call):** For the "turn on the kitchen light" part, the Gemini Live API sends a `FunctionCallPart` to the app.
     * *Gemini -> App: FunctionCallPart(name="HassTurnOn", args={"name": "kitchen light", "domain": "light"})*

3. **Execute Tool:** The app's function call handler (`MainViewModel.executeHomeAssistantTool()`) receives the call and delegates to `HomeAssistantRepository.executeTool()`.

4. **App Acts as MCP Client:** The repository constructs a JSON-RPC `tools/call` request and sends it through the MCP connection via `McpClientManager.callTool()`.

5. **Get Result:** Home Assistant executes the command via the MCP server and returns a result with success/error status.

6. **Log Tool Call:** The app logs the tool call with timestamp, parameters, and result in the UI for debugging.

7. **Close the Loop:** The app sends the result *back into* the Gemini Live session as a `FunctionResponsePart`.
   * *App -> Gemini: FunctionResponsePart(name="HassTurnOn", response={"result": {...}})*

8. **Confirm to User:** The Gemini Live API, now aware the tool call was successful, streams the final confirmation audio to the user.
   * *Gemini -> App (Audio): "Okay, I've turned on the kitchen light. The weather is..."*

## **6. Key Implementation Components (The "Glue" Code)**

The core logic of this app is implemented in two key "glue" components within `HomeAssistantRepository.kt`:

* **MCP-to-Gemini Transformer (`getTools()` method):**
  * Ingests MCP JSON tool definitions and outputs `List<Tool>` compatible with the Gemini API.
  * Implemented in `transformMcpToGeminiFunctionDeclaration()` and `transformMcpPropertyToSchema()`.
  * Handles complex schema mappings:
    * `anyOf` unions (picks first type as primary)
    * Arrays with enum items
    * Nested objects
    * Optional vs required parameters
  * Maps MCP property types to Gemini Schema types (string, integer, double, boolean, array, enumeration).

* **Gemini-to-HA Executor (`executeTool()` method):**
  * Ingests a `FunctionCallPart` from Gemini and outputs a valid Home Assistant MCP `tools/call` JSON-RPC request.
  * Converts function call arguments to `JsonElement` map.
  * Sends request via `McpClientManager.callTool()`.
  * Parses MCP response and constructs `FunctionResponsePart` for Gemini.
  * Handles both success and error responses with proper formatting.

## **7. Implementation Summary**

The project followed a risk-first development approach, prioritizing validation of the core "live" conversational experience:

### ✅ **Task 1: Android App Skeleton** (Completed)
   * Base Android project with BYOFP (Bring Your Own Firebase Project) setup
   * HA configuration UI (URL + token input)
   * Persistent MCP SSE connection to Home Assistant
   * State machine for configuration flow
   * **Key Files:** `FirebaseConfig.kt`, `HAConfig.kt`, `McpClientManager.kt`, `MainActivity.kt`, `MainViewModel.kt`

### ✅ **Task 2: Gemini Live API Integration** (Completed)
   * `GeminiService` manages the LiveSession, microphone, and speaker
   * Real-time bidirectional audio streaming
   * Function call handler integration
   * Session lifecycle management (start/stop)
   * **Key Files:** `GeminiService.kt`

### ✅ **Task 3: Complete MCP Client** (Completed)
   * Full MCP client with SSE connection management
   * Tool discovery (fetch + transform tools from MCP to Gemini format)
   * Tool execution (call tools via MCP and return results to Gemini)
   * Complex schema handling (anyOf, arrays, enums, nested objects)
   * Request/response correlation with unique IDs
   * **Key Files:** `HomeAssistantRepository.kt`, `McpClientManager.kt`, `McpMessages.kt`, `McpToolModels.kt`

### ✅ **Additional Features Implemented**
   * **System Prompt Configuration:** User-editable system instructions with default prompt
   * **Tool Call Logging:** Real-time UI display of tool calls with success/failure indicators
   * **Error Handling:** Comprehensive error handling throughout the stack
   * **Permission Management:** Proper Android permission handling for microphone access

## **8. Technology Stack**

* **Language:** Kotlin
* **UI:** Android XML layouts with ViewModel pattern
* **Networking:** OkHttp with SSE (EventSource)
* **Serialization:** Kotlinx Serialization (JSON)
* **Async:** Kotlin Coroutines and Flow
* **AI API:** Firebase AI SDK (Gemini Live API)
* **Architecture:** MVVM with Repository pattern

## **9. File Structure**

```
app/src/main/java/uk/co/mrsheep/halive/
├── HAGeminiApp.kt              # Application class
├── core/
│   ├── FirebaseConfig.kt       # BYOFP implementation
│   ├── HAConfig.kt             # HA configuration persistence
│   └── SystemPromptConfig.kt   # System prompt management
├── services/
│   ├── GeminiService.kt        # Gemini Live API wrapper
│   ├── HomeAssistantRepository.kt  # MCP-Gemini bridge
│   ├── McpClientManager.kt     # MCP protocol client
│   └── mcp/
│       ├── McpMessages.kt      # MCP message models
│       └── McpToolModels.kt    # MCP tool schema models
└── ui/
    ├── MainActivity.kt         # Main UI
    └── MainViewModel.kt        # UI state management
```

## **10. Current Status**

**✅ COMPLETE** - All planned features have been implemented and the app is fully functional. The app successfully:
* Allows users to bring their own Firebase project (BYOFP)
* Connects to Home Assistant MCP server
* Discovers tools dynamically from MCP
* Provides live voice conversation via Gemini
* Executes Home Assistant commands through tool calls
* Displays real-time tool call logs
* Supports configurable system prompts
