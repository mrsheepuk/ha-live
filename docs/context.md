# Project Context: "Live" Home Assistant Android App

## 1. High-Level Goal
We are building an open-source, "best-in-class" voice assistant for Home Assistant (HA) on Android. The primary goal is to overcome the high latency and rigid, turn-based nature of traditional STT -> LLM -> TTS pipelines. The app must provide a low-latency, "live," streaming, and interruptible audio conversation.

## 2. Core Architecture: "BYOFP" (Bring Your Own Firebase Project)
This is the most critical decision, as it solves the billing and API access conflict.
* **Problem:** The Gemini Live API (which provides the streaming audio) is *only* available via the Firebase AI SDK. This SDK normally bills the developer, which is unfeasible for an open-source project.
* **Solution (BYOFP):** The app will have *no* bundled `google-services.json` file. On first launch, the app will require the user (who are "geeks," per our user) to:
    1.  Create their own Firebase project and enable billing.
    2.  Use the app's file picker to import their own `google-services.json` file.
    3.  The app parses this file, saves the credentials, and initializes Firebase dynamically at runtime.
* **Result:** The user pays for their own Gemini usage, and we get to use the Gemini Live API.

## 3. Technical Implementation & "The Glue"
We cannot connect the Gemini Live API directly to Home Assistant's MCP (Model Context Protocol) server. Our Android app must act as the "glue" or "translator" by maintaining a persistent SSE connection to the MCP server.

* **Connection Management:**
    * The app establishes a persistent SSE (Server-Sent Events) connection to the HA **MCP Server** at `/api/mcp`.
    * It performs the MCP initialization handshake (initialize → initialized) before any operations.
    * This connection stays open for the lifetime of the app session.

* **Part 1: Tool Discovery (Task 3)**
    * The app *fetches* tool definitions via JSON-RPC `tools/list` method through the SSE connection.
    * It *transforms* the MCP-formatted JSON into `List<Tool>` (Function Declarations) that the Gemini API understands.
    * This `List<Tool>` is given to the `LiveSession` on initialization.

* **Part 2: Tool Execution (Task 3)**
    * When the Live API returns a `FunctionCallPart` (e.g., `name="HassTurnOn"`), our app intercepts it.
    * It *executes* this call via JSON-RPC `tools/call` method through the MCP connection.
    * The MCP server handles the internal mapping to Home Assistant services.
    * The app sends a `FunctionResponsePart` back to the `LiveSession` to close the loop.

## 4. Development Strategy (Risk-First)
The core risk is the "live" audio experience, so:

1.  **Step 1 (Task 1):** Build the app skeleton, including the "BYOFP" file-picker UI, dynamic Firebase initialization, HA configuration UI (URL + token), and MCP SSE connection establishment.
2.  **Step 2 (Task 2):** Immediately implement the Gemini Live API (`LiveSession`, mic/speaker) with **dummy tools** and a **mocked executor** (`delay(1000)`). This is to validate the core conversational feel of the app *first*.
3.  **Step 3 (Task 3):** Once the live UX is proven, build the complete MCP client (connection, discovery, and execution) to replace the mocks with real functionality.

## 5. Reference Documents
For detailed implementation plans, please refer to the following files:

* `project_plan.md`: The main project overview and development strategy.
* `task_1_app_skeleton.md`: Plan for the "BYOFP" setup UI, HA configuration, and MCP connection initialization.
* `task_2_live_api_wiring.md`: Plan for integrating the Gemini Live API (audio I/O, session management) with mock tools.
* `task_3_mcp_transformer.md`: Complete MCP client implementation (SSE connection, tool discovery, transformation, and execution).
