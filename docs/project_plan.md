# **Project: Live Home Assistant (Gemini + MCP)**

## **1. High-Level Goals**

The primary objective is to create a "best-in-class" voice interface for Home Assistant (HA) on Android. This app will overcome the latency and rigid, turn-based nature of traditional (STT -> LLM -> TTS) voice pipelines.

The app will provide a continuous, "live" conversational experience, allowing the user to speak naturally, interrupt the assistant, and have their commands actioned with minimal delay.

This will be achieved by using the **Gemini Live API** for real-time audio streaming and the **Home Assistant Model Context Protocol (MCP) Server** as the dynamic "source of truth" for the assistant's capabilities.

## Revised Architecture Diagram

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

## **2. Core Components**

1. **Android App (The "Orchestrator"):**  
   * The native Android application.  
   * Manages the device microphone and speaker.  
   * Establishes and maintains the persistent LiveSession with the Gemini Live API.  
   * Acts as the central "glue" for all data flow.  
2. **Gemini Live API (The "Brain"):**  
   * A single, bidirectional API for streaming audio *in* and receiving audio *out*.  
   * Handles STT, LLM reasoning, and TTS in one continuous session.  
   * Crucially, supports **Function Calling (Tool Use)**.  
   * Will be "told" what tools are available by the Android App at the start of the session.  
3. **Home Assistant MCP Server 'tools/list' (The "Menu"):**  
   * The HA integration that exposes all specified entities, services, and scenes as a machine-readable JSON object (via an HTTP SSE protocol).  
   * This is the **discovery mechanism**. It provides the *list* of what can be controlled.  
4. **Home Assistant MCP Server 'tools/call' (The "Hands"):**  
   * The HA integration allows the exposed tools to be called (via an HTTP SSE protocol).  
   * This will be used for *executing* commands (e.g., turning on a light, running a service).

## **3. Core Data Flow: (Initialization)**

This flow describes what happens when the app is launched, *before* the user speaks. This is how the "Brain" (Gemini) learns about the "Menu" (MCP).

1. **App Starts:** The Android app launches. 
2. **Fetch Tools:** The app makes an HTTP GET request to the Home Assistant MCP Server's endpoint (e.g., http://<ha-ip>:8123/api/mcp).  
3. **Receive "Menu":** The app receives a large JSON object from the MCP server, detailing all available functions (e.g., light.turn_on, scene.activate, cover.open_cover).  
4. **Transform Tools:** The app **(Glue Task #1)** parses this MCP-formatted JSON and transforms it into the **Gemini Tool/Function Declaration** format.  
5. **Start "Live" Session:** The app connects to the Gemini Live API, providing the transformed list of tools and a system prompt (e.g., "You are a helpful home assistant. Use the provided tools to control the user's home.").

## **4. Core Data Flow: (Conversation)**

This flow describes what happens when the user actively uses the assistant.

1. **User Speaks:** The user taps the "talk" button. The app begins streaming audio from the device microphone *to* the Gemini Live API.  
   * *User: "Hey, can you turn on the kitchen light and tell me the weather?"*  
2. **Gemini Responds (Two Paths):**  
   * **Path A (Chat):** For the "tell me the weather" part, Gemini streams audio *back* to the app, which plays it on the device speaker.  
   * **Path B (Tool Call):** For the "turn on the kitchen light" part, the Gemini Live API sends a *data message* (JSON) to the app. This message is a function_call request.  
     * *Gemini -> App: {"function_call": "light.turn_on", "args": {"entity_id": "light.kitchen_main"}}*  
3. **Execute Tool:** The app receives this function_call. It **(Glue Task #2)** parses this request and maps it to a standard Home Assistant API call.  
4. **App Acts as Client:** The app makes a POST request to the HA REST API (e.g., POST /api/services/light/turn_on with {"entity_id": "light.kitchen_main"} as the body).  
5. **Get Result:** Home Assistant executes the command and returns a success/error status to the app.  
6. **Close the Loop:** The app sends this result *back into* the Gemini Live session as a function_response.  
   * *App -> Gemini: {"function_response": "light.turn_on", "response": {"status": "success"}}*  
7. **Confirm to User:** The Gemini Live API, now aware the tool call was successful, streams the final confirmation audio to the user.  
   * *Gemini -> App (Audio): "Okay, I've turned on the kitchen light. The weather is..."*

## **5. Key Responsibilities (The "Glue" Code)**

The core logic of this app, and our primary development focus, lies in two key "glue" components:

* **Glue Task #1: MCP-to-Gemini Transformer**  
  * A module that ingests the MCP JSON and outputs a List<Tool> compatible with the Gemini API.  
  * This involves mapping MCP function schemas to Gemini function schemas.  
* **Glue Task #2: Gemini-to-HA Executor**  
  * A module that ingests a function_call from Gemini and outputs a valid Home Assistant MCP 'tools/call' request (service call).  
  * This involves mapping the function name and arguments to the correct HA MCP tool / arguments and sending as a JSONRPC payload.

## **6. Next Steps**

We will follow a risk-first approach, prioritizing the validation of the core "live" conversational experience before building the production-level HA connectors.

1. **Task 0: Android App Skeleton:**
   * Create the base Android project with BYOFP (Bring Your Own Firebase Project) setup
   * Create HA configuration UI (URL + token input)
   * Establish persistent MCP SSE connection to Home Assistant
   * See: [task_0_app_skeleton.md](task_0_app_skeleton.md)

2. **Task 1: Gemini Live API Integration & Mock Executor:**
   * Implement the GeminiService to manage the LiveSession, microphone, and AudioTrack playback
   * **Instead of Task 2:** Hard-code a *dummy* list of tools (e.g., Tool(name="HassTurnOn", ...))
   * **Instead of Task 2:** Create a *dummy* executeHomeAssistantTool function with delay(1000) and hard-coded "success" responses
   * **Goal:** Have a fully working app that *feels* real. Test audio latency, transcription, and conversational flow.
   * See: [task_1_live_api_wiring.md](task_1_live_api_wiring.md)

3. **Task 2: Complete MCP Client (Discovery & Execution):**
   * Build the complete MCP client with SSE connection management
   * Implement tool discovery (fetch + transform tools from MCP to Gemini format)
   * Implement tool execution (call tools via MCP and return results to Gemini)
   * **Swap:** Replace *both* dummy components from Task 1 with the real MCP client
   * See: [task_2_mcp_transformer.md](task_2_mcp_transformer.md)

4. **Final Test:** At this point, all mock components have been replaced with real, data-driven components. The app is feature-complete.
