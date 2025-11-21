package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Manages quick message storage and CRUD operations.
 *
 * Quick messages are stored in SharedPreferences as a JSON array.
 * On first load, if no messages exist, the default messages are initialized.
 *
 * Usage: Create an instance with context, then call methods.
 */
class QuickMessageConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "quick_messages_prefs"
        private const val KEY_QUICK_MESSAGES = "quick_messages_list"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /**
         * Default quick messages provided on first app launch.
         */
        val DEFAULT_QUICK_MESSAGES = listOf(
            QuickMessage(
                id = "sing_house_song",
                label = "Sing a Song",
                message = "Sing me a song about my house",
                enabled = true
            )
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Initialize with defaults if no messages exist
        if (loadQuickMessagesFromStorage().isEmpty()) {
            saveQuickMessagesToStorage(DEFAULT_QUICK_MESSAGES)
        }
    }

    // ========== Read Operations ==========

    /**
     * Returns all quick messages.
     */
    fun getQuickMessages(): List<QuickMessage> {
        return loadQuickMessagesFromStorage()
    }

    /**
     * Returns only enabled quick messages.
     */
    fun getEnabledQuickMessages(): List<QuickMessage> {
        return getQuickMessages().filter { it.enabled }
    }

    /**
     * Returns a quick message by ID, or null if not found.
     */
    fun getQuickMessageById(id: String): QuickMessage? {
        return getQuickMessages().find { it.id == id }
    }

    // ========== Write Operations ==========

    /**
     * Saves a complete list of quick messages, replacing all existing messages.
     */
    fun saveQuickMessages(messages: List<QuickMessage>) {
        saveQuickMessagesToStorage(messages)
    }

    /**
     * Adds a new quick message to the list.
     * Throws IllegalArgumentException if ID already exists.
     */
    fun addQuickMessage(quickMessage: QuickMessage) {
        val existing = getQuickMessages()

        if (existing.any { it.id == quickMessage.id }) {
            throw IllegalArgumentException("A quick message with ID '${quickMessage.id}' already exists")
        }

        saveQuickMessagesToStorage(existing + quickMessage)
    }

    /**
     * Updates an existing quick message.
     * Throws IllegalArgumentException if ID doesn't exist.
     */
    fun updateQuickMessage(quickMessage: QuickMessage) {
        val existing = getQuickMessages()
        val index = existing.indexOfFirst { it.id == quickMessage.id }

        if (index == -1) {
            throw IllegalArgumentException("Quick message with ID '${quickMessage.id}' does not exist")
        }

        val updated = existing.toMutableList()
        updated[index] = quickMessage

        saveQuickMessagesToStorage(updated)
    }

    /**
     * Deletes a quick message by ID.
     * Throws IllegalArgumentException if ID doesn't exist.
     */
    fun deleteQuickMessage(id: String) {
        val existing = getQuickMessages()

        if (!existing.any { it.id == id }) {
            throw IllegalArgumentException("Quick message with ID '$id' does not exist")
        }

        val remaining = existing.filter { it.id != id }
        saveQuickMessagesToStorage(remaining)
    }

    // ========== Storage Operations ==========

    private fun loadQuickMessagesFromStorage(): List<QuickMessage> {
        val jsonString = prefs.getString(KEY_QUICK_MESSAGES, null) ?: return emptyList()

        return try {
            json.decodeFromString<List<QuickMessage>>(jsonString)
        } catch (e: Exception) {
            // Corrupted data - return empty list
            emptyList()
        }
    }

    private fun saveQuickMessagesToStorage(messages: List<QuickMessage>) {
        val jsonString = json.encodeToString(messages)
        prefs.edit().putString(KEY_QUICK_MESSAGES, jsonString).apply()
    }

    /**
     * Clears all quick messages and resets to default state.
     * Used for error recovery or testing.
     */
    fun resetToDefault() {
        saveQuickMessagesToStorage(DEFAULT_QUICK_MESSAGES)
    }
}
