package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

class ProfileManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val mockStorage = mutableMapOf<String, String>()

    @Before
    fun setup() {
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        mockStorage.clear()

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)

        // Mock editor to store values in our map
        `when`(editor.putString(anyString(), anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            mockStorage[key] = value
            editor
        }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            mockStorage.remove(key)
            editor
        }

        // Mock getString to return values from our map (handles nullable default)
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val defaultValue = invocation.getArgument<String?>(1)
            mockStorage[key] ?: defaultValue
        }

        ProfileManager.initialize(context)
    }

    @Test
    fun testCreateProfile_success() {
        val profile = Profile(name = "Test Profile", systemPrompt = "Test prompt")
        val created = ProfileManager.createProfile(profile)

        assertNotNull(created.id)
        assertEquals("Test Profile", created.name)
        assertEquals("Test prompt", created.systemPrompt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateProfile_blankName_throwsException() {
        ProfileManager.createProfile(Profile(name = "", systemPrompt = "Prompt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateProfile_duplicateName_throwsException() {
        ProfileManager.createProfile(Profile(name = "Duplicate", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Duplicate", systemPrompt = "Prompt 2"))
    }

    @Test
    fun testCreateProfile_firstProfileIsActive() {
        val profile = ProfileManager.createProfile(Profile(name = "First", systemPrompt = "Prompt"))
        val active = ProfileManager.getActiveProfile()
        assertNotNull(active)
        assertEquals(profile.id, active?.id)
    }

    @Test
    fun testGetAllProfiles_returnsCorrectCount() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        val profiles = ProfileManager.getAllProfiles()
        assertEquals(2, profiles.size)
    }

    @Test
    fun testGetProfileById_existingId_returnsProfile() {
        val created = ProfileManager.createProfile(Profile(name = "Test", systemPrompt = "Prompt"))
        val retrieved = ProfileManager.getProfileById(created.id)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved?.id)
        assertEquals(created.name, retrieved?.name)
    }

    @Test
    fun testGetProfileById_nonExistentId_returnsNull() {
        val retrieved = ProfileManager.getProfileById("nonexistent-id")
        assertNull(retrieved)
    }

    @Test
    fun testGetActiveProfile_returnsActiveProfile() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setActiveProfile(profile2.id)

        val retrieved = ProfileManager.getActiveProfile()
        assertNotNull(retrieved)
        assertEquals(profile2.id, retrieved?.id)
    }

    @Test
    fun testUpdateProfile_success() {
        val created = ProfileManager.createProfile(Profile(name = "Original", systemPrompt = "Original Prompt"))
        val updated = created.copy(name = "Updated", systemPrompt = "Updated Prompt")

        ProfileManager.updateProfile(updated)

        val retrieved = ProfileManager.getProfileById(created.id)
        assertEquals("Updated", retrieved?.name)
        assertEquals("Updated Prompt", retrieved?.systemPrompt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUpdateProfile_duplicateName_throwsException() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.updateProfile(profile2.copy(name = "Profile 1"))
    }

    @Test
    fun testSetActiveProfile_setsActive() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setActiveProfile(profile2.id)

        val active = ProfileManager.getActiveProfile()
        assertEquals(profile2.id, active?.id)
    }

    @Test(expected = IllegalStateException::class)
    fun testDeleteProfile_lastProfile_throwsException() {
        val profile = ProfileManager.createProfile(Profile(name = "Only Profile", systemPrompt = "Prompt"))
        ProfileManager.deleteProfile(profile.id)
    }

    @Test
    fun testDeleteProfile_activeProfile_promotesAnother() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setActiveProfile(profile1.id)
        assertEquals(profile1.id, ProfileManager.getActiveProfile()?.id)

        ProfileManager.deleteProfile(profile1.id)

        val remaining = ProfileManager.getAllProfiles()
        assertEquals(1, remaining.size)
        assertEquals(profile2.id, ProfileManager.getActiveProfile()?.id)
    }

    @Test
    fun testDuplicateProfile_success() {
        val original = ProfileManager.createProfile(Profile(name = "Original", systemPrompt = "Original Prompt"))
        val duplicate = ProfileManager.duplicateProfile(original.id, "Duplicate")

        assertNotEquals(original.id, duplicate.id)
        assertEquals("Duplicate", duplicate.name)
        assertEquals("Original Prompt", duplicate.systemPrompt)
    }

    @Test
    fun testEnsureDefaultProfileExists_noProfiles_createsDefault() {
        // ProfileManager starts empty
        ProfileManager.ensureDefaultProfileExists()

        val profiles = ProfileManager.getAllProfiles()
        assertEquals(1, profiles.size)
        assertEquals("Default", profiles[0].name)
        val active = ProfileManager.getActiveProfile()
        assertNotNull(active)
        assertEquals(profiles[0].id, active?.id)
    }

    @Test
    fun testGetActiveOrFirstProfile_returnsActiveProfile() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setActiveProfile(profile2.id)

        val retrieved = ProfileManager.getActiveOrFirstProfile()

        assertNotNull(retrieved)
        assertEquals(profile2.id, retrieved?.id)
    }

    @Test
    fun testGetActiveOrFirstProfile_noActiveSet_returnsFirstProfile() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        val retrieved = ProfileManager.getActiveOrFirstProfile()

        assertNotNull(retrieved)
        assertEquals(profile1.id, retrieved?.id)
    }

    @Test
    fun testGetActiveOrFirstProfile_activeDeletedFallsBack() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setActiveProfile(profile2.id)
        ProfileManager.deleteProfile(profile2.id)

        val retrieved = ProfileManager.getActiveOrFirstProfile()

        assertNotNull(retrieved)
        assertEquals(profile1.id, retrieved?.id) // Falls back to first remaining
    }
}
