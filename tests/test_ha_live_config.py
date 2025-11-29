"""Tests for HA Live Config integration."""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch, call
import uuid
from datetime import datetime


# Mock Home Assistant types for testing outside of HA environment
class MockUser:
    """Mock Home Assistant user."""
    def __init__(self, user_id="test-user-id", name="Test User"):
        self.id = user_id
        self.name = name


class MockContext:
    """Mock service call context."""
    def __init__(self, user_id=None):
        self.user_id = user_id


class MockServiceCall:
    """Mock Home Assistant service call."""
    def __init__(self, data=None, context=None):
        self.data = data or {}
        self.context = context or MockContext()


class MockAuth:
    """Mock Home Assistant auth manager."""
    def __init__(self):
        self.users = {}

    async def async_get_user(self, user_id):
        """Get user by ID."""
        return self.users.get(user_id)


class MockStore:
    """Mock Home Assistant storage store."""
    def __init__(self):
        self.data = None

    async def async_load(self):
        """Load data from storage."""
        return self.data

    async def async_save(self, data):
        """Save data to storage."""
        self.data = data


class MockHass:
    """Mock Home Assistant instance."""
    def __init__(self):
        self.data = {}
        self.services = MagicMock()
        self.services.async_register = MagicMock()
        self.auth = MockAuth()


@pytest.fixture
def hass():
    """Create mock Home Assistant instance."""
    return MockHass()


@pytest.fixture
def store():
    """Create mock store."""
    return MockStore()


class TestSetup:
    """Tests for integration setup."""

    @pytest.mark.asyncio
    async def test_setup(self, hass, store):
        """Test that the integration sets up correctly."""
        # Import the setup function
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        # Mock the Store class
        with patch('custom_components.ha_live_config.Store', return_value=store):
            result = await async_setup(hass, {})

        assert result is True
        assert DOMAIN in hass.data
        assert "store" in hass.data[DOMAIN]
        assert "data" in hass.data[DOMAIN]

        # Verify initial data structure
        initial_data = hass.data[DOMAIN]["data"]
        assert initial_data["gemini_api_key"] is None
        assert initial_data["profiles"] == []

        # Verify services were registered
        assert hass.services.async_register.called
        registered_services = [call[0][1] for call in hass.services.async_register.call_args_list]
        assert "get_config" in registered_services
        assert "set_gemini_key" in registered_services
        assert "upsert_profile" in registered_services
        assert "delete_profile" in registered_services
        assert "check_profile_name" in registered_services

    @pytest.mark.asyncio
    async def test_setup_loads_existing_data(self, hass, store):
        """Test that setup loads existing data from storage."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        # Pre-populate store with existing data
        existing_data = {
            "schema_version": 1,
            "gemini_api_key": "AIza_existing_key",
            "profiles": [{"id": "profile-1", "name": "Living Room"}]
        }
        store.data = existing_data

        with patch('custom_components.ha_live_config.Store', return_value=store):
            result = await async_setup(hass, {})

        assert result is True
        loaded_data = hass.data[DOMAIN]["data"]
        assert loaded_data["gemini_api_key"] == "AIza_existing_key"
        assert len(loaded_data["profiles"]) == 1
        assert loaded_data["profiles"][0]["name"] == "Living Room"


class TestGetConfig:
    """Tests for get_config service."""

    @pytest.mark.asyncio
    async def test_get_config_empty(self, hass, store):
        """Test get_config returns empty config initially."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        get_config_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "get_config":
                get_config_handler = call_args[0][2]
                break

        assert get_config_handler is not None

        # Call the service
        result = get_config_handler(MockServiceCall())

        assert result["gemini_api_key"] is None
        assert result["profiles"] == []

    @pytest.mark.asyncio
    async def test_get_config_with_data(self, hass, store):
        """Test get_config returns configured data."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        existing_data = {
            "schema_version": 1,
            "gemini_api_key": "AIza_test_key",
            "profiles": [
                {"id": "1", "name": "Kitchen"},
                {"id": "2", "name": "Bedroom"}
            ]
        }
        store.data = existing_data

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        get_config_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "get_config":
                get_config_handler = call_args[0][2]
                break

        result = get_config_handler(MockServiceCall())

        assert result["gemini_api_key"] == "AIza_test_key"
        assert len(result["profiles"]) == 2


class TestSetGeminiKey:
    """Tests for set_gemini_key service."""

    @pytest.mark.asyncio
    async def test_set_gemini_key(self, hass, store):
        """Test setting a Gemini API key."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        set_key_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "set_gemini_key":
                set_key_handler = call_args[0][2]
                break

        assert set_key_handler is not None

        # Call the service with a key
        await set_key_handler(MockServiceCall({"api_key": "AIza_test_key_12345"}))

        assert hass.data[DOMAIN]["data"]["gemini_api_key"] == "AIza_test_key_12345"
        assert await store.async_load() is not None

    @pytest.mark.asyncio
    async def test_set_gemini_key_clear(self, hass, store):
        """Test clearing the Gemini API key with empty string."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        # Set initial key
        store.data = {
            "schema_version": 1,
            "gemini_api_key": "AIza_existing_key",
            "profiles": []
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        set_key_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "set_gemini_key":
                set_key_handler = call_args[0][2]
                break

        # Clear the key
        await set_key_handler(MockServiceCall({"api_key": ""}))

        assert hass.data[DOMAIN]["data"]["gemini_api_key"] is None

    @pytest.mark.asyncio
    async def test_set_gemini_key_invalid_warning(self, hass, store):
        """Test that invalid keys produce a warning."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        set_key_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "set_gemini_key":
                set_key_handler = call_args[0][2]
                break

        # Set an invalid key (doesn't start with AIza)
        with patch('custom_components.ha_live_config._LOGGER') as mock_logger:
            await set_key_handler(MockServiceCall({"api_key": "invalid_key"}))
            mock_logger.warning.assert_called()


class TestUpsertProfile:
    """Tests for upsert_profile service."""

    @pytest.mark.asyncio
    async def test_upsert_profile_creates(self, hass, store):
        """Test creating a new profile."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        assert upsert_handler is not None

        # Create a new profile
        profile = {
            "name": "Living Room",
            "system_prompt": "You are a helpful assistant",
            "personality": "Friendly and helpful"
        }

        with patch('custom_components.ha_live_config.dt_util.utcnow') as mock_utcnow:
            mock_utcnow.return_value.isoformat.return_value = "2025-01-01T00:00:00"
            result = await upsert_handler(MockServiceCall({"profile": profile}))

        assert "id" in result
        profile_id = result["id"]

        # Verify profile was added
        profiles = hass.data[DOMAIN]["data"]["profiles"]
        assert len(profiles) == 1
        assert profiles[0]["id"] == profile_id
        assert profiles[0]["name"] == "Living Room"
        assert profiles[0]["last_modified"] == "2025-01-01T00:00:00"

    @pytest.mark.asyncio
    async def test_upsert_profile_updates(self, hass, store):
        """Test updating an existing profile."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        # Start with an existing profile
        profile_id = str(uuid.uuid4())
        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{
                "id": profile_id,
                "name": "Living Room",
                "system_prompt": "Old prompt"
            }]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        # Update the profile
        updated_profile = {
            "id": profile_id,
            "name": "Living Room",
            "system_prompt": "New prompt"
        }

        with patch('custom_components.ha_live_config.dt_util.utcnow') as mock_utcnow:
            mock_utcnow.return_value.isoformat.return_value = "2025-01-02T00:00:00"
            result = await upsert_handler(MockServiceCall({"profile": updated_profile}))

        assert result["id"] == profile_id

        # Verify profile was updated (not added)
        profiles = hass.data[DOMAIN]["data"]["profiles"]
        assert len(profiles) == 1
        assert profiles[0]["system_prompt"] == "New prompt"

    @pytest.mark.asyncio
    async def test_upsert_profile_name_conflict(self, hass, store):
        """Test that duplicate profile names are rejected."""
        from custom_components.ha_live_config import async_setup

        # Start with existing profile
        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{
                "id": "existing-id",
                "name": "Living Room"
            }]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        # Try to create profile with same name (different ID)
        new_profile = {
            "id": str(uuid.uuid4()),
            "name": "Living Room",
            "system_prompt": "Different profile"
        }

        with pytest.raises(ValueError, match="A profile named 'Living Room' already exists"):
            await upsert_handler(MockServiceCall({"profile": new_profile}))

    @pytest.mark.asyncio
    async def test_upsert_profile_case_insensitive_conflict(self, hass, store):
        """Test that name conflict check is case-insensitive."""
        from custom_components.ha_live_config import async_setup

        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{
                "id": "existing-id",
                "name": "Living Room"
            }]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        # Try with different case
        new_profile = {
            "id": str(uuid.uuid4()),
            "name": "LIVING ROOM"
        }

        with pytest.raises(ValueError, match="already exists"):
            await upsert_handler(MockServiceCall({"profile": new_profile}))

    @pytest.mark.asyncio
    async def test_upsert_profile_missing_name(self, hass, store):
        """Test that profiles without names are rejected."""
        from custom_components.ha_live_config import async_setup

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        profile = {"system_prompt": "No name provided"}

        with pytest.raises(ValueError, match="Profile must have a name"):
            await upsert_handler(MockServiceCall({"profile": profile}))

    @pytest.mark.asyncio
    async def test_upsert_profile_with_user(self, hass, store):
        """Test that modified_by is set when user context available."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        # Add a user to auth
        test_user = MockUser("user-123", "Alice Smith")
        hass.auth.users["user-123"] = test_user

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        upsert_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "upsert_profile":
                upsert_handler = call_args[0][2]
                break

        profile = {"name": "My Profile"}
        context = MockContext(user_id="user-123")

        with patch('custom_components.ha_live_config.dt_util.utcnow') as mock_utcnow:
            mock_utcnow.return_value.isoformat.return_value = "2025-01-01T00:00:00"
            await upsert_handler(MockServiceCall({"profile": profile}, context=context))

        saved_profile = hass.data[DOMAIN]["data"]["profiles"][0]
        assert saved_profile["modified_by"] == "Alice Smith"


class TestDeleteProfile:
    """Tests for delete_profile service."""

    @pytest.mark.asyncio
    async def test_delete_profile(self, hass, store):
        """Test deleting a profile."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        profile_id = str(uuid.uuid4())
        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [
                {"id": profile_id, "name": "Profile to Delete"},
                {"id": "keep-id", "name": "Profile to Keep"}
            ]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        delete_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "delete_profile":
                delete_handler = call_args[0][2]
                break

        assert delete_handler is not None

        # Delete the profile
        await delete_handler(MockServiceCall({"profile_id": profile_id}))

        profiles = hass.data[DOMAIN]["data"]["profiles"]
        assert len(profiles) == 1
        assert profiles[0]["id"] == "keep-id"

    @pytest.mark.asyncio
    async def test_delete_profile_not_found(self, hass, store):
        """Test deleting a non-existent profile."""
        from custom_components.ha_live_config import async_setup
        from custom_components.ha_live_config.const import DOMAIN

        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{"id": "existing-id", "name": "Profile"}]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        delete_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "delete_profile":
                delete_handler = call_args[0][2]
                break

        # Try to delete non-existent profile
        with patch('custom_components.ha_live_config._LOGGER') as mock_logger:
            await delete_handler(MockServiceCall({"profile_id": "non-existent"}))
            mock_logger.warning.assert_called()

        # Verify no deletion occurred
        profiles = hass.data[DOMAIN]["data"]["profiles"]
        assert len(profiles) == 1


class TestCheckProfileName:
    """Tests for check_profile_name service."""

    @pytest.mark.asyncio
    async def test_check_profile_name_available(self, hass, store):
        """Test checking an available profile name."""
        from custom_components.ha_live_config import async_setup

        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{"id": "id-1", "name": "Living Room"}]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        # Get the registered service handler
        check_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "check_profile_name":
                check_handler = call_args[0][2]
                break

        assert check_handler is not None

        result = check_handler(MockServiceCall({"name": "Bedroom"}))
        assert result["available"] is True

    @pytest.mark.asyncio
    async def test_check_profile_name_taken(self, hass, store):
        """Test checking a taken profile name."""
        from custom_components.ha_live_config import async_setup

        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{"id": "id-1", "name": "Living Room"}]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        check_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "check_profile_name":
                check_handler = call_args[0][2]
                break

        result = check_handler(MockServiceCall({"name": "Living Room"}))
        assert result["available"] is False

    @pytest.mark.asyncio
    async def test_check_profile_name_case_insensitive(self, hass, store):
        """Test that name check is case-insensitive."""
        from custom_components.ha_live_config import async_setup

        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{"id": "id-1", "name": "Living Room"}]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        check_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "check_profile_name":
                check_handler = call_args[0][2]
                break

        # Check with different case
        result = check_handler(MockServiceCall({"name": "LIVING ROOM"}))
        assert result["available"] is False

    @pytest.mark.asyncio
    async def test_check_profile_name_exclude_id(self, hass, store):
        """Test excluding a profile ID from name check."""
        from custom_components.ha_live_config import async_setup

        profile_id = "id-1"
        store.data = {
            "schema_version": 1,
            "gemini_api_key": None,
            "profiles": [{"id": profile_id, "name": "Living Room"}]
        }

        with patch('custom_components.ha_live_config.Store', return_value=store):
            await async_setup(hass, {})

        check_handler = None
        for call_args in hass.services.async_register.call_args_list:
            if call_args[0][1] == "check_profile_name":
                check_handler = call_args[0][2]
                break

        # Check the same name but exclude the ID
        result = check_handler(MockServiceCall({
            "name": "Living Room",
            "exclude_id": profile_id
        }))
        assert result["available"] is True
