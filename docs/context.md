# Project Context: "Live" Home Assistant Android App

## 1. High-Level Goal
This is an open-source, "best-in-class" voice assistant for Home Assistant (HA) on Android. The app overcomes the high latency and rigid, turn-based nature of traditional STT -> LLM -> TTS pipelines by providing a low-latency, "live," streaming, and interruptible audio conversation experience using the Gemini Live API.

## 2. Core Architecture: "BYOFP" (Bring Your Own Firebase Project)
The app solves the billing and API access conflict through the "BYOFP" approach:
* **Problem:** The Gemini Live API (which provides the streaming audio) is *only* available via the Firebase AI SDK. This SDK normally bills the developer, which is unfeasible for an open-source project.
* **Solution (BYOFP):** The app has *no* bundled `google-services.json` file. On first launch, the app requires the user to:
    1.  Create their own Firebase project and enable billing.
    2.  Use the app's file picker to import their own `google-services.json` file.
    3.  The app parses this file (via `FirebaseConfig.kt`), saves the credentials, and initializes Firebase dynamically at runtime.
* **Implementation:** See `core/FirebaseConfig.kt` for the file parsing and dynamic initialization logic.
* **Result:** The user pays for their own Gemini usage, enabling use of the Gemini Live API in an open-source app.

## 3. Technical Implementation & "The Glue"
The Gemini Live API cannot connect directly to Home Assistant's MCP (Model Context Protocol) server. The Android app acts as the "glue" or "translator" by maintaining a persistent SSE connection to the MCP server and bridging communication between Gemini and Home Assistant.

* **Connection Management (McpClientManager.kt):**
    * The app establishes a persistent SSE (Server-Sent Events) connection to the HA **MCP Server** at `/mcp_server/sse`.
    * It performs the MCP initialization handshake (initialize → initialized) before any operations.
    * This connection stays open for the lifetime of the app session.
    * Uses OkHttp's EventSource for SSE handling and coroutines for async operations.

* **Part 1: Tool Discovery (HomeAssistantRepository.kt)**
    * The app *fetches* tool definitions via JSON-RPC `tools/list` method through the SSE connection.
    * The `getTools()` method *transforms* the MCP-formatted JSON into `List<Tool>` (Function Declarations) that the Gemini API understands.
    * This transformation handles complex schemas including anyOf unions, arrays, enums, and nested objects.
    * The `List<Tool>` is passed to the Gemini `LiveSession` on initialization.

* **Part 2: Tool Execution (HomeAssistantRepository.kt)**
    * When the Live API returns a `FunctionCallPart` (e.g., `name="HassTurnOn"`), the app intercepts it via the function call handler.
    * The `executeTool()` method *executes* this call via JSON-RPC `tools/call` method through the MCP connection.
    * The MCP server handles the internal mapping to Home Assistant services.
    * The app sends a `FunctionResponsePart` back to the `LiveSession` to close the loop.

## 4. Implementation Overview
The app was developed following a risk-first strategy, prioritizing validation of the "live" audio experience:

1.  **✅ Task 1: App Skeleton** - Complete implementation of:
    * BYOFP file-picker UI and dynamic Firebase initialization (`FirebaseConfig.kt`)
    * HA configuration UI for URL + token input (`HAConfig.kt`)
    * MCP SSE connection establishment (`McpClientManager.kt`)
    * State management and UI flow (`MainActivity.kt`, `MainViewModel.kt`)

2.  **✅ Task 2: Gemini Live API Integration** - Complete implementation of:
    * `GeminiService.kt` manages the `LiveSession`, microphone, and speaker
    * Real-time audio streaming with function call handling
    * Session lifecycle management (start/stop)

3.  **✅ Task 3: Complete MCP Client** - Complete implementation of:
    * Full MCP client with SSE connection and JSON-RPC protocol
    * Tool discovery and transformation from MCP to Gemini format
    * Tool execution with proper request/response correlation
    * Complex schema handling (anyOf, arrays, enums)

### Session Initialization Flow
The app uses a deferred initialization strategy to ensure maximum freshness of system context:

1.  **App Launch (`checkConfiguration()`):**
    * Establishes MCP connection to Home Assistant
    * Transitions directly to `UiState.ReadyToTalk` without initializing Gemini
    * User sees "Ready to chat" immediately after MCP connection

2.  **Button Press (`startChat()`):**
    * User clicks "Start Chat" button
    * App transitions to `UiState.Initializing` (shows "Initializing..." status)
    * Executes `initializeGemini()`:
        - Fetches current tools from Home Assistant MCP server via `getTools()`
        - Retrieves system prompt from active profile
        - Optionally fetches live context via `GetLiveContext` tool (if profile enabled)
        - Combines profile settings (system prompt, personality, background info, live context)
        - Initializes Gemini Live model with fresh tools, prompt, model name, and voice
        - Logs full system prompt to tool log for debugging
    * If initialization succeeds, starts audio conversation session
    * Transitions to `UiState.ChatActive`

3.  **Benefits:**
    * System prompt reflects current Home Assistant state (fresh live context)
    * Tools reflect latest available Home Assistant capabilities
    * Faster app launch (no waiting for Gemini initialization)
    * Profile switching is simpler (no pre-initialization needed)

## 5. Key Components
* **ui/MainActivity.kt**: Main activity handling UI states, permissions, and user interactions
* **ui/MainViewModel.kt**: ViewModel managing app state, configuration flow, and tool execution logging
* **services/GeminiService.kt**: Wrapper for Gemini Live API, managing audio conversation sessions
* **services/HomeAssistantRepository.kt**: Bridge between Gemini and Home Assistant, handling tool discovery and execution
* **services/McpClientManager.kt**: MCP protocol client managing SSE connection and JSON-RPC communication
* **core/FirebaseConfig.kt**: Dynamic Firebase initialization from user-provided config file
* **core/HAConfig.kt**: Home Assistant configuration persistence
* **core/SystemPromptConfig.kt**: Configurable system prompt with UI for customization

## 6. Features
* **BYOFP Architecture**: No bundled Firebase config; users provide their own
* **Live Voice Conversation**: Real-time, streaming, interruptible audio powered by Gemini Live API
* **Dynamic Tool Discovery**: Automatically fetches available tools from Home Assistant MCP server
* **Tool Execution**: Seamlessly executes Home Assistant commands through MCP protocol
* **Tool Call Logging**: Real-time display of tool calls with success/failure indicators
* **Configurable System Prompt**: User-editable system instructions for the AI assistant
* **State Management**: Clean state machine for configuration and chat states
