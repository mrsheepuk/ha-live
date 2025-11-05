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
- No maximum profile limit

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

**Behavior during chat**:
- Profile selection is **disabled** during active chat session
- User must stop chat to switch profiles
- Prevents issues with Gemini system prompt immutability during sessions

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

**Access during chat**:
- Settings is **accessible during active chat**
- All settings shown as **read-only** with message: "Stop chat to modify settings"
- Allows user to view current configuration without disrupting session

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

**Additional features**:
- **Copy to clipboard**: Button to copy profile JSON to clipboard for easy sharing
- No import/export files (clipboard is simpler)
- No "last used" timestamps displayed
- No preset/template profiles in initial version

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

**Initial version scope**:
- No character count indicator
- No prompt templates or examples
- No "Preview" button
- Simple, focused editing experience

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
- Show editor dialog with current URL/token (read-only if chat active)
- User saves new config (only allowed when chat stopped)
- **Test Connection button**: Attempts to pull tools from HA using new config
- Show loading indicator during test: "Testing connection..."
- If success: Save config and show success message
- If fail: Show error, don't save, stay in editor
- **Note**: Tools are fetched fresh when each chat session starts, not on config save

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
- Initial setup moves to separate OnboardingActivity (see section 9)

**Future enhancements**:
- Quick action buttons (e.g., "Clear tool log") - deferred to future version

---

### 9. UX Flow

**First-time user** (with OnboardingActivity):
```
1. Launch app → MainActivity checks configuration
2. Not configured → Launch OnboardingActivity
3. Onboarding Step 1: Welcome + "Import google-services.json"
4. Onboarding Step 2: Enter HA URL/token + Test Connection
5. Onboarding Step 3: Auto-create "Default Profile" with default system prompt
6. Navigate to MainActivity (now configured)
7. See main screen with profile dropdown + Start Chat button
8. Ready to use!
```

**Benefits of OnboardingActivity**:
- Reuses configuration UI components from SettingsActivity
- Progress indicators (Step 1/3, 2/3, 3/3)
- Clean separation: MainActivity = usage, Settings = reconfiguration, Onboarding = first-time setup
- Can add welcome messages or tutorial hints without cluttering MainActivity

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
- No "Initializing profile..." message needed (happens during "Start Chat" action)
- Dropdown shows profiles in stored order (no special sorting by recency in initial version)

---

### 11. Settings Screen Implementation
**Approach**: Simple SettingsActivity with custom UI (regular Views)

**Rationale**:
- App is small and doesn't need PreferenceFragmentCompat complexity
- No need for Navigation component
- Custom UI allows better control over layout and interactions
- Consistent with existing simple architecture
- Accessible during chat but in read-only mode (see section 4)

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

**Rules**:
- Users **cannot** un-set the default - there must always be one default profile
- If last-used profile is deleted: fall back to the default profile
- At least one profile must exist at all times

---

### 13. Error Handling

**Scenarios and handling**:

| Scenario | Handling |
|----------|----------|
| User tries to delete the only remaining profile | Show error dialog: "Cannot delete the last profile. Create another profile first." Disable delete button if only one profile exists. |
| User creates profile with duplicate name | Show error dialog: "A profile with this name already exists. Please choose a different name." |
| User tries to save profile with empty name | Show error dialog: "Profile name is required." Require user to provide a name before saving. |
| User tries to save profile with empty system prompt | Allow empty prompt (uses empty string, Gemini will handle it). Show warning: "Are you sure you want to save an empty prompt?" |
| Profile data becomes corrupted | Show error on app launch, offer to "Reset profiles to default" (creates single default profile) |

---

### 14. Implementation Phases
**Approach**: Phased implementation (4 phases)

**Phase 1: Core Settings Screen & Onboarding**
- Create OnboardingActivity for first-time setup (Firebase + HA config)
- Create SettingsActivity with basic navigation
- Move HA config editing to Settings (with Test Connection button)
- Move Firebase config change to Settings
- Add menu button to MainActivity
- Implement read-only mode for Settings during active chat
- Basic layout and navigation structure

**Phase 2: Profile System Backend**
- Create Profile data model
- Create ProfileManager class (handles storage/CRUD operations)
- Unit tests for ProfileManager
- Auto-create default profile on first run (if no profiles exist)

**Phase 3: Profile UI & Main Screen Integration**
- Create ProfileManagementActivity (list profiles with card-based UI)
- Create ProfileEditorActivity (edit/create profile)
- Implement "Copy to clipboard" feature for profiles
- Add profile dropdown to MainActivity
- Remove system prompt editor from MainActivity
- Update MainViewModel to work with selected profile
- Handle profile switching and initialization
- Disable profile selection during active chat

**Phase 4: Polish & Testing**
- Implement all error handling (duplicate names, empty fields, etc.)
- Add confirmation dialogs (delete profile, empty prompt warning)
- Handle corrupted profile data recovery
- Comprehensive testing & bug fixes
- UI polish and refinements

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

## Future Features

The following features are deferred to future versions after the initial implementation:

### Profile Enhancements
- **Preset/template profiles**: Pre-built personality templates (e.g., "Professional", "Casual", "Nerdy")
- **Profile icons/colors**: Visual distinction for different profiles
- **Recently used sorting**: Show most recently used profiles at top of dropdown
- **Character count indicator**: Display prompt length in editor
- **Prompt templates/examples**: Built-in examples to help users get started
- **Preview/test button**: Test a prompt before saving it

### Main Screen Enhancements
- **Quick actions**: Buttons for common tasks (e.g., "Clear tool log")

### Future Direction - Profile Variables
**Coming very soon** (next major feature):
- Support for variables in prompts using Home Assistant templating
- Example: `{{USER_NAME}}`, `{{LOCATION}}`, etc.
- Will allow dynamic, context-aware system prompts

### Not Planned
- **Profile import/export files**: Not needed - users can copy/paste JSON via clipboard
- **Cloud sync**: Not needed - clipboard sharing is sufficient for the use case
- **Quick profile switching during chat**: Not possible - Gemini system prompts are immutable during sessions

---

## Next Steps

All design decisions have been finalized. Ready to proceed with implementation:

1. **Create detailed implementation plan** with specific file changes for Phase 1
2. **Begin Phase 1 implementation**: OnboardingActivity + SettingsActivity
3. **Iteratively implement** remaining phases with testing between each
4. **Track progress** and adjust as needed

The next document will break down Phase 1 into specific implementation tasks with file-by-file changes.
