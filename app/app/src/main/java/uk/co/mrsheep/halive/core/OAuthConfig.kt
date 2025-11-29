package uk.co.mrsheep.halive.core

object OAuthConfig {
    const val CLIENT_ID = "https://halive.app"
    const val REDIRECT_URI = "halive://oauth/callback"
    const val REDIRECT_SCHEME = "halive"
    const val REDIRECT_HOST = "oauth"
    const val REDIRECT_PATH = "/callback"

    fun buildAuthUrl(haBaseUrl: String, state: String): String {
        return "$haBaseUrl/auth/authorize" +
            "?client_id=$CLIENT_ID" +
            "&redirect_uri=$REDIRECT_URI" +
            "&response_type=code" +
            "&state=$state"
    }
}
