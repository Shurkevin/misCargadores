package es.origds.iberdrolaauto.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.TokenResponse

/** Keeps OAuth tokens on-device only, encrypted with a key held by Android Keystore. */
class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "iberdrola_oauth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(response: TokenResponse) {
        preferences.edit()
            .putString(ACCESS_TOKEN, response.accessToken)
            .putString(REFRESH_TOKEN, response.refreshToken)
            .putLong(EXPIRES_AT, response.accessTokenExpirationTime ?: 0L)
            .apply()
    }

    fun accessToken(): String? = preferences.getString(ACCESS_TOKEN, null)

    fun clear() = preferences.edit().clear().apply()

    companion object {
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val EXPIRES_AT = "expires_at"
    }
}
