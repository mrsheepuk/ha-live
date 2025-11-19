package uk.co.mrsheep.halive.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import androidx.core.app.PendingIntentCompat
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.ProfileManager
import uk.co.mrsheep.halive.ui.MainActivity

/**
 * Widget provider for conversation quick-start widget.
 *
 * Manages widget lifecycle and provides quick-start conversation functionality.
 * Each widget instance is associated with a specific profile via SharedPreferences.
 */
class ConversationWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val WIDGET_PREFS_NAME = "conversation_widget_prefs"
        private const val WIDGET_PROFILE_PREFIX = "widget_profile_"

        /**
         * Get the stored profile ID for a specific widget instance.
         */
        fun getWidgetProfileId(context: Context, appWidgetId: Int): String? {
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(WIDGET_PROFILE_PREFIX + appWidgetId, null)
        }

        /**
         * Store the profile ID for a specific widget instance.
         */
        fun setWidgetProfileId(context: Context, appWidgetId: Int, profileId: String) {
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(WIDGET_PROFILE_PREFIX + appWidgetId, profileId).apply()
        }

        /**
         * Clear the stored profile ID for a widget.
         */
        fun clearWidgetProfileId(context: Context, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(WIDGET_PROFILE_PREFIX + appWidgetId).apply()
        }

        /**
         * Update a specific widget by ID.
         */
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val profileId = getWidgetProfileId(context, appWidgetId)
            val profile = if (!profileId.isNullOrEmpty()) {
                ProfileManager.getProfileById(profileId)
            } else {
                null
            }

            val profileName = profile?.name ?: context.getString(R.string.profile_not_found)
            val views = RemoteViews(context.packageName, R.layout.widget_conversation)

            // Update the profile name text
            views.setTextViewText(R.id.widgetProfileName, profileName)

            // Set up the click intent to launch MainActivity with auto-start
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "android.intent.action.MAIN"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.AUTO_START_CONVERSATION, true)
                if (!profileId.isNullOrEmpty()) {
                    putExtra(MainActivity.PROFILE_ID, profileId)
                }
            }

            val pendingIntent = PendingIntentCompat.getActivity(
                context,
                appWidgetId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                false // isMutable = false (immutable pending intent)
            )

            // Apply the pending intent to the root container
            views.setOnClickPendingIntent(R.id.widgetRootContainer, pendingIntent)

            // Update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    /**
     * Called when a widget instance is created or when the system requests an update.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    /**
     * Called when a widget instance is deleted.
     * Cleans up associated SharedPreferences entry.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Clean up SharedPreferences for deleted widgets
        for (appWidgetId in appWidgetIds) {
            clearWidgetProfileId(context, appWidgetId)
        }
    }

    /**
     * Called when the first instance of the widget is added.
     * Could be used for initial setup or analytics.
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Could be used for initialization if needed
    }

    /**
     * Called when the last instance of the widget is deleted.
     * Could be used for cleanup of shared resources.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Could be used for cleanup of shared resources if needed
    }
}
