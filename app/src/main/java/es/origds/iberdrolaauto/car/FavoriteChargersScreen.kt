package es.origds.iberdrolaauto.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import es.origds.iberdrolaauto.auth.TokenStore
import es.origds.iberdrolaauto.auth.TokenRefresher
import es.origds.iberdrolaauto.data.ChargePoint
import es.origds.iberdrolaauto.data.ChargePointNameStore
import es.origds.iberdrolaauto.data.ChargePointOrderStore
import es.origds.iberdrolaauto.data.IberdrolaReadOnlyRepository
import es.origds.iberdrolaauto.data.singleAvailableSocketName
import java.util.concurrent.Executors

/** A read-only list: it intentionally exposes neither maps nor navigation actions. */
class FavoriteChargersScreen(carContext: CarContext) : Screen(carContext) {
    private enum class Mode { FAVORITES, NEARBY }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenStore = TokenStore(carContext)
    private val tokenRefresher = TokenRefresher(carContext, tokenStore)
    private var loading = false
    private var message = "Cargando favoritos…"
    private var chargers: List<ChargePoint> = emptyList()
    private var mode = Mode.FAVORITES
    private var favoritesLoadAttempted = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                executor.shutdownNow()
                tokenRefresher.dispose()
            }
        })
    }

    override fun onGetTemplate(): Template {
        loadIfNeeded()
        return chargersListTemplate()
    }

    private fun chargersListTemplate(): Template {
        if (loading) {
            return ListTemplate.Builder()
                .setTitle(title())
                .setLoading(true)
                .setActionStrip(mainActionStrip())
                .build()
        }
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Actualizar")
                .setImage(CarIcons.refresh(carContext))
                .setOnClickListener { refresh() }
                .build()
        )
        if (chargers.isEmpty()) {
            items.addItem(Row.Builder().setTitle(message).build())
        } else {
            displayedChargers().forEach { charger ->
                items.addItem(
                    Row.Builder()
                        .setTitle(compactTitle(charger))
                        .addText(listAvailability(charger))
                        .apply {
                            charger.distanceKm?.let { addText("A ${"%.1f".format(java.util.Locale("es", "ES"), it)} km") }
                        }
                        .setImage(CarIcons.charger(carContext, charger.availableSockets > 0))
                        .setBrowsable(
                            mode != Mode.NEARBY || charger.totalSockets != 1 ||
                                charger.availableSockets != 1
                        )
                        .setOnClickListener {
                            openCharger(charger, showNavigation = mode == Mode.NEARBY)
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle(title())
            .setSingleList(items.build())
            .setActionStrip(mainActionStrip())
            .build()
    }

    private fun mainActionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setTitle(if (mode == Mode.NEARBY) "Mdona" else "Cerca de mí")
                .setOnClickListener {
                    if (mode == Mode.NEARBY) showFavorites() else findNearby()
                }
                .build()
        )
        .addAction(
            Action.Builder()
                .setIcon(CarIcons.search(carContext))
                .setOnClickListener { openAddressSearch() }
                .build()
        )
        .build()

    private fun listAvailability(charger: ChargePoint): String = buildString {
        if (charger.availableSockets == 1) {
            append("Disponible")
        } else if (charger.availableSockets == charger.totalSockets && charger.totalSockets > 0) {
            append("Todas disponibles")
        } else if (charger.totalSockets == 1) {
            append("No disponible")
        } else {
            append("${charger.availableSockets} de ${charger.totalSockets} tomas disponibles")
        }
        append(" · ${charger.powerKw} kW")
        charger.singleAvailableSocketName()?.let { append(" · $it") }
    }

    /** Keeps the phone-defined order within each group, while preferring Toma 1. */
    private fun displayedChargers(): List<ChargePoint> =
        if (mode == Mode.FAVORITES) chargers.sortedBy { if (it.hasAvailableSocketOne()) 0 else 1 } else chargers

    private fun compactTitle(charger: ChargePoint): String {
        val name = ChargePointNameStore(carContext).displayName(charger)
            .replace(Regex("^MERCADONA\\s*\\(\\d+\\)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bOFICINAS\\b", RegexOption.IGNORE_CASE), "Of.")
            .replace(Regex("\\bNARANJA\\b", RegexOption.IGNORE_CASE), "Nar.")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (name.length <= 26) name else name.take(25).trimEnd() + "…"
    }

    private fun ChargePoint.hasAvailableSocketOne(): Boolean = sockets.any { socket ->
        socket.available && socket.name.contains(Regex("\\btoma\\s*1\\b", RegexOption.IGNORE_CASE))
    }

    private fun openCharger(charger: ChargePoint, showNavigation: Boolean) {
        if (showNavigation && charger.totalSockets == 1 && charger.availableSockets == 1 &&
            charger.latitude != null && charger.longitude != null
        ) {
            navigateTo(charger)
        } else {
            screenManager.push(
                FavoriteChargePointDetailScreen(
                    carContext = carContext,
                    chargePoint = charger,
                    showNavigation = showNavigation
                )
            )
        }
    }

    private fun refresh() {
        if (loading) return
        chargers = emptyList()
        if (mode == Mode.NEARBY) findNearby() else {
            favoritesLoadAttempted = false
            message = "Actualizando favoritos…"
            invalidateIfActive()
        }
    }

    private fun showFavorites() {
        if (loading) return
        mode = Mode.FAVORITES
        chargers = emptyList()
        favoritesLoadAttempted = false
        message = "Cargando cargadores Mercadona…"
        invalidateIfActive()
    }

    private fun navigateTo(charger: ChargePoint) {
        carContext.startCarApp(
            Intent(CarContext.ACTION_NAVIGATE).setData(
                Uri.parse("geo:${charger.latitude},${charger.longitude}")
            )
        )
    }

    private fun openAddressSearch() {
        screenManager.push(AddressSearchScreen(carContext) { location -> findNearbyAt(location) })
    }

    private fun findNearby() {
        if (loading) return
        mode = Mode.NEARBY
        chargers = emptyList()
        val fineGranted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            message = "Confirma la ubicación en el teléfono para buscar cargadores cercanos."
            invalidateIfActive()
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { granted, _ ->
                if (granted.isEmpty()) {
                    message = "No se concedió acceso a la ubicación."
                    invalidateIfActive()
                } else {
                    findNearby()
                }
            }
            return
        }
        message = "Obteniendo tu ubicación…"
        invalidateIfActive()
        val locationManager = carContext.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { locationManager.isProviderEnabled(it) }
        if (provider == null) {
            message = "Activa la ubicación del teléfono para buscar cerca de ti."
            invalidateIfActive()
            return
        }
        loading = true
        invalidateIfActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, executor) { location ->
                location?.let(::loadNearby) ?: run {
                    mainHandler.post {
                        loading = false
                        message = "No se pudo obtener tu ubicación actual."
                        invalidateIfActive()
                    }
                }
            }
        } else {
            val location = locationManager.getLastKnownLocation(provider)
            if (location == null) {
                loading = false
                message = "No hay una ubicación reciente disponible."
                invalidateIfActive()
            } else executor.execute { loadNearby(location) }
        }
    }

    private fun loadNearby(location: Location) {
        if (tokenStore.accessToken() == null) {
            mainHandler.post {
                loading = false
                message = "Inicia sesión en la aplicación del teléfono para buscar cargadores."
                invalidateIfActive()
            }
            return
        }
        mainHandler.post {
            message = "Buscando cargadores disponibles cerca de ti…"
            invalidateIfActive()
        }
        tokenRefresher.refreshIfNeeded { tokenResult ->
            tokenResult.onFailure {
                mainHandler.post {
                    loading = false
                    message = "La sesión ha caducado. Inicia sesión de nuevo en el teléfono."
                    invalidateIfActive()
                }
            }.onSuccess { token ->
                executor.execute {
                    val result = runCatching {
                        IberdrolaReadOnlyRepository(carContext).nearbyAvailableChargePoints(token, location.latitude, location.longitude)
                    }
                    mainHandler.post {
                        chargers = result.getOrNull()?.availablePoints.orEmpty()
                        message = result.fold(
                            onSuccess = { search ->
                                if (search.availablePoints.isNotEmpty()) "" else when {
                                    search.catalogueCount == 0 -> "Iberdrola no devolvió cargadores en unos 2 km."
                                    search.detailCount == 0 -> "Iberdrola devolvió ${search.catalogueCount} puntos, pero sin detalle de tomas."
                                    else -> "Se revisaron ${search.detailCount} cargadores cercanos y ninguno figura disponible."
                                }
                            },
                            onFailure = { "No se pudieron buscar cargadores: ${it.message?.take(120) ?: "error desconocido"}" }
                        )
                        loading = false
                        invalidateIfActive()
                    }
                }
            }
        }
    }

    private fun findNearbyAt(location: Location) {
        if (loading) return
        mode = Mode.NEARBY
        chargers = emptyList()
        loading = true
        message = "Buscando cargadores cerca de la dirección…"
        executor.execute { loadNearby(location) }
    }

    private fun loadIfNeeded() {
        if (loading || chargers.isNotEmpty() || mode == Mode.NEARBY || favoritesLoadAttempted) return
        val token = tokenStore.accessToken()
        if (token == null) {
            message = "Inicia sesión en la aplicación del teléfono para ver tus favoritos."
            return
        }
        loading = true
        tokenRefresher.refreshIfNeeded { tokenResult ->
            tokenResult.onFailure {
                mainHandler.post {
                    favoritesLoadAttempted = true
                    message = "La sesión ha caducado. Inicia sesión de nuevo en el teléfono."
                    loading = false
                    invalidateIfActive()
                }
            }.onSuccess { freshToken ->
                executor.execute {
                    val result = runCatching {
                        IberdrolaReadOnlyRepository(carContext).authorizedChargePoints(freshToken)
                    }
                    mainHandler.post {
                        chargers = ChargePointOrderStore(carContext).ordered(result.getOrDefault(emptyList()))
                        favoritesLoadAttempted = true
                        message = result.fold(
                            onSuccess = { if (it.isEmpty()) "No tienes cargadores favoritos." else "" },
                            onFailure = { "No se pudieron actualizar los favoritos." }
                        )
                        loading = false
                        invalidateIfActive()
                    }
                }
            }
        }
    }

    private fun invalidateIfActive() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    private fun title(): String = when (mode) {
            Mode.FAVORITES -> if (chargers.isEmpty()) "MisCargadores" else "MisCargadores (${chargers.size})"
            Mode.NEARBY -> if (chargers.isEmpty()) "Cerca de mí" else "Cerca de mí (${chargers.size})"
        }
}
