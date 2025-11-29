"""Config flow for HA Live Config integration."""
from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult

from .const import DOMAIN


class HALiveConfigFlowHandler(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for HA Live Config."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Handle the initial step."""
        # Only allow a single instance
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()

        if user_input is not None:
            # User confirmed, create the entry
            return self.async_create_entry(
                title="HA Live Config",
                data={}
            )

        # Show confirmation form
        return self.async_show_form(
            step_id="user",
            description_placeholders={},
        )
