package es.origds.iberdrolaauto.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * OAuth Authorization Code + PKCE coordinator. Credentials are entered only on the
 * provider's browser page; this process never receives the user's password or MFA code.
 */
class OAuthCoordinator(private val activity: Activity, private val tokenStore: TokenStore) {
    private val service = AuthorizationService(activity)

    fun begin(settings: AuthSettings): Result<Unit> = runCatching {
        require(settings.isReady()) { "Falta la configuración OAuth autorizada por Iberdrola." }
        val configuration = AuthorizationServiceConfiguration(
            Uri.parse(settings.authorizationEndpoint),
            Uri.parse(settings.tokenEndpoint)
        )
        val verifier = CodeVerifier.generate()
        val request = AuthorizationRequest.Builder(
            configuration,
            settings.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(settings.redirectUri)
        ).setCodeVerifier(verifier)
            .setScope("openid profile email offline_access")
            .setAdditionalParameters(mapOf("audience" to settings.audience))
            .build()
        activity.startActivityForResult(service.getAuthorizationRequestIntent(request), REQUEST_CODE)
    }

    fun handleResult(data: Intent?, onComplete: (Result<Unit>) -> Unit) {
        val response = data?.let(AuthorizationResponse::fromIntent)
        val error = data?.let(AuthorizationException::fromIntent)
        when {
            response != null -> service.performTokenRequest(response.createTokenExchangeRequest()) { token, exception ->
                if (token != null) {
                    tokenStore.save(token)
                    onComplete(Result.success(Unit))
                } else onComplete(Result.failure(exception ?: IllegalStateException("No se recibió token.")))
            }
            else -> onComplete(Result.failure(error ?: IllegalStateException("Inicio de sesión cancelado.")))
        }
    }

    fun dispose() = service.dispose()

    companion object { const val REQUEST_CODE = 611 } 
}
