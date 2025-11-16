# Claude Context: HA Live - Home Assistant Voice Assistant

## Project Overview
Open-source Android voice assistant for Home Assistant using Gemini Live API. Provides low-latency, streaming, interruptible voice conversations by acting as a bridge between Gemini Live and Home Assistant's MCP server.

**Key Innovation:** Dual provider architecture - users can choose between Firebase SDK (with google-services.json) or direct Gemini Live API (with API key), enabling flexible deployment without mandatory developer billing.

## Architecture

### Conversation Service Providers

Two implementations of the `ConversationService` interface:

**FirebaseConversationService** (`services/geminifirebase/`)
- Uses Firebase AI SDK for Gemini Live API access
- Requires `google-services.json` (BYOFP pattern)
- Original implementation, stable and well-tested
- Tool transformation: `FirebaseMCPToolTransformer.kt`
- Tool execution: `FirebaseMCPToolExecutor.kt`

**DirectConversationService** (`services/geminidirect/`)
- Direct WebSocket connection to Gemini Live API
- Requires Gemini API key (stored in `GeminiConfig`)
- Lower-level protocol control, no SDK constraints
- Tool transformation: `GeminiLiveMCPToolTransformer.kt`
- Session management: `GeminiLiveSession.kt`, protocol types in `protocol/`

**ConversationServiceFactory**
- Selects provider based on user preference (`ConversationServicePreference`)
- Falls back if preferred provider not configured
- Defaults to Direct API if both available

### Core Components

**MCP Bridge:**
- `McpClientManager.kt` - SSE connection to HA MCP server (`/mcp_server/sse`)
- Created fresh per-session (not at app launch)
- Provides raw MCP tools and executes JSON-RPC calls

**Tool Execution Layer:**
- `ToolExecutor` interface - Abstract tool execution contract
- `AppToolExecutor` - Wraps MCP client, adds logging + local tools
- `LocalToolDefinitions.kt` - App-side tools (e.g., EndConversation)
- Local tools executed in-app, MCP tools forwarded to Home Assistant

**Session Preparation:**
- `SessionPreparer.kt` - Extracts heavy initialization from ViewModel
- Fetches tools, applies filtering, renders templates, builds system prompt
- Returns initialized `ConversationService` ready to start

**Configuration:**
- `HAConfig.kt` - Home Assistant URL + token
- `FirebaseConfig.kt` - Dynamic Firebase initialization from user file
- `GeminiConfig.kt` - Gemini API key for direct protocol
- `ConversationServicePreference.kt` - User's provider choice

**User Features:**
- `ProfileManager.kt` - Multiple conversation profiles
- `Profile.kt` - System prompt, personality, model, voice, tool filtering
- `WakeWordService.kt` - Foreground wake word detection
- `ProfileExportImport.kt` - Share profiles via JSON

### Session Flow

**App Launch:**
1. Check Firebase config (google-services.json or Gemini API key)
2. Check Home Assistant config (URL + token)
3. Initialize `HomeAssistantApiClient` (for template rendering)
4. State: `ReadyToTalk` (no Gemini initialization yet)
5. Start wake word listening if enabled (foreground only)

**Start Chat Button Press:**
1. Stop wake word listening (release microphone)
2. State: `Initializing`
3. Create fresh `McpClientManager` for this session
4. Create `ConversationService` via factory (based on preference)
5. `SessionPreparer.prepareAndInitialize()`:
   - Fetch raw MCP tools
   - Apply profile tool filtering (ALL or SELECTED)
   - Render background info template (Jinja2 via HA API)
   - Fetch live context (GetLiveContext tool) if enabled
   - Build system prompt with all sections
   - Initialize conversation service with tools + prompt
6. Start audio conversation session
7. State: `ChatActive`
8. Play ready beep
9. Send initial message to agent if configured
10. Resume wake word on session end

**Tool Call Flow:**
```
User speaks → Gemini Live → FunctionCall (provider-specific format)
    ↓
ConversationService → toolExecutor.callTool(name, args)
    ↓
AppToolExecutor checks localTools map
    ↓ (if MCP tool)
McpClientManager.callTool() [JSON-RPC via SSE]
    ↓
Home Assistant MCP Server → HA Service Call
    ↓
ToolCallResult ← Home Assistant
    ↓
FunctionResponse ← ConversationService
    ↓
Gemini Live (confirms action via audio)
```

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

<live_context>
{plain text from GetLiveContext tool - current HA state overview}
</live_context>
```

**Template Rendering:**
- Background info rendered via `POST /api/template` if not blank
- Supports full Jinja2 syntax
- Fails initialization on template errors (user feedback)
- Re-rendered on every session start for freshness

**Live Context:**
- Fetched via GetLiveContext MCP tool if `profile.includeLiveContext == true`
- Response structure: `{"result": {"result": "Live Context: ..."}}`
- Graceful degradation on error (continues without it)

## Profile Features

Each profile configures:
- **System Prompt** - Core instructions
- **Personality** - Conversation style
- **Background Info** - Jinja2 template (rendered fresh each session)
- **Model** - e.g., "gemini-2.0-flash-exp"
- **Voice** - e.g., "Aoede", "Leda"
- **Tool Filtering** - ALL (use all MCP tools) or SELECTED (whitelist)
- **Include Live Context** - Fetch GetLiveContext on startup
- **Enable Transcription** - Real-time speech-to-text logging
- **Auto-Start Chat** - Start conversation on app launch
- **Initial Message to Agent** - Text sent immediately after session starts

## Key Architectural Patterns

### Abstraction via Interfaces
```kotlin
interface ConversationService {
    suspend fun initialize(tools, systemPrompt, model, voice, toolExecutor, transcriptor)
    suspend fun startSession()
    suspend fun sendText(message)
    fun stopSession()
    fun cleanup()
}

interface ToolExecutor {
    suspend fun getTools(): List<McpTool>
    suspend fun callTool(name, arguments): ToolCallResult
}
```

**Why:** Enables multiple providers without coupling to Firebase SDK or direct protocol

### Separation of Concerns
- **Transformation:** `*MCPToolTransformer` (stateless, provider-specific)
- **Execution:** `AppToolExecutor` (logs, UI state, local tools)
- **Coordination:** `MainViewModel` (session lifecycle, UI state)
- **Preparation:** `SessionPreparer` (tool fetching, filtering, template rendering)

### Dependency Management (HAGeminiApp)
```kotlin
// Global state (survives activity recreation)
var haApiClient: HomeAssistantApiClient? = null
var haUrl: String? = null
var haToken: String? = null

suspend fun initializeHomeAssistant(haUrl: String, haToken: String) {
    this.haUrl = haUrl
    this.haToken = haToken
    haApiClient = HomeAssistantApiClient(haUrl, haToken)
}
```

**Note:** MCP connection (`McpClientManager`) is now per-session, created in `MainViewModel.startChat()`

## Important Type Mappings

**MCP → Provider Tool Transformation:**
- `McpTool` (from `mcpClient.getTools()`) has `name`, `description`, `inputSchema`
- Firebase: Transform to `List<Tool>` via `FirebaseMCPToolTransformer`
- Direct: Transform to `List<ToolDeclaration>` via `GeminiLiveMCPToolTransformer`
- **Access tool names from MCP layer** before transformation (provider properties may be internal)

**Return Types:**
- `toolExecutor.getTools()` → `List<McpTool>`
- `toolExecutor.callTool()` → `ToolCallResult`
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
3. Verify **return types** match actual type definitions
4. Check **property access patterns** (nested properties, nullable chains)

### Provider SDK Constraints
- Firebase AI SDK properties are **internal** - cannot access `Tool.functionDeclarations` or `FunctionDeclaration.name`
- **Solution:** Access tool information from MCP layer before transformation, or use direct API
- Always verify property visibility when working with SDK types

### Error Handling Philosophy
- Template rendering: **Fail fast** (user needs to know config is wrong)
- Live context fetching: **Graceful degradation** (continue without it)
- Tool execution: **Log and return error** (don't crash session)
- MCP connection: **Per-session** (failure doesn't break app, retry on next session)

### Material Library Version Consistency
- App uses **Material Components 2** (`Theme.MaterialComponents.*` in `themes.xml`)
- **DO NOT** use Material3 styles, attributes, or text appearances

**Styles - Use Material Components 2:**
- **Correct:** `@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox`
- **Incorrect:** `@style/Widget.Material3.TextInputLayout.OutlinedBox` (causes crashes)

**Theme Attributes - Material3-only (DO NOT USE):**
- ❌ `?attr/colorErrorContainer` → Use `@color/error_container` or define in theme
- ❌ `?attr/colorSurfaceVariant` → Use direct color value (e.g., `#F5F5F5`)
- ✅ `?attr/colorPrimary`, `?attr/colorOnSurface` are safe (Material Components 2)

**Text Appearances - Use Material Components 2:**
- ❌ `textAppearanceTitleMedium` → ✅ `textAppearanceSubtitle1`
- ❌ `textAppearanceBodySmall` → ✅ `textAppearanceCaption`
- ❌ `textAppearanceLabelLarge` → ✅ `textAppearanceButton`
- Safe: `textAppearanceHeadline1-6`, `textAppearanceBody1-2`, `textAppearanceCaption`, `textAppearanceButton`

### Code Review Checklist
1. Search for all references to modified/deleted classes
2. Check return type names match actual definitions
3. Verify property access patterns (nested properties, nullable chains)
4. Test with both conversation providers
5. Check impacts on files not included in the change

## File Organization

```
app/app/src/main/java/uk/co/mrsheep/halive/
├── core/
│   ├── FirebaseConfig.kt               # BYOFP dynamic initialization
│   ├── GeminiConfig.kt                 # Direct API key storage
│   ├── HAConfig.kt                     # HA credentials persistence
│   ├── Profile.kt                      # Profile data model
│   ├── ProfileManager.kt               # Profile CRUD + migration
│   ├── ConversationServicePreference.kt # Provider selection
│   ├── WakeWordConfig.kt               # Wake word settings
│   ├── SystemPromptConfig.kt           # Default prompts
│   ├── ProfileExportImport.kt          # Profile sharing
│   └── AppLogger.kt                    # Logging interface
├── services/
│   ├── conversation/
│   │   ├── ConversationService.kt      # Provider interface
│   │   └── ConversationServiceFactory.kt # Provider selection
│   ├── geminifirebase/
│   │   ├── FirebaseConversationService.kt   # Firebase SDK implementation
│   │   ├── FirebaseMCPToolTransformer.kt    # MCP → Firebase tools
│   │   └── FirebaseMCPToolExecutor.kt       # Firebase tool execution
│   ├── geminidirect/
│   │   ├── DirectConversationService.kt     # Direct API implementation
│   │   ├── GeminiLiveSession.kt             # WebSocket session
│   │   ├── GeminiLiveMCPToolTransformer.kt  # MCP → protocol tools
│   │   ├── AudioHelper.kt                   # Audio encoding
│   │   └── protocol/
│   │       ├── ClientMessages.kt            # Outbound messages
│   │       ├── ServerMessages.kt            # Inbound messages
│   │       └── ProtocolTypes.kt             # Common types
│   ├── mcp/
│   │   ├── McpClientManager.kt         # MCP SSE client
│   │   ├── McpMessages.kt              # JSON-RPC message types
│   │   └── McpToolModels.kt            # MCP tool data structures
│   ├── ToolExecutor.kt                 # Tool execution interface
│   ├── AppToolExecutor.kt              # Logging + local tool wrapper
│   ├── SessionPreparer.kt              # Session initialization logic
│   ├── HomeAssistantApiClient.kt       # HA REST API (templates)
│   ├── LocalToolDefinitions.kt         # App-side tools
│   ├── WakeWordService.kt              # Foreground wake detection
│   └── BeepHelper.kt                   # Audio feedback
├── ui/
│   ├── MainActivity.kt                 # Main UI + audio conversation
│   ├── MainViewModel.kt                # Main coordination logic
│   ├── OnboardingActivity.kt           # First-run setup flow
│   ├── SettingsActivity.kt             # Settings management
│   ├── ProfileManagementActivity.kt    # Profile CRUD UI
│   ├── ProfileEditorActivity.kt        # Profile editing
│   └── adapters/
│       ├── ProfileAdapter.kt           # Profile list UI
│       └── ToolSelectionAdapter.kt     # Tool filter UI
└── HAGeminiApp.kt                      # Application class (dependency container)
```

## Dependencies

- **Kotlin Coroutines:** Async operations, SSE handling
- **OkHttp:** SSE connection (MCP), WebSocket (Direct API), HTTP client (template API)
- **kotlinx.serialization:** JSON parsing (MCP, HA API, protocol messages)
- **Firebase AI SDK:** Gemini Live API access (Firebase provider only)
- **Material Design:** UI components
- **AndroidX:** Lifecycle, ViewModel, ConstraintLayout
- **TensorFlow Lite:** Wake word detection (OpenWakeWord model)

## Recent Architectural Changes

### Dual Provider Architecture
- **Added** `DirectConversationService` for direct Gemini Live API access
- **Created** `ConversationService` interface to abstract provider
- **Implemented** factory pattern for provider selection
- **Benefit:** No longer dependent on Firebase SDK constraints, users can choose

### Session Lifecycle Refactoring
- **Moved** MCP connection from app launch to per-session
- **Extracted** initialization logic to `SessionPreparer`
- **Benefit:** Fresh tools/context per session, cleaner separation of concerns

### Tool Execution Abstraction
- **Created** `ToolExecutor` interface
- **Implemented** `AppToolExecutor` wrapper with logging + local tools
- **Benefit:** Local tools (EndConversation) execute in-app, unified logging

### Profile Enhancements
- **Added** tool filtering (ALL vs SELECTED modes)
- **Added** transcription logging toggle
- **Added** auto-start chat on app launch
- **Added** initial message to agent
- **Added** profile export/import via JSON

## Features

- ✅ Dual provider support (Firebase SDK or Direct API)
- ✅ Multiple conversation profiles
- ✅ Tool filtering (whitelist mode per profile)
- ✅ Wake word detection (foreground only)
- ✅ Jinja2 template rendering for background info
- ✅ Live context fetching on session start
- ✅ Real-time transcription logging
- ✅ Auto-start chat on app launch
- ✅ Initial message to agent
- ✅ Profile export/import
- ✅ Tool call logging with timestamps
- ✅ Local tools (EndConversation)
- ✅ Audio feedback (beeps for ready/end)
