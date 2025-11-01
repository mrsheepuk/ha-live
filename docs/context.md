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
We cannot connect the Gemini Live API directly to Home Assistant's MCP (Model Context Protocol) server. Our Android app must act as the "glue" or "translator" in a two-part process.

* **Part 1: Tool Discovery (Task 1)**
    * The app will *fetch* the tool definitions from the user's HA **MCP Server** as a large JSON file.
    * It will then *transform* this MCP-formatted JSON into the `List<Tool>` (Function Declarations) that the Gemini API understands.
    * This `List<Tool>` is given to the `LiveSession` on initialization.

* **Part 2: Tool Execution (Task 2)**
    * When the Live API returns a `FunctionCallPart` (e.g., `name="light.turn_on"`), our app intercepts it.
    * It *executes* this call by parsing the name (e.g., to `domain="light"`, `service="turn_on"`) and making a standard `POST` call to the HA **REST API** (e.g., `/api/services/light/turn_on`).
    * The app then sends a `FunctionResponsePart` (e.g., `{"status": "success"}`) back into the `LiveSession` to close the loop.

## 4. Development Strategy (Risk-First)
The core risk is the "live" audio experience, so:

1.  **Step 1 (Task 0):** Build the app skeleton, including the "BYOFP" file-picker UI and dynamic Firebase initialization.
2.  **Step 2 (Task 1):** Immediately implement the Gemini Live API (`LiveSession`, mic/speaker) with **dummy tools** and a **mocked executor** (`delay(1000)`). This is to validate the core conversational feel of the app *first*.
3.  **Step 3 (Tasks 2 & 3):** Once the live UX is proven, build the *real* tool discovery (Task 2) and execution (Task 3) modules to replace the mocks.

## 5. Reference Documents
For detailed implementation plans, please refer to the following files:

* `project_plan.md`: The main project overview and development strategy.
* `task_0_app_skeleton.md`: Plan for the "BYOFP" setup UI and app framework.
* `task_1_live_api_wiring.md`: Plan for integrating the Gemini Live API (audio I/O, session management).
* `task_2_mcp_transformer.md`: Plan for the "Tool Discovery" (MCP -> Gemini) transformation.
* `task_3_ha_executor.md`: Plan for the "Tool Execution" (Gemini -> HA REST API) logic.
