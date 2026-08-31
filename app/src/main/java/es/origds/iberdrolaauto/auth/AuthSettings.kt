package es.origds.iberdrolaauto.auth

/**
 * Public OAuth client metadata. The user's password and MFA code are entered only in
 * the provider's browser page; neither is persisted nor sent to another backend.
 */
data class AuthSettings(
    val authorizationEndpoint: String = "https://login-rp.iberdrola.com/authorize",
    val tokenEndpoint: String = "https://login-rp.iberdrola.com/oauth/token",
    val clientId: String = "6K4rRPc6x0LmBO7FLWKxrqhBewNEYbuU",
    val redirectUri: String = "rv://callback/android/es.iberdrola.recargaverde/callback",
    val audience: String = "http://eva.iberdrola.com/veappapi/okta/"
) {
    fun isReady(): Boolean = authorizationEndpoint.startsWith("https://") &&
        tokenEndpoint.startsWith("https://") && clientId.isNotBlank()
}
