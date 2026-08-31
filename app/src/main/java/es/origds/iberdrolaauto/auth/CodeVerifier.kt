package es.origds.iberdrolaauto.auth

import android.util.Base64
import java.security.SecureRandom

internal object CodeVerifier {
    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
