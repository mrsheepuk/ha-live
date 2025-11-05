# Task: Settings Page & Profile System

## Overview
Separate the "configuration" experience from the "usage" experience by introducing a dedicated Settings page and implementing a multi-profile system for different AI personalities.

## Current State Analysis
- **Main Screen**: Handles both configuration (Firebase setup, HA config, system prompt editing) and usage (voice chat)
- **System Prompt**: Currently edited directly on main screen with Save/Reset buttons (200dp EditText)
- **Config Flow**: Firebase → HA → ReadyToTalk (one-time setup, no way to change afterwards)
- **Storage**: Uses SharedPreferences (FirebaseConfig, HAConfig, SystemPromptConfig)

## Goals
1. **Separate concerns**: Main screen = "use the app", Settings = "configure the app"
2. **Multi-profile support**: Allow users to create multiple "personalities" with different system prompts
3. **Reconfiguration**: Allow changing Firebase/HA config after initial setup
4. **Better UX**: Cleaner main screen focused on the conversation experience

---

## Design Decisions

### 1. Settings Screen Access
**Approach**: Menu button (three dots) in the top-right corner of MainActivity
- Standard Android pattern
- Keeps main screen clean and focused on chat functionality

---

### 2. Profile System - Storage & Structure
**Approach**: SharedPreferences with JSON serialization

**Data structure**:
```kotlin
data class Profile(
    val id: String,           // UUID
    val name: String,         // "House Lizard", "Professional Assistant", etc.
    val systemPrompt: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long
)
```

**Rationale**:
- Simple implementation, no new dependencies
- Consistent with existing config storage (FirebaseConfig, HAConfig, SystemPromptConfig)
- Can migrate to Room database later if needed

**Open question**:
- Should there be a maximum number of profiles? (e.g., 10 profiles max)

---

### 3. Profile Selection UI on Main Screen
**Approach**: Dropdown/Spinner above the main button (replaces system prompt container)

**Current main screen layout**:
- Status text at top
- System prompt container (200dp EditText) ← **TO BE REMOVED**
- Main button (200dp circle "Start Chat")
- Tool log at bottom

**New layout**:
```
[Status: Ready to chat]

┌─────────────────────────────┐
│ Profile: House Lizard    ▼ │  <-- Spinner/dropdown
└─────────────────────────────┘

      ┌───────────┐
      │           │
      │   START   │  <-- Main button
      │   CHAT    │
      └───────────┘

[Tool Log Area]
```

**Rationale**:
- Prominent and easy to discover
- Replaces the system prompt editor (which moves to Settings)
- Clear visual hierarchy

**Open questions**:
- Should profile selection be disabled during active chat?
- Should switching profiles during chat stop the current session?

---

### 4. Settings Screen - Structure & Organization
**Approach**: Grouped sections with separate activities for complex management

**Structure**:
```
Settings Screen
├── Profiles Section
│   ├── "Manage Profiles" button → opens ProfileManagementActivity
│   └── Quick summary: "5 profiles configured"
│
├── Home Assistant Section
│   ├── Current URL (displayed, tap to edit)
│   ├── Token status: "✓ Configured" (tap to change)
│   └── "Reconnect" button (tests connection)
│
└── Firebase Section
    ├── Project ID: "my-project-123" (displayed)
    └── "Change Firebase Config" button → file picker
```

**Rationale**:
- Grouped approach is easier to navigate and test
- Separate activities for complex sections (like profile management)
- Consistent with simple app architecture (no Navigation component needed)

**Open questions**:
- Should we show connection status in real-time on Settings screen?
- Should there be a "Test Connection" button for HA config?

---

### 5. Profile Management Screen
**Approach**: Card-based UI with visible action buttons

**Features**:
- ✓ View list of all profiles (with name, preview of prompt)
- ✓ Create new profile (name + prompt editor)
- ✓ Edit existing profile (name + prompt)
- ✓ Delete profile (with confirmation, can't delete if it's the only one)
- ✓ Set default profile (used for new sessions)
- ✓ Duplicate profile (copy existing to create new one)

**Rationale**:
- Card-based UI is most discoverable for users unfamiliar with swipe gestures
- Action buttons are visible and clear
- Supports all required CRUD operations

**Open questions**:
- Should profiles show "last used" timestamp?
- Should we support importing/exporting profiles (JSON file)?
- Should there be preset/template profiles? (e.g., "Professional", "Casual", "Nerdy")

---

### 6. Profile Editor UI
**Approach**: Separate ProfileEditorActivity (dedicated screen)

**Components**:
- Name input (short, e.g., "House Lizard")
- System prompt input (large multiline text, similar to current implementation)
- Save/Cancel buttons

**Rationale**:
- Dedicated screen allows more space for prompt editing
- Better keyboard handling than dialogs
- Consistent with other separate activities (ProfileManagementActivity)

**Open questions**:
- Should there be a character count indicator for the prompt?
- Should we offer prompt templates or examples?
- Should there be a "Preview" button to test the prompt before saving?

---

### 7. Configuration Reconfiguration
**Current behavior**: Config is set once during initial setup, never changed (no UI to change it)

**New behavior**:

**For Firebase config change**:
- Stop any active session
- Show warning: "Changing Firebase config will restart the app"
- User picks new file → save → kill app (System.exit(0)) → user relaunches
- App reinitializes with new Firebase config

**For HA config change**:
- Stop any active session
- Show editor dialog with current URL/token
- User saves → attempt to reconnect to MCP server
- If success: continue, if fail: show error, stay in Settings
- Re-fetch tools and reinitialize Gemini with current profile

**Open questions**:
- Should changing HA config automatically refetch tools and reinitialize?
- Should we show a loading indicator during reconnection?
- What if user is in a chat when they try to access Settings?

---

### 8. Main Screen Cleanup

**To remove**:
- ❌ System prompt EditText (200dp multiline editor)
- ❌ Save/Reset prompt buttons
- ❌ HA config input fields (moves to Settings for reconfiguration)

**To keep**:
- ✓ Status text
- ✓ Main button (Start/Stop Chat)
- ✓ Tool log
- ✓ Initial setup flow (Firebase → HA) for first-time users

**To add**:
- ✓ Settings menu button (top-right)
- ✓ Profile dropdown/selector (replaces system prompt container)

**Rationale**:
- Significantly cleaner UI focused on usage
- Configuration moved to dedicated Settings screen
- Initial setup remains on MainActivity for simplicity

**Open questions**:
- Should the initial setup flow (Firebase → HA) remain on MainActivity, or move to separate onboarding screens?
- Should we add any other quick actions to the main screen? (e.g., "Clear tool log")

---

### 9. UX Flow

**First-time user**:
```
1. Launch app
2. See "Import google-services.json" button (MainActivity)
3. Pick file → saved
4. See HA config inputs (MainActivity)
5. Enter URL/token → saved → connects to MCP
6. Automatically create "Default Profile" with default system prompt
7. See main screen with profile dropdown + Start Chat button
8. Ready to use!
```

**Regular user (app configured)**:
```
1. Launch app
2. See main screen with profile dropdown
3. Select profile (optional, uses default/last used)
4. Tap "Start Chat"
5. Use voice assistant
6. Tap menu → Settings to configure profiles/settings
```

**Creating additional profiles**:
```
1. Tap menu → Settings
2. Tap "Manage Profiles"
3. Tap "+" or "Create New Profile"
4. Enter name and system prompt
5. Save
6. Return to main screen, new profile available in dropdown
```

---

### 10. Profile Selection Behavior
**Approach**: Lazy initialization - profile is applied when "Start Chat" is pressed

**Behavior**:
- User selects profile from dropdown (just updates selection, no initialization yet)
- User taps "Start Chat"
- If profile differs from last session: re-initialize Gemini with new system prompt
- If same profile: continue with existing initialization
- Start chat session

**Rationale**:
- Allows quick profile switching without unnecessary re-initialization
- Only initializes when actually needed (starting chat)
- Clearer separation between selection and usage

**Open questions**:
- Should we show a brief "Initializing profile..." message when switching?
- Should recently used profiles appear at the top of the dropdown?

---

### 11. Settings Screen Implementation
**Approach**: Simple SettingsActivity with custom UI (regular Views)

**Rationale**:
- App is small and doesn't need PreferenceFragmentCompat complexity
- No need for Navigation component
- Custom UI allows better control over layout and interactions
- Consistent with existing simple architecture

**Open question**:
- Should Settings be accessible during a chat session? (Probably "No" - require stopping chat first)

---

### 12. Default Profile Behavior
**Approach**: Remember last-used profile, fall back to default if deleted

**Behavior**:
- App remembers the last profile used in a chat session
- On app launch, that profile is pre-selected in the dropdown
- If that profile was deleted: fall back to the profile marked as "default"
- If no default is set: select the first available profile

**Rationale**:
- More intuitive for regular use (doesn't require re-selecting each time)
- Default profile acts as a safety fallback
- Balances convenience with flexibility

**Open questions**:
- Can users un-set the default (no default profile)?
- What happens if the last-used profile is deleted?

---

### 13. Error Handling

**Scenarios and handling**:

| Scenario | Handling |
|----------|----------|
| User tries to delete the only remaining profile | Show error dialog: "Cannot delete the last profile. Create another profile first." Disable delete button if only one profile exists. |
| User creates profile with duplicate name | Show error dialog: "A profile with this name already exists. Please choose a different name." |
| User tries to save profile with empty name | Auto-generate name: "Profile 1", "Profile 2", etc. OR show error and require name. |
| User tries to save profile with empty system prompt | Allow empty prompt (uses empty string, Gemini will handle it). Show warning: "Are you sure you want to save an empty prompt?" |
| Profile data becomes corrupted | Show error on app launch, offer to "Reset profiles to default" (creates single default profile) |

**Open question**:
- Should we auto-generate names for empty profile names, or require the user to provide a name?

---

### 14. Implementation Phases
**Approach**: Phased implementation (4 phases)

**Phase 1: Core Settings Screen**
- Create SettingsActivity with basic navigation
- Move HA config editing to Settings
- Move Firebase config change to Settings
- Add menu button to MainActivity
- Basic layout and navigation structure

**Phase 2: Profile System Backend**
- Create Profile data model
- Create ProfileManager class (handles storage/CRUD operations)
- Unit tests for ProfileManager
- Auto-create default profile on first run (if no profiles exist)

**Phase 3: Profile UI & Main Screen Integration**
- Create ProfileManagementActivity (list profiles)
- Create ProfileEditorActivity (edit/create profile)
- Add profile dropdown to MainActivity
- Remove system prompt editor from MainActivity
- Update MainViewModel to work with selected profile
- Handle profile switching and initialization

**Phase 4: Polish & Testing**
- Add preset/template profiles (if desired)
- Implement all error handling (duplicate names, empty fields, etc.)
- Connection status indicators on Settings screen
- Comprehensive testing & bug fixes

**Rationale**:
- Safer implementation with smaller, testable increments
- Can validate each phase before moving to next
- Easier to debug and troubleshoot
- Settings screen can be useful even before profiles are fully implemented

---

## Technical Considerations

### State Management
- Add `selectedProfileId` to MainViewModel
- Settings/Profile changes should notify MainActivity to refresh

### Testing Strategy
- Unit tests for ProfileManager (CRUD operations)
- Integration tests for profile selection → Gemini initialization
- Manual testing for Settings → MCP reconnection

### Dependencies
- No new external dependencies needed
- Uses existing: SharedPreferences, JSON serialization, LiveData/Flow

---

## Summary of Open Questions

Throughout the design decisions above, the following questions remain to be answered:

### Profile System
1. Should there be a maximum number of profiles? (e.g., 10 profiles max)
2. Should profiles show "last used" timestamp?
3. Should we support importing/exporting profiles (JSON file)?
4. Should there be preset/template profiles? (e.g., "Professional", "Casual", "Nerdy")

### Profile Editor
5. Should there be a character count indicator for the prompt?
6. Should we offer prompt templates or examples?
7. Should there be a "Preview" button to test the prompt before saving?

### Profile Selection
8. Should profile selection be disabled during active chat?
9. Should switching profiles during chat stop the current session?
10. Should we show a brief "Initializing profile..." message when switching?
11. Should recently used profiles appear at the top of the dropdown?

### Settings & Configuration
12. Should we show connection status in real-time on Settings screen?
13. Should there be a "Test Connection" button for HA config?
14. Should changing HA config automatically refetch tools and reinitialize?
15. Should we show a loading indicator during reconnection?
16. What if user is in a chat when they try to access Settings?
17. Should Settings be accessible during a chat session?

### Main Screen
18. Should the initial setup flow (Firebase → HA) remain on MainActivity, or move to separate onboarding screens?
19. Should we add any other quick actions to the main screen? (e.g., "Clear tool log")

### Default Profile & Error Handling
20. Can users un-set the default (no default profile)?
21. What happens if the last-used profile is deleted?
22. Should we auto-generate names for empty profile names, or require the user to provide a name?

### Future Considerations (Out of Scope)
- Profile sharing: Export/import profiles to share with others?
- Cloud sync: Sync profiles across devices?
- Profile icons/colors: Visual distinction for profiles?
- Quick profile switch gesture during chat?
- Profile variables: Support for `{{USER_NAME}}` style variables?

---

## Next Steps

Once you've reviewed and answered the open questions above, the next steps are:
1. Create detailed implementation plan with specific file changes for each phase
2. Begin Phase 1 implementation (Core Settings Screen)
3. Iteratively implement and test each subsequent phase
