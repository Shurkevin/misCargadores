package es.origds.iberdrolaauto.auth

import android.content.Context
import android.net.Uri
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.RefreshTokenRequest
import java.util.concurrent.atomic.AtomicBoolean

/** Renews the Iberdrola access token locally when it is close to expiry. */
class TokenRefresher(context: Context, private val tokenStore: TokenStore) {
    private val service = AuthorizationService(context.applicationContext)
    private val refreshing = AtomicBoolean(false)

    fun refreshIfNeeded(force: Boolean = false, onComplete: (Result<String>) -> Unit) {
        val current = tokenStore.accessToken()
        val expiresAt = tokenStore.accessTokenExpirationTime()
        val stillValid = current != null && expiresAt > System.currentTimeMillis() + EXPIRY_MARGIN_MS
        if (!force && stillValid) {
            onComplete(Result.success(current))
            return
        }
        val refreshToken = tokenStore.refreshToken()
        if (refreshToken.isNullOrBlank()) {
            onComplete(Result.failure(IllegalStateException("La sesión ha caducado. Inicia sesión de nuevo.")))
            return
        }
        if (!refreshing.compareAndSet(false, true)) {
            onComplete(Result.failure(IllegalStateException("Renovación de sesión en curso.")))
            return
        }
        val settings = AuthSettings()
        val configuration = AuthorizationServiceConfiguration(
            Uri.parse(settings.authorizationEndpoint),
            Uri.parse(settings.tokenEndpoint)
        )
        val request = RefreshTokenRequest.Builder(configuration, settings.clientId, refreshToken)
            .setScope("openid profile email offline_access")
            .setAdditionalParameters(mapOf("audience" to settings.audience))
            .build()
        service.performTokenRequest(request) { response, exception ->
            refreshing.set(false)
            if (response != null && response.accessToken != null) {
                tokenStore.save(response)
                onComplete(Result.success(response.accessToken!!))
            } else {
                onComplete(Result.failure(exception ?: IllegalStateException("No se pudo renovar la sesión.")))
            }
        }
    }

    fun dispose() = service.dispose()

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
