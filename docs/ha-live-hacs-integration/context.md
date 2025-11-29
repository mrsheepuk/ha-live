# Shared HA Config: Project Context

## What is HA Live?

HA Live is an Android voice assistant app that connects to Home Assistant. Users speak to the app, which uses Google's Gemini Live API for natural conversation, and executes commands in Home Assistant via its MCP (Model Context Protocol) server.

## The Problem

Currently, each user must configure the app independently:
- Enter Home Assistant URL
- Manually copy a long-lived access token from HA
- Enter their Gemini API key
- Create conversation profiles (system prompts, personality, settings)

For households with multiple people using the same Home Assistant instance, this means duplicated setup and no way to share configuration.

## The Solution

Store shared configuration in Home Assistant itself, so household members can:
1. Log in with their HA credentials (no manual token copying)
2. Automatically use a shared Gemini API key
3. Access shared conversation profiles
4. Still have local-only profiles if desired

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Home Assistant                        │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │        ha_live_config (HACS Integration)           │ │
│  │                                                    │ │
│  │  Stores:                                           │ │
│  │  • Shared Gemini API key                          │ │
│  │  • Shared conversation profiles                   │ │
│  │                                                    │ │
│  │  Exposes HA services for get/set operations       │ │
│  └────────────────────────────────────────────────────┘ │
│                           ↑                              │
│                           │ REST API                     │
│                           │                              │
│  ┌────────────────────────┴───────────────────────────┐ │
│  │                    MCP Server                       │ │
│  │              (existing, unchanged)                  │ │
│  └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
                            ↑
                            │ OAuth2 + REST API
                            │
┌───────────────────────────┴─────────────────────────────┐
│                      HA Live App                         │
│                                                          │
│  • Authenticates via OAuth2 (browser login)              │
│  • Detects if ha_live_config integration is installed    │
│  • Fetches/syncs shared config if available              │
│  • Falls back to local-only mode if not                  │
│                                                          │
│  Profile Storage:                                        │
│  • SHARED: Synced with Home Assistant                    │
│  • LOCAL: Device-only, never synced                      │
└──────────────────────────────────────────────────────────┘
```

## Key Concepts

### Authentication Change
- **Before:** User manually creates long-lived token in HA, copies to app
- **After:** User enters HA URL, logs in via browser, app gets OAuth tokens

### Profile Sources
- **Shared profiles:** Stored in HA, visible to all household members, edits sync automatically
- **Local profiles:** Stored on device only, for personal/device-specific use

### Graceful Degradation
- If HACS integration not installed → app works exactly as before (local-only)
- If HA unreachable → app uses cached shared profiles (read-only) + local profiles
- If no shared Gemini key → user can enter their own local key

### Sync Model
- Fetch-on-launch: App fetches latest shared config when opened
- Push-on-save: Edits to shared profiles push to HA immediately
- Conflict handling: Warn if profile was edited by someone else

## Implementation Phases

| Phase | Summary |
|-------|---------|
| **1** | Replace manual token auth with OAuth2 browser login |
| **2** | Create the `ha_live_config` HACS integration |
| **3** | Detect integration, fetch shared Gemini API key |
| **4** | Sync shared profiles between app and HA |
| **5** | Polish: offline mode, sync status, conflicts, migration |

**Dependencies:** Phases 1 & 2 can run in parallel. Each subsequent phase depends on the previous.

## User Experience Goals

1. **New user with integration:** Enter URL → Log in → Ready (zero manual config)
2. **New user without integration:** Enter URL → Log in → Enter API key → Create profile
3. **Existing user, integration added:** Prompt to upload local profiles to shared
4. **Household member:** Sees profiles created by others, can use or edit them

## Files to Know

| File | Purpose |
|------|---------|
| `core/Profile.kt` | Profile data model |
| `core/ProfileManager.kt` | Profile CRUD, handles both local and shared |
| `core/GeminiConfig.kt` | Gemini API key storage |
| `core/HAConfig.kt` | Home Assistant URL/token storage |
| `services/HomeAssistantApiClient.kt` | REST API client for HA |
| `services/mcp/McpClientManager.kt` | MCP connection to HA |
| `ui/OnboardingActivity.kt` | First-run setup flow |
| `ui/ProfileManagementActivity.kt` | Profile list screen |
| `ui/ProfileEditorActivity.kt` | Profile edit screen |
| `HAGeminiApp.kt` | Application class, holds global state |

## Design Principles

1. **No breaking changes:** Existing local-only users unaffected
2. **Optional sharing:** Integration is optional, app works without it
3. **Offline-capable:** Cached data enables offline use
4. **Simple sync:** Last-write-wins, no complex merge logic
5. **Clear ownership:** Profiles show who last modified them
