package uk.co.mrsheep.halive.core

import android.net.Uri

object OAuthConfig {
    const val CLIENT_ID = "https://halive.mrsheep.co.uk"
    const val REDIRECT_URI = "halive://oauth/callback"
    const val REDIRECT_SCHEME = "halive"
    const val REDIRECT_HOST = "oauth"
    const val REDIRECT_PATH = "/callback"

    fun buildAuthUrl(haBaseUrl: String, state: String): String {
        return Uri.parse("$haBaseUrl/auth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }
}
