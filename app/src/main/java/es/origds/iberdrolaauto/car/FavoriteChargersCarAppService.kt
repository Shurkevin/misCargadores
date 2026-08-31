package es.origds.iberdrolaauto.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point. Authentication is deliberately kept on the phone;
 * this service can only read the token stored by the phone part of this app.
 */
class FavoriteChargersCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = FavoriteChargersScreen(carContext)
    }
}
