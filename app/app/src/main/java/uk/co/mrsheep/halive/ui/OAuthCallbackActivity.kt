package uk.co.mrsheep.halive.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uk.co.mrsheep.halive.core.OAuthConfig

/**
 * Handles OAuth callback from Home Assistant.
 * This activity receives the halive://oauth/callback deep link.
 *
 * It stores the OAuth result in SharedPreferences so any activity
 * (OnboardingActivity or SettingsActivity) can retrieve it in onResume.
 */
class OAuthCallbackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OAuthCallbackActivity"
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STATE = "state"

        private const val PREFS_NAME = "oauth_pending_result"
        private const val KEY_AUTH_CODE = "pending_auth_code"
        private const val KEY_STATE = "pending_state"
        private const val KEY_ERROR = "pending_error"
        private const val KEY_SOURCE_ACTIVITY = "source_activity"

        const val SOURCE_ONBOARDING = "onboarding"
        const val SOURCE_SETTINGS = "settings"

        /**
         * Set the source activity before initiating OAuth.
         * This tells the callback handler which activity to return to.
         */
        fun setSourceActivity(context: Context, source: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SOURCE_ACTIVITY, source).apply()
        }

        /**
         * Check if there's a pending OAuth result.
         * Returns a Triple of (authCode, state, error) where all can be null.
         * Clears the pending result after reading.
         */
        fun getPendingResult(context: Context): Triple<String?, String?, String?> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val authCode = prefs.getString(KEY_AUTH_CODE, null)
            val state = prefs.getString(KEY_STATE, null)
            val error = prefs.getString(KEY_ERROR, null)

            // Clear after reading
            if (authCode != null || error != null) {
                prefs.edit().clear().apply()
            }

            return Triple(authCode, state, error)
        }

        private fun storePendingResult(context: Context, authCode: String?, state: String?, error: String?) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_AUTH_CODE, authCode)
                .putString(KEY_STATE, state)
                .putString(KEY_ERROR, error)
                .apply()
        }

        private fun getSourceActivity(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_SOURCE_ACTIVITY, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        Log.d(TAG, "Received callback URI: $uri")

        if (uri == null) {
            Log.e(TAG, "No URI in callback")
            finishWithError("No callback data received")
            return
        }

        // Validate the scheme and host
        if (uri.scheme != OAuthConfig.REDIRECT_SCHEME || uri.host != OAuthConfig.REDIRECT_HOST) {
            Log.e(TAG, "Invalid callback URI scheme/host: ${uri.scheme}://${uri.host}")
            finishWithError("Invalid callback")
            return
        }

        // Check for error
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val errorDescription = uri.getQueryParameter("error_description") ?: error
            Log.e(TAG, "OAuth error: $error - $errorDescription")
            finishWithError(errorDescription)
            return
        }

        // Get the auth code
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (code == null) {
            Log.e(TAG, "No auth code in callback")
            finishWithError("No authorization code received")
            return
        }

        Log.d(TAG, "Received auth code, state: $state")

        // Store result for the calling activity to retrieve
        storePendingResult(this, code, state, null)

        // Try to return to the calling activity
        // Use CLEAR_TOP to bring it to front if it's in the stack
        // Try SettingsActivity first (if it exists in stack), then OnboardingActivity
        finishAndReturnToCaller()
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, "Authentication failed: $message", Toast.LENGTH_LONG).show()

        // Store error result
        storePendingResult(this, null, null, message)

        finishAndReturnToCaller()
    }

    private fun finishAndReturnToCaller() {
        // Determine which activity to return to based on the stored source
        val targetClass = when (getSourceActivity(this)) {
            SOURCE_ONBOARDING -> OnboardingActivity::class.java
            SOURCE_SETTINGS -> SettingsActivity::class.java
            else -> MainActivity::class.java // Fallback
        }

        // Launch with flags to clear the Chrome Custom Tab from the task stack
        val intent = Intent(this, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}
