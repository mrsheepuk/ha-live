package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.ui.MainActivity

/**
 * Helper singleton for creating pinned shortcuts to profiles.
 *
 * Provides functionality to create device home screen shortcuts that directly
 * launch into a conversation with a specific profile, with auto-start enabled.
 */
object ShortcutHelper {

    /**
     * Creates a pinned shortcut for the given profile.
     *
     * The shortcut will launch MainActivity with:
     * - AUTO_START_CONVERSATION = true
     * - PROFILE_ID = profile.id
     *
     * Uses ShortcutManagerCompat to handle API level differences.
     *
     * @param context The Android context
     * @param profile The profile to create a shortcut for
     * @return true if the shortcut was created successfully, false otherwise
     */
    fun createShortcutForProfile(context: Context, profile: Profile): Boolean {
        return try {
            // Check if the device supports pinned shortcuts
            if (!isRequestPinShortcutSupported(context)) {
                return false
            }

            // Create the intent that will be launched when the shortcut is tapped
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(MainActivity.AUTO_START_CONVERSATION, true)
                putExtra(MainActivity.PROFILE_ID, profile.id)
            }

            // Create the shortcut icon using the hearing drawable
            val icon = IconCompat.createWithResource(context, R.drawable.ic_hearing)

            // Build the shortcut info with the profile's name as the label
            val shortcutInfo = ShortcutInfoCompat.Builder(context, profile.id)
                .setShortLabel(profile.name)
                .setIcon(icon)
                .setIntent(intent)
                .build()

            // Request to pin the shortcut to the device home screen
            ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
            true
        } catch (e: Exception) {
            // Log the exception
            Log.e("ShortcutHelper", "Failed to create shortcut for profile: ${profile.name}", e)
            false
        }
    }

    /**
     * Checks if the device supports pinned shortcuts.
     *
     * Pinned shortcuts require API 26+.
     *
     * @param context The Android context
     * @return true if pinned shortcuts are supported, false otherwise
     */
    fun isRequestPinShortcutSupported(context: Context): Boolean {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    }
}
