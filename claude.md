# Claude Context: HA Live - Home Assistant Voice Assistant

## Project Overview
Open-source Android voice assistant for Home Assistant using Gemini Live API. Provides low-latency, streaming, interruptible voice conversations by acting as a bridge between Gemini Live and Home Assistant's MCP server.

**Key Innovation:** BYOFP (Bring Your Own Firebase Project) architecture - users provide their own `google-services.json` file to enable Gemini Live API access without developer billing.

## Architecture

### Core Components

**MCP Bridge (The "Glue"):**
- `McpClientManager.kt` - Persistent SSE connection to HA MCP server at `/mcp_server/sse`
- `GeminiMCPToolTransformer.kt` - Transforms MCP tools → Gemini FunctionDeclarations
- `GeminiMCPToolExecutor.kt` - Executes tool calls via MCP JSON-RPC
- `HomeAssistantApiClient.kt` - Direct REST API calls (template rendering)

**Gemini Integration:**
- `GeminiService.kt` - Manages LiveSession, microphone, speaker, real-time audio
- `MainViewModel.kt` - Coordinates initialization, handles function calls, logs tool execution

**User Features:**
- `ProfileManager.kt` - Multiple conversation profiles with custom prompts/settings
- BYOFP: `FirebaseConfig.kt` - Dynamic Firebase initialization from user file
- Tool logging UI for debugging

### Session Initialization Flow (Deferred Strategy)

**App Launch:**
1. Establish MCP SSE connection to Home Assistant
2. Go directly to "Ready to Talk" state (no Gemini initialization)

**Start Chat Button Press:**
1. Transition to "Initializing..." state
2. Fetch raw MCP tools via `mcpClient.getTools()` → `McpToolsListResult`
3. Extract tool names: `mcpToolsResult.tools.map { it.name }` (for logging)
4. Transform tools: `GeminiMCPToolTransformer.transform()` → `List<Tool>`
5. Retrieve active profile settings
6. **Render background info template** via `haApiClient.renderTemplate()` (Jinja2)
7. **Fetch live context** via `GetLiveContext` tool (if enabled in profile)
8. Build system prompt with sections:
   - `<system_prompt>` - Core instructions
   - `<personality>` - Conversation style
   - `<background_info>` - Rendered template with dynamic HA state
   - `<initial_live_context>` - Current HA overview (if enabled)
9. Initialize Gemini Live model with tools + prompt
10. Start audio conversation session

**Benefits:** Fresh tools, live context, and rendered templates at every session start.

## System Prompt Structure

```
<system_prompt>
{profile.systemPrompt}
</system_prompt>

<personality>
{profile.personality}
</personality>

<background_info>
{rendered via HA template API - supports {{ now() }}, {{ states('entity.id') }}, etc.}
</background_info>

<initial_live_context>
{plain text from GetLiveContext tool - current HA state overview}
</initial_live_context>
```

**Template Rendering:**
- Background info is always rendered via `POST /api/template` if not blank
- Supports full Jinja2 syntax
- Fails initialization on template errors (user gets clear feedback)
- Re-rendered on every session start for freshness

**Live Context:**
- Fetched via GetLiveContext MCP tool
- Response structure: `{"result": {"success": true, "result": "Live Context: ..."}}`
- Extract: `response.jsonObject["result"]?.jsonObject["result"]?.jsonPrimitive?.content`
- Separate section from background info for semantic clarity

## Key Architectural Patterns

### Coordination Layer (MainViewModel)
Tools are fetched and transformed at the coordination layer, not buried in repositories:
```kotlin
// MainViewModel.initializeGemini()
val mcpToolsResult = app.mcpClient?.getTools()           // Raw MCP
val tools = GeminiMCPToolTransformer.transform(it)       // Gemini format
val toolNames = mcpToolsResult.tools.map { it.name }     // For logging
```

**Why:** Access to public MCP properties (`McpTool.name`) before transformation into Firebase SDK's internal structure.

### Separation of Concerns
- **Transformation:** `GeminiMCPToolTransformer` (stateless singleton)
- **Execution:** `GeminiMCPToolExecutor` (injectable class)
- **Coordination:** `MainViewModel` (orchestrates both)

**Deleted:** `HomeAssistantRepository` (violated SRP - did both transformation and execution)

### Dependency Management (HAGeminiApp)
```kotlin
var mcpClient: McpClientManager? = null
var toolExecutor: GeminiMCPToolExecutor? = null
var haApiClient: HomeAssistantApiClient? = null

suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
    mcpClient = McpClientManager(haUrl, haToken)
    mcpClient?.initialize()
    toolExecutor = GeminiMCPToolExecutor(mcpClient!!)
    haApiClient = HomeAssistantApiClient(haUrl, haToken)
}
```

## Important Type Mappings

**MCP → Gemini Tool Transformation:**
- `McpToolsListResult` (from `mcpClient.getTools()`) has a `tools: List<McpTool>` property
- Each `McpTool` has public `name: String`, `description: String`, `inputSchema: McpInputSchema`
- Transform to `List<Tool>` (Firebase SDK) which contains internal `FunctionDeclaration` list
- **Access tool names from MCP layer** before transformation (Firebase properties are internal)

**Return Types:**
- `mcpClient.getTools()` → `McpToolsListResult?`
- `toolExecutor.executeTool()` → `FunctionResponsePart`
- `haApiClient.renderTemplate()` → `String` (throws on error)

## Layout Patterns

**Correct padding pattern:**
```xml
<!-- ScrollView/NestedScrollView has NO padding -->
<NestedScrollView
    android:clipToPadding="false"
    android:fitsSystemWindows="true">

    <!-- Inner LinearLayout HAS padding -->
    <LinearLayout android:padding="16dp">
        <!-- Content -->
    </LinearLayout>
</NestedScrollView>
```

**Exception:** Root ConstraintLayout (onboarding) can have horizontal padding with `fitsSystemWindows="true"`.

## Key Learnings for Future Agents

### Before Deleting/Refactoring Classes
1. **Search entire codebase** for references: `grep -r "ClassName" app/`
2. Check **all usages** in ViewModels, Activities, Services
3. Verify **return types** match actual type definitions (not assumptions)
4. Check **property access patterns** (e.g., nested properties in data classes)

### Firebase SDK Constraints
- Firebase AI SDK properties are **internal** - cannot access `Tool.functionDeclarations` or `FunctionDeclaration.name`
- **Solution:** Access tool information from MCP layer before transformation
- Always verify property visibility when working with Firebase types

### Error Handling Philosophy
- Template rendering: **Fail fast** (user needs to know their config is wrong)
- Live context fetching: **Graceful degradation** (continue without it)
- Tool execution: **Log and return error** (don't crash the session)

### Code Review Checklist
1. Search for all references to modified/deleted classes
2. Check return type names match actual definitions
3. Verify property access patterns (nested properties, nullable chains)
4. Test with haiku agents, then senior developer review
5. Check impacts on files not included in the change

## Tool Execution Flow

```
User speaks → Gemini Live API → FunctionCallPart
    ↓
MainViewModel.executeHomeAssistantTool(call)
    ↓
app.toolExecutor.executeTool(call)
    ↓
mcpClient.callTool(name, arguments) [JSON-RPC via SSE]
    ↓
Home Assistant MCP Server → HA Service Call
    ↓
ToolCallResult ← Home Assistant
    ↓
FunctionResponsePart ← toolExecutor
    ↓
Gemini Live API (confirms action via audio)
```

## Recent Significant Changes (2025-01)

### Deferred Initialization
- **Moved** Gemini model initialization from app launch to "Start Chat" button press
- **Benefit:** Faster app startup, fresh tools/context at conversation start
- **New state:** `UiState.Initializing` with "Initializing..." feedback

### Architectural Refactoring
- **Split** `HomeAssistantRepository` into focused classes:
  - `GeminiMCPToolTransformer` - Pure transformation (singleton)
  - `GeminiMCPToolExecutor` - Tool execution (injectable)
- **Moved** coordination to `MainViewModel` (fetches MCP tools, transforms, executes)
- **Solved** Firebase internal API issue by accessing tool names from MCP layer

### System Prompt Enhancements
- **Added** `<initial_live_context>` section (separate from background info)
- **Added** Home Assistant template rendering for background info
- **Extract** live context as plain text (unwrap nested JSON from GetLiveContext)
- **Log** available tools at startup for debugging

### Template Rendering
- Background info rendered via `POST {haUrl}/api/template`
- Supports full Jinja2: `{{ now() }}`, `{{ states('entity.id') }}`, `{% if %}`, etc.
- Always renders if background info is not blank
- Re-renders on every session start (fresh content)

## File Organization

```
app/app/src/main/java/uk/co/mrsheep/halive/
├── core/
│   ├── FirebaseConfig.kt        # BYOFP dynamic initialization
│   ├── HAConfig.kt              # HA credentials persistence
│   ├── Profile.kt               # Profile data model
│   └── ProfileManager.kt        # Profile CRUD + migration
├── services/
│   ├── GeminiService.kt         # Gemini Live API wrapper
│   ├── GeminiMCPToolTransformer.kt    # MCP → Gemini transformation
│   ├── GeminiMCPToolExecutor.kt       # MCP tool execution
│   ├── HomeAssistantApiClient.kt      # HA REST API (templates)
│   ├── McpClientManager.kt      # MCP SSE client
│   └── mcp/
│       ├── McpMessages.kt       # JSON-RPC message types
│       └── McpToolModels.kt     # MCP tool data structures
├── ui/
│   ├── MainActivity.kt          # Main UI + audio conversation
│   ├── MainViewModel.kt         # Main coordination logic
│   ├── OnboardingActivity.kt    # First-run setup flow
│   ├── SettingsActivity.kt      # Settings management
│   └── ProfileManagementActivity.kt  # Profile CRUD UI
└── HAGeminiApp.kt               # Application class (dependency container)
```

## Testing the App

1. **First Launch:** Import `google-services.json` (BYOFP)
2. **Configure HA:** Enter URL + long-lived access token
3. **Create Profile:** Customize system prompt, personality, background info
4. **Background Info Template Example:**
   ```
   Current time: {{ now() }}
   Sun: {{ states('sun.sun') }}
   Temperature: {{ states('sensor.living_room_temperature') }}°C
   ```
5. **Enable Live Context:** Checkbox in profile for dynamic HA state overview
6. **Start Chat:** Click button → see tool log → speak with assistant

## Debugging

- **Tool Log:** Real-time display of tool calls, system startup, errors
- **System Startup Entry:** Shows available tools (count + sorted list) and full system prompt
- **Template Errors:** Clear error messages if Jinja2 syntax is invalid
- **MCP Connection:** Logs in `McpClientManager` show SSE events and JSON-RPC messages

## Dependencies

- **Kotlin Coroutines:** Async operations, SSE handling
- **OkHttp:** SSE connection (MCP), HTTP client (template API)
- **kotlinx.serialization:** JSON parsing (MCP, HA API)
- **Firebase AI SDK:** Gemini Live API access
- **Material Design:** UI components
- **AndroidX:** Lifecycle, ViewModel, ConstraintLayout

## Next Steps / Future Enhancements

- [ ] Error recovery for failed template rendering mid-session
- [ ] Cache rendered templates per session (optional optimization)
- [ ] Profile import/export for sharing configurations
- [ ] Multi-language support for UI
- [ ] Conversation history persistence
- [ ] Wake word detection for hands-free usage
