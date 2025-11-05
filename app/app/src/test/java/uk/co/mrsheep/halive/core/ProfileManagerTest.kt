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

    @Before
    fun setup() {
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)

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
    fun testCreateProfile_firstProfileIsDefault() {
        val profile = ProfileManager.createProfile(Profile(name = "First", systemPrompt = "Prompt"))
        assertTrue(profile.isDefault)
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
    fun testGetDefaultProfile_returnsDefaultProfile() {
        ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val default = ProfileManager.createProfile(
            Profile(name = "Default", systemPrompt = "Prompt 2", isDefault = true)
        )

        val retrieved = ProfileManager.getDefaultProfile()
        assertNotNull(retrieved)
        assertEquals(default.id, retrieved?.id)
        assertTrue(retrieved!!.isDefault)
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
    fun testSetDefaultProfile_unmarksOthers() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.setDefaultProfile(profile2.id)

        val allProfiles = ProfileManager.getAllProfiles()
        assertEquals(false, allProfiles.find { it.id == profile1.id }?.isDefault)
        assertEquals(true, allProfiles.find { it.id == profile2.id }?.isDefault)
    }

    @Test(expected = IllegalStateException::class)
    fun testDeleteProfile_lastProfile_throwsException() {
        val profile = ProfileManager.createProfile(Profile(name = "Only Profile", systemPrompt = "Prompt"))
        ProfileManager.deleteProfile(profile.id)
    }

    @Test
    fun testDeleteProfile_defaultProfile_promotesAnother() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        assertTrue(profile1.isDefault)

        ProfileManager.deleteProfile(profile1.id)

        val remaining = ProfileManager.getAllProfiles()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].isDefault)
    }

    @Test
    fun testDuplicateProfile_success() {
        val original = ProfileManager.createProfile(Profile(name = "Original", systemPrompt = "Original Prompt"))
        val duplicate = ProfileManager.duplicateProfile(original.id, "Duplicate")

        assertNotEquals(original.id, duplicate.id)
        assertEquals("Duplicate", duplicate.name)
        assertEquals("Original Prompt", duplicate.systemPrompt)
        assertFalse(duplicate.isDefault)
    }

    @Test
    fun testEnsureDefaultProfileExists_noProfiles_createsDefault() {
        // ProfileManager starts empty
        ProfileManager.ensureDefaultProfileExists()

        val profiles = ProfileManager.getAllProfiles()
        assertEquals(1, profiles.size)
        assertEquals("Default", profiles[0].name)
        assertTrue(profiles[0].isDefault)
    }

    @Test
    fun testMarkProfileAsUsed_updatesLastUsedId() {
        val profile = ProfileManager.createProfile(Profile(name = "Test", systemPrompt = "Prompt"))

        ProfileManager.markProfileAsUsed(profile.id)

        assertEquals(profile.id, ProfileManager.getLastUsedProfileId())
    }

    @Test
    fun testGetLastUsedOrDefaultProfile_noLastUsed_returnsDefault() {
        val profile = ProfileManager.createProfile(Profile(name = "Default", systemPrompt = "Prompt"))

        val retrieved = ProfileManager.getLastUsedOrDefaultProfile()

        assertNotNull(retrieved)
        assertEquals(profile.id, retrieved?.id)
    }

    @Test
    fun testGetLastUsedOrDefaultProfile_lastUsedDeleted_fallsBackToDefault() {
        val profile1 = ProfileManager.createProfile(Profile(name = "Profile 1", systemPrompt = "Prompt 1"))
        val profile2 = ProfileManager.createProfile(Profile(name = "Profile 2", systemPrompt = "Prompt 2"))

        ProfileManager.markProfileAsUsed(profile2.id)
        ProfileManager.deleteProfile(profile2.id)

        val retrieved = ProfileManager.getLastUsedOrDefaultProfile()

        assertNotNull(retrieved)
        assertEquals(profile1.id, retrieved?.id) // Falls back to default
    }
}
