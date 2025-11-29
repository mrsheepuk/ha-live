package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uk.co.mrsheep.halive.core.OAuthConfig

/**
 * Handles OAuth callback from Home Assistant.
 * This activity receives the halive://oauth/callback deep link.
 */
class OAuthCallbackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OAuthCallbackActivity"
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STATE = "state"
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

        // Return to OnboardingActivity with the auth code
        val resultIntent = Intent(this, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AUTH_CODE, code)
            putExtra(EXTRA_STATE, state)
        }
        startActivity(resultIntent)
        finish()
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, "Authentication failed: $message", Toast.LENGTH_LONG).show()

        // Return to OnboardingActivity with error
        val resultIntent = Intent(this, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ERROR, message)
        }
        startActivity(resultIntent)
        finish()
    }
}
