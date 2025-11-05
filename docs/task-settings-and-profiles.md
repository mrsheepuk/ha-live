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

## Questions for Refinement

### 1. Settings Screen Access
**Question**: How should users access the Settings screen?
- Option A: Menu button (three dots) in the top-right corner of MainActivity
- Option B: Dedicated "Settings" button on the main screen (visible when ReadyToTalk)
- Option C: Both options available
- Option D: Other approach?

**My suggestion**: Option A (menu button) - this is the Android standard and keeps main screen clean.

---

### 2. Profile System - Storage & Structure
**Question**: How should we structure the profile data?

**Proposed structure**:
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

**Storage options**:
- Option A: Continue using SharedPreferences (serialize to JSON, store as string)
- Option B: Use Room database for better querying
- Option C: Store as individual JSON files in app's private storage

**My suggestion**: Option A for now (SharedPreferences with JSON) - simpler, no new dependencies. Can migrate to Room later if needed.

**Sub-questions**:
- Should there be a maximum number of profiles? (e.g., 10 profiles max)
- What happens to the current saved system prompt when we migrate to profiles?

---

### 3. Profile Selection UI on Main Screen
**Question**: Where/how should users select a profile on the main screen?

**Current main screen layout**:
- Status text at top
- System prompt container (currently takes up space with 200dp EditText)
- Main button (200dp circle "Start Chat")
- Tool log at bottom

**Options for profile selection**:
- Option A: Dropdown/Spinner above the main button (replaces system prompt container)
- Option B: Small dropdown in the top-right corner
- Option C: Show currently selected profile name as text, tap to open selection dialog
- Option D: Floating action button to switch profiles

**My suggestion**: Option A - prominent dropdown above main button, showing profile name. Easy to discover and use.

**Visual concept for Option A**:
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

**Sub-questions**:
- Should profile selection be disabled during active chat?
- Should switching profiles during chat stop the current session?

---

### 4. Settings Screen - Structure & Organization
**Question**: How should the Settings screen be organized?

**Proposed structure**:
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

**Alternative**: Single scrollable settings screen with inline editors (like Android system settings)

**My suggestion**: Start with the grouped approach (separate activities for complex sections). Easier to navigate and test.

**Sub-questions**:
- Should we show connection status in real-time on Settings screen?
- Should there be a "Test Connection" button for HA config?

---

### 5. Profile Management Screen
**Question**: What actions should be available in the Profile Management screen?

**Proposed features**:
- ✓ View list of all profiles (with name, preview of prompt)
- ✓ Create new profile (name + prompt editor)
- ✓ Edit existing profile (name + prompt)
- ✓ Delete profile (with confirmation, can't delete if it's the only one)
- ✓ Set default profile (used for new sessions)
- ✓ Duplicate profile (copy existing to create new one)

**UI approach**:
- Option A: RecyclerView list with swipe actions (swipe to delete, tap to edit)
- Option B: Simple list with context menu on long-press
- Option C: Card-based UI with action buttons visible on each card

**My suggestion**: Option C - most discoverable for users unfamiliar with swipe gestures.

**Sub-questions**:
- Should profiles show "last used" timestamp?
- Should we support importing/exporting profiles (JSON file)?
- Should there be preset/template profiles? (e.g., "Professional", "Casual", "Nerdy")

---

### 6. Profile Editor UI
**Question**: How should the profile editor work?

**Requirements**:
- Name input (short, e.g., "House Lizard")
- System prompt input (large multiline text, similar to current implementation)
- Save/Cancel buttons

**Options**:
- Option A: Separate ProfileEditorActivity (dedicated screen)
- Option B: Dialog with full-screen prompt editor
- Option C: Bottom sheet that expands to full screen for prompt editing

**My suggestion**: Option A - allows more space for prompt editing and better keyboard handling.

**Sub-questions**:
- Should there be a character count indicator for the prompt?
- Should we offer prompt templates or examples?
- Should there be a "Preview" button to test the prompt before saving?

---

### 7. Migration Strategy
**Question**: How should we handle existing users who upgrade to this version?

**Current state**:
- User has Firebase config saved
- User has HA config saved
- User MAY have a customized system prompt saved

**Migration approach**:
```kotlin
1. On first launch after upgrade:
   - Detect no profiles exist
   - Load existing system prompt (or default)
   - Create a default profile: "Default Profile"
   - Mark it as default and last used
   - Done!

2. Maintain backward compatibility with existing config storage
```

**Sub-questions**:
- Should we prompt the user about the new feature with a "What's New" dialog?
- Should we offer to rename "Default Profile" to something personalized?

---

### 8. Configuration Reconfiguration
**Question**: What should happen when a user changes Firebase or HA config?

**Current behavior**: Config is set once, never changed (no UI to change it)

**Proposed behavior**:

**For Firebase config change**:
- Stop any active session
- Show warning: "Changing Firebase config will restart the app"
- User picks new file → save → kill app (System.exit(0)) → user relaunches

**For HA config change**:
- Stop any active session
- Show editor dialog with current URL/token
- User saves → attempt to reconnect to MCP server
- If success: continue, if fail: show error, stay in Settings
- Need to re-fetch tools and reinitialize Gemini

**Sub-questions**:
- Should changing HA config automatically refetch tools and reinitialize?
- Should we show a loading indicator during reconnection?
- What if user is in a chat when they try to access Settings?

---

### 9. Main Screen Cleanup
**Question**: What should be removed from the main screen?

**To remove**:
- ❌ System prompt EditText (200dp multiline editor)
- ❌ Save/Reset prompt buttons
- ❌ Potentially: HA config input fields (only shown during initial setup)

**To keep**:
- ✓ Status text
- ✓ Main button (Start/Stop Chat)
- ✓ Tool log
- ✓ Profile selector (NEW)

**To add**:
- ✓ Settings menu button (top-right)
- ✓ Profile dropdown/selector

**Questions**:
- Should the initial setup flow (Firebase → HA) remain on MainActivity, or move to separate onboarding screens?
- Should we add any other quick actions to the main screen? (e.g., "Clear tool log")

---

### 10. UX Flow
**Question**: Confirm the complete user flow:

**First-time user**:
```
1. Launch app
2. See "Import google-services.json" button (MainActivity)
3. Pick file → saved
4. See HA config inputs (MainActivity)
5. Enter URL/token → saved → connects to MCP
6. Automatically create "Default Profile" with default prompt
7. See main screen with profile dropdown + Start Chat button
8. Ready to use!
```

**Existing user (after upgrade)**:
```
1. Launch app
2. Migration: Create "Default Profile" from existing prompt
3. See main screen with new profile dropdown
4. Can now access Settings → Manage Profiles
```

**Regular user (configured)**:
```
1. Launch app
2. See main screen with profile dropdown
3. Select profile (optional, uses default/last used)
4. Tap "Start Chat"
5. Use voice assistant
6. Tap menu → Settings to configure profiles/settings
```

**Is this flow acceptable?**

---

### 11. Profile Selection Behavior
**Question**: When should the selected profile be used?

**Options**:
- Option A: Profile selected on main screen is used when "Start Chat" is pressed
  - Gemini is re-initialized with new prompt if different from last chat
- Option B: Profile is "loaded" immediately when selected (re-initializes Gemini)
  - "Start Chat" always uses the currently loaded profile
- Option C: Profile selection just sets a preference, Gemini initialized on "Start Chat"

**My suggestion**: Option A - lazy initialization only when starting chat. Allows quick profile switching without unnecessary initialization.

**Sub-questions**:
- Should we show a brief "Initializing profile..." message when switching?
- Should recently used profiles appear at the top of the dropdown?

---

### 12. Settings Screen Implementation
**Question**: Should Settings be a new Activity or a Fragment?

**Options**:
- Option A: New SettingsActivity with PreferenceFragmentCompat (Android standard)
- Option B: Simple custom Activity with regular Views
- Option C: Fragment-based navigation (requires adding Navigation component)

**My suggestion**: Option B - Simple SettingsActivity with custom UI. The app is small and doesn't need the complexity of PreferenceFragmentCompat or Navigation.

**Sub-questions**:
- Should Settings be accessible during a chat session? (Probably "No" - require stopping chat first)

---

### 13. Default Profile Behavior
**Question**: How should "default profile" work?

**Options**:
- Option A: Default profile is auto-selected on app launch
- Option B: App remembers last-used profile, falls back to default if deleted
- Option C: Always show profile dropdown with no auto-selection

**My suggestion**: Option B - remember last used, more intuitive for regular use.

**Sub-questions**:
- Can users un-set the default (no default profile)?
- What happens if the last-used profile is deleted?

---

### 14. Error Handling
**Question**: How should we handle errors during profile operations?

**Scenarios**:
- User tries to delete the only remaining profile
- User creates profile with duplicate name
- User tries to save profile with empty name
- User tries to save profile with empty system prompt
- Profile data becomes corrupted

**Proposed handling**:
- Show friendly error dialog for each case
- Prevent destructive actions (can't delete last profile)
- Auto-generate name if empty? ("Profile 1", "Profile 2")
- Allow empty prompt (uses empty string, Gemini will handle)

**Acceptable?**

---

### 15. Implementation Phases
**Question**: Should this be implemented in multiple phases or all at once?

**Proposed phases**:

**Phase 1: Core Settings Screen**
- Create SettingsActivity with basic navigation
- Move HA config editing to Settings
- Move Firebase config change to Settings
- Add menu button to MainActivity

**Phase 2: Profile System**
- Create Profile data model
- Create ProfileManager class (handles storage/CRUD)
- Create ProfileManagementActivity (list profiles)
- Create ProfileEditorActivity (edit/create profile)
- Implement migration for existing system prompt

**Phase 3: Main Screen Integration**
- Add profile dropdown to MainActivity
- Remove system prompt editor from MainActivity
- Update MainViewModel to work with selected profile
- Handle profile switching and initialization

**Phase 4: Polish & Testing**
- Add default profile templates
- Improve error handling
- Add "What's New" dialog for upgrades
- Testing & bug fixes

**Is this phased approach acceptable, or prefer all-at-once?**

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

## Open Questions / Discussion Points

1. **Profile sharing**: Should users be able to export/import profiles to share with others?
2. **Cloud sync**: Future feature to sync profiles across devices? (Out of scope for this task)
3. **Profile icons**: Should profiles have custom icons or colors for visual distinction?
4. **Quick profile switch**: Should there be a quick-switch gesture during chat (e.g., swipe)?
5. **Profile variables**: Should prompts support variables like `{{USER_NAME}}` that can be set per-profile?

---

## Next Steps

Once you've reviewed and answered these questions, I'll create:
1. Detailed implementation plan with file changes
2. Updated architecture diagram
3. Step-by-step implementation tasks

**Please provide your answers/preferences for the questions above, and feel free to add any additional requirements or concerns!**
