package es.origds.iberdrolaauto.car

import android.location.Geocoder
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.concurrent.Executors

/** Lets Android Auto collect an address, then resolves it on the phone. */
class AddressSearchScreen(
    carContext: CarContext,
    private val onAddressResolved: (Location) -> Unit
) : Screen(carContext) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchText = ""
    private var loading = false
    private var message: String? = null
    @Volatile private var carHostActive = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carHostActive = true
            }

            override fun onStop(owner: LifecycleOwner) {
                carHostActive = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carHostActive = false
                executor.shutdownNow()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                this@AddressSearchScreen.searchText = searchText
            }

            override fun onSearchSubmitted(searchText: String) {
                this@AddressSearchScreen.searchText = searchText.trim()
                resolveAddress()
            }
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Dirección, ciudad o código postal")
            .setInitialSearchText(searchText)
            .setShowKeyboardByDefault(true)
            .apply {
                if (loading) {
                    setLoading(true)
                } else {
                    message?.let { setItemList(ItemList.Builder().addItem(Row.Builder().setTitle(it).build()).build()) }
                }
            }
            .build()
    }

    private fun resolveAddress() {
        if (loading) return
        if (searchText.isBlank()) {
            message = "Escribe una dirección para buscar cargadores."
            invalidateIfActive()
            return
        }
        loading = true
        message = null
        invalidateIfActive()
        executor.execute {
            val result = runCatching {
                val address = Geocoder(carContext, Locale("es", "ES"))
                    .getFromLocationName(searchText, 1)
                    ?.firstOrNull()
                    ?: error("No se encontró esa dirección.")
                Location("address").apply {
                    latitude = address.latitude
                    longitude = address.longitude
                }
            }
            mainHandler.post {
                loading = false
                result.fold(
                    onSuccess = { location ->
                        onAddressResolved(location)
                        screenManager.pop()
                    },
                    onFailure = {
                        message = "No se encontró esa dirección. Prueba con ciudad y calle."
                        invalidateIfActive()
                    }
                )
            }
        }
    }

    private fun invalidateIfActive() {
        if (carHostActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }
}
