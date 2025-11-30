# HA Live - Voice Assistant for Home Assistant

**Bring natural, conversational AI to your Home Assistant setup with low-latency, streaming voice interactions.**

HA Live is an open-source Android app that bridges Google's Gemini Live API with Home Assistant's Model Context Protocol (MCP) server, giving you a powerful voice assistant that can control your smart home through natural conversation. Think of it as having a deeply integrated AI assistant that actually understands and can control your entire Home Assistant ecosystem.

## What Makes HA Live Special?

- **True Streaming Conversations**: Uses Gemini Live's real-time, bidirectional streaming for natural, interruptible conversations—no more waiting for the AI to finish speaking
- **Direct Home Assistant Integration**: Connects to Home Assistant's MCP server to access all your entities, services, and automations as native AI tools
- **Multiple Personalities**: Create unlimited conversation profiles with different prompts, voices, models, and tool access
- **Wake Word Detection**: Built-in "Lizzy H" wake word support (foreground only, privacy-first)
- **Contextual Awareness**: Inject live Home Assistant state and Jinja2 templates into every conversation

## How It Works

HA Live acts as a bridge between two powerful systems:

```
You → HA Live (Android) → Gemini Live API
                ↓
    Home Assistant MCP Server (/mcp_server/sse)
                ↓
    Your Smart Home (lights, sensors, automations, etc.)
```

When you speak to HA Live:
1. Your voice is streamed to Gemini Live for real-time processing
2. Gemini Live receives a list of available "tools" from your Home Assistant setup
3. When Gemini decides to control your home, it calls these tools
4. HA Live translates tool calls into JSON-RPC requests to your HA MCP server
5. Home Assistant executes the action and returns results
6. Gemini confirms the action back to you via voice

All of this happens in real-time with sub-second latency, making conversations feel natural and responsive.

## Key Features

### Conversation Profiles
Create multiple profiles for different use cases:
- **Custom System Prompts**: Define how the AI should behave
- **Personality Traits**: Formal assistant, casual friend, technical expert—you choose
- **Background Info**: Use Jinja2 templates to inject dynamic context (e.g., `{{ now() }}`, `{{ states('sensor.temperature') }}`)
- **Model Selection**: Choose from available Gemini models (e.g., `gemini-2.0-flash-exp`)
- **Voice Options**: Select from multiple voice styles (Aoede, Leda, etc.)
- **Tool Filtering**: Grant access to ALL tools or create a whitelist for specific profiles
- **Auto-Start**: Automatically begin conversations when opening the app
- **Initial Messages**: Send a message to the agent as soon as the session starts

### Advanced Configuration
- **Live Context Injection**: Automatically fetch current Home Assistant state before each conversation
- **Template Rendering**: Background info supports full Jinja2 syntax via Home Assistant's template API
- **Transcription Logging**: See real-time speech-to-text for debugging and monitoring
- **Profile Import/Export**: Share profiles with the community via JSON

### Wake Word Detection
- Powered by OpenWakeWord's ONNX models
- "Lizzy H" wake phrase
- Foreground-only (battery efficient, privacy-conscious)
- Works while app is active, pauses during conversations

### Session Management
- Fresh MCP connection per conversation (always up-to-date tools)
- Clean session lifecycle (no stale state between chats)
- Graceful error handling (template errors fail fast, context errors degrade gracefully)

## Requirements

- **Android Device**: API 26+ (Android 8.0 Oreo or newer)
- **Home Assistant**:
  - Version with MCP server support
  - MCP SSE endpoint enabled (`/mcp_server/sse`)
  - Long-lived access token
- **Gemini API Key**: Get one from [Google AI Studio](https://aistudio.google.com/apikey)
- **Network**: Both devices on the same network (or Home Assistant accessible remotely via HTTPS)

## Installation

### 1. Install the App

**Option A: Build from Source**
```bash
git clone https://github.com/yourusername/ha-live.git
cd ha-live/app
./gradlew assembleRelease
# Install the APK from app/app/build/outputs/apk/release/
```

**Option B: Download APK**
Download the latest APK from the [Releases](https://github.com/yourusername/ha-live/releases) page.

### 2. Set Up Home Assistant

Enable the MCP server in your Home Assistant configuration:

```yaml
# configuration.yaml
# Ensure MCP server is enabled (enabled by default in recent versions)
```

Create a long-lived access token:
1. Go to your Home Assistant profile (click your username in the sidebar)
2. Scroll down to "Long-Lived Access Tokens"
3. Click "Create Token"
4. Give it a name (e.g., "HA Live App")
5. Copy the token immediately (you won't see it again)

### 3. Get a Gemini API Key

1. Go to [Google AI Studio](https://aistudio.google.com/apikey)
2. Create a new API key (or use existing)
3. Copy the API key for app setup

### 4. First-Run Onboarding

When you first launch HA Live, you'll go through a 3-step setup:

**Step 1: Configure Gemini API**
- Paste your Gemini API key

**Step 2: Connect Home Assistant**
- Enter your Home Assistant URL (e.g., `http://192.168.1.100:8123` or `https://home.example.com`)
- Paste your long-lived access token
- Tap "Test Connection" to verify

**Step 3: Complete**
- Tap "Start Using HA Live"

## Configuration Guide

### Creating Your First Profile

After onboarding, you'll have a default profile. To customize or create new ones:

1. Tap the menu (three dots) → "Manage Profiles"
2. Tap "+" to create a new profile or tap an existing one to edit

**System Prompt**: Core instructions for the AI
```
You are a helpful home automation assistant. You can control lights,
check sensors, and manage automations. Be concise and action-oriented.
```

**Personality**: How the AI should communicate
```
Friendly but professional. Use casual language but stay focused on
getting tasks done efficiently.
```

**Background Info**: Dynamic context using Jinja2 templates
```
Current time: {{ now().strftime('%I:%M %p') }}
Outside temperature: {{ states('sensor.outdoor_temperature') }}°F
Living room occupied: {{ states('binary_sensor.living_room_motion') }}
```

**Live Context**: Enable to fetch a complete Home Assistant state overview at session start

**Tool Filtering**:
- **ALL**: Grant access to all Home Assistant tools (recommended for general use)
- **SELECTED**: Whitelist specific tools (useful for restricted profiles, e.g., "kids profile" with limited access)

### Wake Word Configuration

**Quick Toggle**: Tap the "Wake Word" chip on the main screen to enable/disable detection.

**Advanced Settings** (Settings → Wake Word → "Configure"):
- **Threshold** (0.3-0.8): Sensitivity control—lower = more sensitive, higher = fewer false positives
- **Thread Count**: CPU threads for model execution (1, 2, 4, or 8)
- **Execution Mode**: Sequential (lower latency) or Parallel (multi-core utilization)
- **Optimization Level**: ONNX Runtime optimization (Basic recommended, Extended/All for maximum performance)
- **Test Mode**: Live score display with visual threshold marker to tune sensitivity

Note: Models are bundled with the app (~10MB) and copied to device storage on first launch.

### Profile Management Tips

- **Export Single Profile**: In Manage Profiles, tap a profile's menu → "Export" → Save as `.haprofile` file
- **Export All Profiles**: In Manage Profiles, menu → "Export All Profiles" → Save as `.haprofile` file
- **Import Profiles**: In Manage Profiles, menu → "Import Profiles" → Select `.haprofile` file
- **Quick Switch**: Tap the dropdown on the main screen to change active profile
- **Auto-Start**: Enable in profile settings to start conversations immediately on app launch

## Advanced Features

### Jinja2 Template Support

Background info templates are rendered via Home Assistant's `/api/template` endpoint, giving you access to:
- **Time**: `{{ now() }}`, `{{ today_at('17:00') }}`
- **States**: `{{ states('entity.id') }}`, `{{ states.sensor }}`
- **Attributes**: `{{ state_attr('climate.bedroom', 'temperature') }}`
- **Custom**: Any Jinja2 function available in HA

Templates are re-rendered fresh at the start of each conversation.

### Local Tools

In addition to Home Assistant tools, HA Live provides built-in tools:
- **EndConversation**: Allows Gemini to gracefully end the session when appropriate

### Debug Logs (Tool Call Logging)

Access detailed tool execution logs via the menu → "Debug Logs":
```
[✓] 12:34:57 - SUCCESS
Tool: light.turn_on
Params: {"entity_id": "light.living_room"}
Result: {"success": true}

[✓] 12:35:02 - SUCCESS
Tool: GetLiveContext
Params: {}
Result: Live Context: Living room lights are on...
```

Shows all tool calls, system events, initialization steps, and errors.

### Transcription Logs (Speech-to-Text)

When enabled in profile settings, a collapsible section on the main screen shows real-time speech-to-text:
```
User: "Turn on the living room lights"
Model: "Okay, turning on the living room lights"
Model (thought): "I should use the light.turn_on service"
```

Toggle the header to expand/collapse the transcription view.

## Architecture

HA Live uses a modular architecture:

### Core Components
- **ConversationService Interface**: Abstracts the Gemini Live API connection
- **DirectConversationService**: WebSocket-based Gemini Live API implementation
- **ConversationServiceFactory**: Creates the conversation service

### MCP Integration
- **McpClientManager**: Server-Sent Events (SSE) client for Home Assistant MCP server
- **AppToolExecutor**: Wraps MCP client, adds logging and local tool support
- **SessionPreparer**: Handles tool fetching, filtering, and session initialization

### Session Lifecycle
1. App launch: Load config, initialize API client, start wake word (if enabled)
2. Start chat: Create MCP connection, fetch tools, apply filtering, render templates
3. Active session: Stream audio, handle tool calls, provide transcription
4. End chat: Cleanup MCP connection, play end beep, resume wake word

### File Organization
```
app/src/main/java/uk/co/mrsheep/halive/
├── core/                    # Configuration and data models
│   ├── HAConfig.kt         # Home Assistant credentials
│   ├── GeminiConfig.kt     # Gemini API key storage
│   ├── Profile.kt          # Profile data model
│   └── ProfileManager.kt   # Profile CRUD operations
├── services/
│   ├── conversation/       # Provider interface
│   ├── geminidirect/       # Gemini Live API implementation
│   ├── mcp/                # MCP client
│   ├── WakeWordService.kt  # Wake word detection
│   └── SessionPreparer.kt  # Session initialization
└── ui/                     # Activities and ViewModels
```

## Contributing

Contributions are welcome! Areas where help is especially appreciated:

- **Additional wake word models** (beyond "Lizzy H")
- **UI/UX improvements** for profile management
- **Background mode support** (Android background restrictions are tricky)
- **Documentation** and example profiles

### Development Setup

1. Clone the repository
2. Open in Android Studio (Hedgehog or newer)
3. Sync Gradle dependencies
4. Set up a Home Assistant test instance
5. Create a Gemini API key
6. Build and run on device or emulator (API 26+)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- **Home Assistant**: For the amazing MCP server implementation
- **Google Gemini Team**: For the Gemini Live API
- **OpenWakeWord**: For the ONNX wake word models
- **Claude (Sonnet 4.5)**: For the tireless implementation efforts, marshalling teams of Haiku subagents to build most of the features in this app
- **The Home Assistant Community**: For inspiration and feedback

## Community & Support

- **Issues**: [GitHub Issues](https://github.com/yourusername/ha-live/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/ha-live/discussions)
- **Home Assistant Forum**: [Coming Soon]

## Roadmap

- [ ] Background mode support (system-wide wake word)
- [ ] Custom wake word training
- [ ] Multi-language support
- [ ] Response caching for common queries
- [ ] Integration with Home Assistant conversation agents
- [ ] Widget support for quick access
- [ ] Wear OS companion app

---

**Made with love for the Home Assistant community**

If you find HA Live useful, consider starring the repo and sharing it with other HA enthusiasts!
