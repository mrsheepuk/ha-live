# Phase 2: HACS Integration Development

## Goal

Create a Home Assistant custom integration (`ha_live_config`) that stores shared configuration (Gemini API key and conversation profiles) for the HA Live app. This integration will be distributed via HACS.

## Context

HA Live is an Android voice assistant app that connects to Home Assistant. Currently, each user configures their own Gemini API key and profiles locally on their device. This phase creates a Home Assistant integration that allows household members to share configuration.

## What to Build

A custom Home Assistant integration that:
1. Stores a shared Gemini API key
2. Stores conversation profiles (name, system prompt, personality, model settings, etc.)
3. Exposes services that the Android app can call via HA's REST API
4. Persists data across Home Assistant restarts

## Integration Structure

```
custom_components/
└── ha_live_config/
    ├── __init__.py          # Main integration setup
    ├── manifest.json         # Integration metadata
    ├── services.yaml         # Service definitions
    └── const.py              # Constants
```

## File Contents

### `const.py`

```python
"""Constants for HA Live Config integration."""
DOMAIN = "ha_live_config"
STORAGE_VERSION = 1
STORAGE_KEY = "ha_live_config"
PROFILE_SCHEMA_VERSION = 1
```

### `manifest.json`

```json
{
  "domain": "ha_live_config",
  "name": "HA Live Config",
  "version": "1.0.0",
  "documentation": "https://github.com/mrsheepuk/ha-live-config",
  "issue_tracker": "https://github.com/mrsheepuk/ha-live-config/issues",
  "dependencies": [],
  "codeowners": ["@mrsheepuk"],
  "requirements": [],
  "iot_class": "local_push",
  "integration_type": "service"
}
```

### `services.yaml`

```yaml
get_config:
  name: Get Configuration
  description: Retrieve all HA Live shared configuration including Gemini API key and profiles.

set_gemini_key:
  name: Set Gemini API Key
  description: Set or update the shared Gemini API key.
  fields:
    api_key:
      name: API Key
      description: The Gemini API key to store. Pass null/empty to clear.
      required: false
      example: "AIzaSy..."
      selector:
        text:

upsert_profile:
  name: Create/Update Profile
  description: Create a new profile or update an existing one. Returns the profile ID.
  fields:
    profile:
      name: Profile
      description: The profile data as a JSON object.
      required: true
      example: |
        {
          "name": "Default Assistant",
          "system_prompt": "You are a helpful assistant...",
          "personality": "Friendly and concise",
          "model": "gemini-2.0-flash-exp",
          "voice": "Aoede"
        }
      selector:
        object:

delete_profile:
  name: Delete Profile
  description: Delete a profile by its ID.
  fields:
    profile_id:
      name: Profile ID
      description: The UUID of the profile to delete.
      required: true
      example: "550e8400-e29b-41d4-a716-446655440000"
      selector:
        text:

check_profile_name:
  name: Check Profile Name
  description: Check if a profile name is available (case-insensitive).
  fields:
    name:
      name: Name
      description: The profile name to check.
      required: true
      selector:
        text:
    exclude_id:
      name: Exclude ID
      description: Optional profile ID to exclude from the check (useful when updating).
      required: false
      selector:
        text:
```

### `__init__.py`

```python
"""HA Live Config - Shared configuration for HA Live voice assistant."""
from __future__ import annotations

import logging
import uuid
from typing import Any

from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.helpers.storage import Store
from homeassistant.helpers.typing import ConfigType
import homeassistant.util.dt as dt_util

from .const import DOMAIN, STORAGE_KEY, STORAGE_VERSION, PROFILE_SCHEMA_VERSION

_LOGGER = logging.getLogger(__name__)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up the HA Live Config integration."""
    store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    data = await store.async_load()

    if data is None:
        data = {
            "schema_version": PROFILE_SCHEMA_VERSION,
            "gemini_api_key": None,
            "profiles": []
        }

    hass.data[DOMAIN] = {
        "store": store,
        "data": data
    }

    async def _save() -> None:
        """Persist data to storage."""
        await store.async_save(hass.data[DOMAIN]["data"])

    # -------------------------------------------------------------------------
    # Service: get_config
    # -------------------------------------------------------------------------
    @callback
    def handle_get_config(call: ServiceCall) -> dict[str, Any]:
        """Return all shared configuration."""
        data = hass.data[DOMAIN]["data"]
        return {
            "gemini_api_key": data.get("gemini_api_key"),
            "profiles": data.get("profiles", [])
        }

    # -------------------------------------------------------------------------
    # Service: set_gemini_key
    # -------------------------------------------------------------------------
    async def handle_set_gemini_key(call: ServiceCall) -> None:
        """Set or update the shared Gemini API key."""
        api_key = call.data.get("api_key")

        # Basic validation
        if api_key is not None and api_key != "" and not api_key.startswith("AIza"):
            _LOGGER.warning("API key doesn't look like a valid Gemini key")

        # Allow empty string to clear the key
        if api_key == "":
            api_key = None

        hass.data[DOMAIN]["data"]["gemini_api_key"] = api_key
        await _save()
        _LOGGER.info("Gemini API key %s", "set" if api_key else "cleared")

    # -------------------------------------------------------------------------
    # Service: upsert_profile
    # -------------------------------------------------------------------------
    async def handle_upsert_profile(call: ServiceCall) -> dict[str, Any]:
        """Create or update a profile."""
        profile = dict(call.data.get("profile", {}))
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        # Validate required fields
        if not profile.get("name"):
            raise ValueError("Profile must have a name")

        # Check for name collision (different ID, same name - case insensitive)
        profile_id = profile.get("id")
        existing_with_name = next(
            (p for p in profiles
             if p["name"].lower() == profile["name"].lower()
             and p["id"] != profile_id),
            None
        )
        if existing_with_name:
            raise ValueError(f"A profile named '{profile['name']}' already exists")

        # Generate ID if new profile
        if not profile_id:
            profile["id"] = str(uuid.uuid4())

        # Add metadata
        profile["last_modified"] = dt_util.utcnow().isoformat()
        profile["schema_version"] = PROFILE_SCHEMA_VERSION

        # Get user info if available
        if call.context.user_id:
            user = await hass.auth.async_get_user(call.context.user_id)
            profile["modified_by"] = user.name if user else None
        else:
            profile["modified_by"] = None

        # Upsert logic
        existing_idx = next(
            (i for i, p in enumerate(profiles) if p["id"] == profile["id"]),
            None
        )

        if existing_idx is not None:
            profiles[existing_idx] = profile
            _LOGGER.info("Updated profile: %s", profile["name"])
        else:
            profiles.append(profile)
            _LOGGER.info("Created profile: %s", profile["name"])

        await _save()
        return {"id": profile["id"]}

    # -------------------------------------------------------------------------
    # Service: delete_profile
    # -------------------------------------------------------------------------
    async def handle_delete_profile(call: ServiceCall) -> None:
        """Delete a profile by ID."""
        profile_id = call.data.get("profile_id")
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        original_count = len(profiles)
        hass.data[DOMAIN]["data"]["profiles"] = [
            p for p in profiles if p["id"] != profile_id
        ]

        if len(hass.data[DOMAIN]["data"]["profiles"]) < original_count:
            await _save()
            _LOGGER.info("Deleted profile: %s", profile_id)
        else:
            _LOGGER.warning("Profile not found for deletion: %s", profile_id)

    # -------------------------------------------------------------------------
    # Service: check_profile_name
    # -------------------------------------------------------------------------
    @callback
    def handle_check_profile_name(call: ServiceCall) -> dict[str, bool]:
        """Check if a profile name is available."""
        name = call.data.get("name", "")
        exclude_id = call.data.get("exclude_id")
        profiles = hass.data[DOMAIN]["data"]["profiles"]

        name_taken = any(
            p["name"].lower() == name.lower() and p["id"] != exclude_id
            for p in profiles
        )

        return {"available": not name_taken}

    # Register all services
    hass.services.async_register(
        DOMAIN, "get_config", handle_get_config,
        supports_response="only"
    )
    hass.services.async_register(
        DOMAIN, "set_gemini_key", handle_set_gemini_key
    )
    hass.services.async_register(
        DOMAIN, "upsert_profile", handle_upsert_profile,
        supports_response="optional"
    )
    hass.services.async_register(
        DOMAIN, "delete_profile", handle_delete_profile
    )
    hass.services.async_register(
        DOMAIN, "check_profile_name", handle_check_profile_name,
        supports_response="only"
    )

    _LOGGER.info("HA Live Config integration loaded successfully")
    return True
```

## Profile Data Schema

The app will send profiles with this structure:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Default Assistant",
  "system_prompt": "You are a helpful home assistant...",
  "personality": "Friendly, concise, and helpful",
  "background_info": "Current time: {{ now().strftime('%H:%M') }}",
  "model": "gemini-2.0-flash-exp",
  "voice": "Aoede",
  "tool_filter_mode": "ALL",
  "selected_tools": [],
  "include_live_context": true,
  "enable_transcription": false,
  "auto_start_chat": false,
  "initial_message": ""
}
```

The integration adds these fields automatically:
- `last_modified`: ISO 8601 timestamp
- `modified_by`: Home Assistant username
- `schema_version`: For future migrations

## Storage Location

Data is stored in: `config/.storage/ha_live_config`

This is Home Assistant's standard storage location for integrations, persisted across restarts.

## How the App Will Call These Services

Via Home Assistant's REST API:

```http
POST /api/services/ha_live_config/get_config
Authorization: Bearer {token}
Content-Type: application/json

{}
```

Response contains the service return value.

## Installation Methods

### Method 1: HACS (Recommended)

1. Create GitHub repo: `mrsheepuk/ha-live-config`
2. Add to HACS as custom repository, or submit to HACS default
3. Users install via HACS UI

### Method 2: Manual

1. Copy `custom_components/ha_live_config/` to HA's `config/custom_components/`
2. Restart Home Assistant
3. Integration auto-loads (no configuration.yaml needed)

## Testing the Integration

### Manual Testing via Developer Tools

In Home Assistant → Developer Tools → Services:

1. **Test get_config:**
   - Service: `ha_live_config.get_config`
   - Returns current config (empty initially)

2. **Test set_gemini_key:**
   - Service: `ha_live_config.set_gemini_key`
   - Data: `api_key: "AIzaTestKey123"`

3. **Test upsert_profile (create):**
   - Service: `ha_live_config.upsert_profile`
   - Data:
     ```yaml
     profile:
       name: "Test Profile"
       system_prompt: "You are helpful"
       model: "gemini-2.0-flash-exp"
     ```

4. **Test check_profile_name:**
   - Service: `ha_live_config.check_profile_name`
   - Data: `name: "Test Profile"`
   - Should return `available: false`

5. **Test upsert_profile (update):**
   - Use the ID returned from create
   - Change some fields

6. **Test delete_profile:**
   - Service: `ha_live_config.delete_profile`
   - Data: `profile_id: "{uuid-from-above}"`

### Automated Tests

Create `tests/` directory with pytest tests:

```python
# tests/test_init.py
import pytest
from custom_components.ha_live_config import async_setup
from custom_components.ha_live_config.const import DOMAIN

async def test_setup(hass):
    """Test integration setup."""
    assert await async_setup(hass, {})
    assert DOMAIN in hass.data

async def test_get_config_empty(hass):
    """Test get_config returns empty config initially."""
    await async_setup(hass, {})
    # Call service and verify response

async def test_upsert_profile_creates(hass):
    """Test creating a new profile."""
    # ...

async def test_upsert_profile_name_conflict(hass):
    """Test that duplicate names are rejected."""
    # ...

async def test_delete_profile(hass):
    """Test deleting a profile."""
    # ...
```

## Acceptance Criteria

1. [ ] Integration installs without errors
2. [ ] `get_config` returns stored data correctly
3. [ ] `set_gemini_key` persists key across HA restarts
4. [ ] `upsert_profile` creates new profiles with generated UUID
5. [ ] `upsert_profile` updates existing profiles by ID
6. [ ] `upsert_profile` rejects duplicate names (case-insensitive)
7. [ ] `upsert_profile` records `modified_by` from authenticated user
8. [ ] `delete_profile` removes profiles correctly
9. [ ] `check_profile_name` correctly identifies available/taken names
10. [ ] All data persists across Home Assistant restarts
11. [ ] Integration works with HACS installation

## Repository Structure for HACS

```
ha-live-config/
├── README.md
├── LICENSE
├── hacs.json
├── custom_components/
│   └── ha_live_config/
│       ├── __init__.py
│       ├── manifest.json
│       ├── services.yaml
│       └── const.py
└── tests/
    └── test_init.py
```

### `hacs.json`

```json
{
  "name": "HA Live Config",
  "render_readme": true
}
```

## Dependencies

- None (standard HA APIs only)

## What This Enables

- Phase 3: App can detect this integration and fetch shared config
- Phase 4: App can sync profiles with Home Assistant
- Household members share configuration automatically
