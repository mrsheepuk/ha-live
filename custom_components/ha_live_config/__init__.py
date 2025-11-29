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
