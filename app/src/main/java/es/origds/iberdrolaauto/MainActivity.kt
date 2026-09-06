package es.origds.iberdrolaauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import es.origds.iberdrolaauto.auth.AuthSettings
import es.origds.iberdrolaauto.auth.OAuthCoordinator
import es.origds.iberdrolaauto.auth.TokenStore
import es.origds.iberdrolaauto.auth.TokenRefresher
import es.origds.iberdrolaauto.data.ChargePoint
import es.origds.iberdrolaauto.data.singleAvailableSocketName
import es.origds.iberdrolaauto.data.ChargePointNameStore
import es.origds.iberdrolaauto.data.ChargePointOrderStore
import es.origds.iberdrolaauto.data.IberdrolaReadOnlyRepository
import es.origds.iberdrolaauto.data.OpenChargeMapRepository
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var tokenStore: TokenStore
    private lateinit var tokenRefresher: TokenRefresher
    private lateinit var oauth: OAuthCoordinator
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var chargerList: LinearLayout
    private lateinit var reorderAction: LinearLayout
    private lateinit var renameAction: LinearLayout
    private lateinit var reorderActionTitle: TextView
    private lateinit var renameActionTitle: TextView
    private lateinit var finishReorderAction: TextView
    private lateinit var mobileSearchActions: LinearLayout
    private lateinit var nearbyAction: TextView
    private lateinit var providerLoginAction: LinearLayout
    private lateinit var providerKeyAction: LinearLayout
    private lateinit var providerLoginStatus: TextView
    private lateinit var providerKeyStatus: TextView
    private var settingsDialog: BottomSheetDialog? = null
    private lateinit var orderStore: ChargePointOrderStore
    private lateinit var nameStore: ChargePointNameStore
    private var points: MutableList<ChargePoint> = mutableListOf()
    private var reordering = false
    private var renaming = false
    private var showingNearby = false
    private var locationPermissionPending = false
    private var favoritePoints: MutableList<ChargePoint> = mutableListOf()
    private val providerPreferences by lazy { getSharedPreferences("providers", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        tokenStore = TokenStore(this)
        tokenRefresher = TokenRefresher(this, tokenStore)
        oauth = OAuthCoordinator(this, tokenStore)
        orderStore = ChargePointOrderStore(this)
        nameStore = ChargePointNameStore(this)
        setContentView(content())
        updateState()
        if (tokenStore.accessToken() != null) refreshChargePoints()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OAuthCoordinator.REQUEST_CODE) oauth.handleResult(data) { result ->
            result.fold(
                onSuccess = {
                    status.text = "Sesión conectada. Cargando tus favoritos…"
                    updateState()
                    refreshChargePoints()
                },
                onFailure = { status.text = "No se pudo completar el inicio de sesión: ${it.message}" }
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && locationPermissionPending) {
            locationPermissionPending = false
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                findNearbyFromPhone()
            } else {
                status.text = "Necesitamos permiso de ubicación para buscar cerca de ti."
            }
        }
    }

    override fun onDestroy() {
        tokenRefresher.dispose()
        oauth.dispose()
        super.onDestroy()
    }

    private fun content(): DrawerLayout = DrawerLayout(this).apply {
        setBackgroundColor(color(R.color.iberdrola_background))
        addView(FrameLayout(context).apply {
        addView(SwipeRefreshLayout(context).apply {
            swipeRefresh = this
            setColorSchemeResources(R.color.iberdrola_green, R.color.iberdrola_success)
            setOnRefreshListener {
                if (tokenStore.accessToken() != null) refreshChargePoints() else isRefreshing = false
            }
            addView(ScrollView(context).apply {
                isFillViewport = true
                addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(48), dp(20), dp(28))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(color(R.color.iberdrola_green_dark), 28)
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    addView(Button(context).apply {
                        text = "⚙"
                        textSize = 21f
                        setTextColor(Color.WHITE)
                        background = rounded(Color.TRANSPARENT, 18)
                        minWidth = dp(40)
                        minHeight = dp(40)
                        setPadding(0, 0, 0, 0)
                        contentDescription = "Abrir ajustes"
                        setOnClickListener { showSettingsWindow() }
                    }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(16)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START
                        addView(TextView(context).apply {
                            text = "CARGADORES PRIVADOS"
                            textSize = 12f
                            letterSpacing = 0.12f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.START
                        })
                        addView(TextView(context).apply {
                            text = "Tus favoritos\nen un vistazo"
                            textSize = 28f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.START
                            setPadding(0, dp(8), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, matchWidth().apply { bottomMargin = dp(20) })
                status = TextView(context).apply {
                    textSize = 15f
                    setTextColor(color(R.color.iberdrola_green_dark))
                    background = rounded(color(R.color.iberdrola_mint), 16)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
                addView(status, matchWidth())
                mobileSearchActions = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(12), 0, 0)
                    nearbyAction = mobileAction("⌖  Cerca de mí") {
                        if (showingNearby) showFavorites() else findNearbyFromPhone()
                    }
                    addView(nearbyAction, LinearLayout.LayoutParams(0, dp(46), 1f))
                    addView(mobileAction("⌕  Dirección") { searchNearbyAddress() },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
                }
                addView(mobileSearchActions, matchWidth())
                chargerList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(chargerList, matchWidth().apply { topMargin = dp(20) })
                action = Button(context).apply {
                    stylePrimaryButton(this)
                    setOnClickListener {
                        if (tokenStore.accessToken() == null) {
                            oauth.begin(AuthSettings()).onFailure { status.text = it.message }
                        } else refreshChargePoints()
                    }
                }
                addView(action, matchWidth().apply { topMargin = dp(12) })
                })
            })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        finishReorderAction = TextView(context).apply {
            text = "✓"
            textSize = 30f
            gravity = Gravity.CENTER
            contentDescription = "Terminar de reordenar"
            setTextColor(Color.WHITE)
            background = rounded(color(R.color.iberdrola_green), 100)
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener {
                reordering = false
                reorderActionTitle.text = "Ordenar cargadores"
                visibility = View.GONE
                status.text = "Orden guardado."
                status.setTextColor(color(R.color.iberdrola_muted))
                status.background = rounded(Color.parseColor("#ECEFED"), 16)
                status.postDelayed({
                    if (status.text == "Orden guardado.") {
                        status.text = "${points.size} cargadores favoritos."
                        status.setTextColor(color(R.color.iberdrola_green_dark))
                        status.background = rounded(color(R.color.iberdrola_mint), 16)
                    }
                }, 3_000)
                renderChargePoints()
            }
        }
        addView(finishReorderAction, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(24)
            bottomMargin = dp(28)
        })
        }, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT))
        // Build the panel once so its controls are initialized; it is opened as a standalone window.
        settingsPanel()
    }

    private fun showSettingsWindow() {
        if (settingsDialog?.isShowing == true) return
        settingsDialog = BottomSheetDialog(this, R.style.Theme_IberdrolaAuto_BottomSheet).apply {
            setContentView(settingsPanel())
            setOnDismissListener { settingsDialog = null }
            setOnShowListener {
                findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.apply {
                        background = ColorDrawable(Color.TRANSPARENT)
                        setPadding(0, 0, 0, 0)
                        layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                    }
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    WindowInsetsControllerCompat(this, decorView).isAppearanceLightNavigationBars = false
                }
                refreshProviderControls()
                refreshSettingsActions()
            }
            show()
        }
    }

    private fun closeSettings() {
        settingsDialog?.dismiss()
    }

    private data class ProviderRow(val container: LinearLayout, val status: TextView)
    private data class SettingRow(val container: LinearLayout, val title: TextView)

    private fun providerRow(monogram: String, title: String, detail: String): ProviderRow {
        val status = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(color(R.color.iberdrola_success))
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(62)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = monogram
                textSize = if (monogram.length > 1) 9f else 16f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.iberdrola_success))
                background = rounded(color(R.color.iberdrola_mint), 99)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(11) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(color(R.color.iberdrola_ink))
                })
                addView(TextView(context).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(color(R.color.iberdrola_muted))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
            addView(TextView(context).apply {
                text = "›"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.iberdrola_muted))
            }, LinearLayout.LayoutParams(dp(18), dp(36)))
        }
        return ProviderRow(row, status)
    }

    private fun settingRow(icon: String, label: String, detail: String): SettingRow {
        val title = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(color(R.color.iberdrola_ink))
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.iberdrola_green_dark))
            }, LinearLayout.LayoutParams(dp(38), dp(42)).apply { rightMargin = dp(5) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(TextView(context).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(color(R.color.iberdrola_muted))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.iberdrola_muted))
            }, LinearLayout.LayoutParams(dp(22), dp(42)))
        }
        return SettingRow(row, title)
    }

    private fun refreshSettingsActions() {
        if (!::reorderAction.isInitialized || !::renameAction.isInitialized) return
        val enabled = favoritePoints.isNotEmpty()
        listOf(reorderAction, renameAction).forEach {
            it.visibility = View.VISIBLE
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun settingsPanel(): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(color(R.color.iberdrola_surface), 28)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(12), dp(22), dp(16))
                addView(View(context).apply {
                background = rounded(Color.parseColor("#D6DFD9"), 99)
                }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(20)
                })
                addView(LinearLayout(context).apply {
                gravity = Gravity.TOP
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "☷"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.iberdrola_success))
                    background = rounded(color(R.color.iberdrola_mint), 12)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Ajustes"
                        textSize = 24f
                        setTextColor(color(R.color.iberdrola_ink))
                    })
                    addView(TextView(context).apply {
                        text = "Personaliza cómo ves tus cargadores."
                        textSize = 14f
                        setTextColor(color(R.color.iberdrola_muted))
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, matchWidth().apply { bottomMargin = dp(24) })
                addView(TextView(context).apply {
                text = "FUENTES DE DATOS"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(color(R.color.iberdrola_muted))
                }, matchWidth().apply { bottomMargin = dp(8) })
                addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(color(R.color.iberdrola_background), 16)
                setPadding(dp(12), dp(2), dp(12), dp(2))
                providerRow("I", "Iberdrola", "Tu cuenta de movilidad").also { row ->
                    providerLoginAction = row.container
                    providerLoginStatus = row.status
                    row.container.setOnClickListener { beginProviderLogin() }
                    addView(row.container, matchWidth())
                }
                addView(View(context).apply { setBackgroundColor(Color.parseColor("#E4EBE6")) }, matchWidth().apply { height = dp(1) })
                providerRow("OCM", "Open Charge Map", "Datos de carga pública").also { row ->
                    providerKeyAction = row.container
                    providerKeyStatus = row.status
                    row.container.setOnClickListener { configureOpenChargeMapKey() }
                    addView(row.container, matchWidth())
                }
                }, matchWidth().apply { bottomMargin = dp(20) })
                addView(TextView(context).apply {
                text = "PREFERENCIAS"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(color(R.color.iberdrola_muted))
                }, matchWidth().apply { bottomMargin = dp(6) })
                settingRow("↕", "Ordenar cargadores", "Elige el orden de tu lista").also { row ->
                reorderAction = row.container
                reorderActionTitle = row.title
                row.container.setOnClickListener {
                    reordering = !reordering
                    if (reordering) renaming = false
                    reorderActionTitle.text = if (reordering) "Terminar de ordenar" else "Ordenar cargadores"
                    renameActionTitle.text = "Personalizar nombres"
                    finishReorderAction.visibility = if (reordering) View.VISIBLE else View.GONE
                    closeSettings()
                    renderChargePoints()
                }
                addView(row.container, matchWidth())
                }
                addView(View(context).apply { setBackgroundColor(Color.parseColor("#E4EBE6")) }, matchWidth().apply { height = dp(1) })
                settingRow("✎", "Personalizar nombres", "Identifica tus lugares habituales").also { row ->
                renameAction = row.container
                renameActionTitle = row.title
                row.container.setOnClickListener {
                    renaming = !renaming
                    if (renaming) {
                        reordering = false
                        finishReorderAction.visibility = View.GONE
                    }
                    renameActionTitle.text = if (renaming) "Terminar de personalizar" else "Personalizar nombres"
                    reorderActionTitle.text = "Ordenar cargadores"
                    closeSettings()
                    renderChargePoints()
                }
                addView(row.container, matchWidth())
                }
            }, matchWidth())
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ColorDrawable(Color.parseColor("#B63832"))
                setPadding(dp(22), dp(11), dp(22), dp(20))
                addView(TextView(context).apply {
                    text = "↪  Cerrar sesión"
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(46)
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Cerrar sesión")
                            .setMessage("Tendrás que iniciar sesión de nuevo para consultar tus cargadores de Iberdrola.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Cerrar sesión") { _, _ ->
                                tokenStore.clear()
                                closeSettings()
                                updateState()
                            }
                            .show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWidth())
        })
    }

    private fun updateState() {
        val connected = tokenStore.accessToken() != null
        if (!connected && !::status.isInitialized) return
        if (!connected) status.text = "Aún no hay sesión. El inicio de sesión se abrirá en el navegador de Iberdrola cuando tengamos una configuración OAuth autorizada."
        if (!connected) {
            points.clear()
            favoritePoints.clear()
            chargerList.removeAllViews()
            reordering = false
            renaming = false
            finishReorderAction.visibility = View.GONE
            mobileSearchActions.visibility = View.GONE
        }
        action.visibility = if (connected) View.GONE else View.VISIBLE
        action.text = "Iniciar sesión con Iberdrola"
        swipeRefresh.isEnabled = connected
        refreshProviderControls()
        refreshSettingsActions()
        if (connected) updateSearchActions()
    }

    private fun refreshProviderControls() {
        val iberdrolaConfigured = tokenStore.accessToken() != null
        val openChargeMapConfigured = !providerPreferences.getString("ocm_api_key", null).isNullOrBlank()

        providerLoginStatus.text = if (iberdrolaConfigured) "Conectado" else "Conectar"
        providerLoginStatus.setTextColor(if (iberdrolaConfigured) color(R.color.iberdrola_success) else color(R.color.iberdrola_green_dark))
        providerLoginAction.isEnabled = !iberdrolaConfigured
        providerLoginAction.alpha = 1f

        providerKeyStatus.text = if (openChargeMapConfigured) "Configurada" else "Añadir clave"
        providerKeyStatus.setTextColor(if (openChargeMapConfigured) color(R.color.iberdrola_success) else color(R.color.iberdrola_green_dark))
    }

    private fun configureOpenChargeMapKey() {
        if (providerPreferences.getString("ocm_api_key", null).isNullOrBlank()) {
            showOpenChargeMapTutorial()
        } else {
            showOpenChargeMapKeyDialog()
        }
    }

    private fun showOpenChargeMapTutorial() {
        AlertDialog.Builder(this)
            .setTitle("Configurar Open Charge Map")
            .setMessage(
                "Necesitas una API key gratuita para que MisCargadores pueda consultar sus datos.\n\n" +
                    "1. Crea una cuenta en openchargemap.org.\n" +
                    "2. Entra en tu perfil y abre My Apps.\n" +
                    "3. Pulsa Register an Application y crea una aplicación personal.\n" +
                    "4. Copia la API key y vuelve aquí para pegarla.\n\n" +
                    "La clave se guarda únicamente en este teléfono."
            )
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Abrir web") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://openchargemap.org/site/develop/api")))
            }
            .setPositiveButton("Ya tengo la clave") { _, _ -> showOpenChargeMapKeyDialog() }
            .show()
    }

    private fun showOpenChargeMapKeyDialog() {
        val input = EditText(this).apply {
            hint = "API key"
            setSingleLine()
            setText(providerPreferences.getString("ocm_api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Open Charge Map")
            .setMessage("La API key se guarda solo en este teléfono.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                providerPreferences.edit().putString("ocm_api_key", input.text.toString().trim()).apply()
                refreshProviderControls()
            }
            .show()
    }

    private fun beginProviderLogin() {
        oauth.begin(AuthSettings()).onFailure { status.text = it.message }
    }

    private fun refreshChargePoints() {
        swipeRefresh.isRefreshing = true
        status.text = "Consultando cargadores autorizados…"
        tokenRefresher.refreshIfNeeded { tokenResult ->
            tokenResult.onFailure { error ->
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    status.text = error.message ?: "La sesión ha caducado. Inicia sesión de nuevo."
                }
            }.onSuccess { token ->
                executor.execute {
                    val result = runCatching { IberdrolaReadOnlyRepository(this).authorizedChargePoints(token) }
                    runOnUiThread {
                swipeRefresh.isRefreshing = false
                status.text = result.fold(
                    onSuccess = { points ->
                        favoritePoints = orderStore.ordered(points).toMutableList()
                        if (!showingNearby) this.points = favoritePoints.toMutableList()
                        reordering = false
                        renaming = false
                        finishReorderAction.visibility = View.GONE
                        reorderActionTitle.text = "Ordenar cargadores"
                        renameActionTitle.text = "Personalizar nombres"
                        refreshSettingsActions()
                        if (!showingNearby) renderChargePoints()
                        if (points.isEmpty()) "No se han encontrado favoritos autorizados." else "${points.size} cargadores favoritos."
                    },
                    onFailure = {
                        this.points.clear()
                        chargerList.removeAllViews()
                        refreshSettingsActions()
                        finishReorderAction.visibility = View.GONE
                        "No se han podido actualizar los cargadores: ${it.message}"
                    }
                )
                    }
                }
            }
        }
    }

    private fun updateSearchActions() {
        mobileSearchActions.visibility = View.VISIBLE
        nearbyAction.text = if (showingNearby) "←  Favoritos" else "⌖  Cerca de mí"
    }

    private fun showFavorites() {
        showingNearby = false
        reordering = false
        renaming = false
        finishReorderAction.visibility = View.GONE
        refreshSettingsActions()
        points = favoritePoints.toMutableList()
        status.text = if (points.isEmpty()) "No se han encontrado favoritos autorizados." else "${points.size} cargadores favoritos."
        updateSearchActions()
        renderChargePoints()
    }

    private fun findNearbyFromPhone() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermissionPending = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { locationManager.isProviderEnabled(it) }
        if (provider == null) {
            status.text = "Activa la ubicación del teléfono para buscar cerca de ti."
            return
        }
        status.text = "Obteniendo tu ubicación…"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, executor) { location ->
                if (location == null) {
                    runOnUiThread { status.text = "No se pudo obtener tu ubicación actual." }
                } else {
                    searchNearby(location.latitude, location.longitude, "Buscando cargadores disponibles cerca de ti…")
                }
            }
        } else {
            val location = locationManager.getLastKnownLocation(provider)
            if (location == null) status.text = "No hay una ubicación reciente disponible."
            else searchNearby(location.latitude, location.longitude, "Buscando cargadores disponibles cerca de ti…")
        }
    }

    private fun searchNearbyAddress() {
        val dialog = BottomSheetDialog(this, R.style.Theme_IberdrolaAuto_BottomSheet)
        val input = EditText(this).apply {
            hint = "Dirección, ciudad o código postal"
            setSingleLine()
            textSize = 16f
            setTextColor(color(R.color.iberdrola_ink))
            setHintTextColor(color(R.color.iberdrola_muted))
            background = rounded(color(R.color.iberdrola_background), 16, color(R.color.iberdrola_green))
            setPadding(dp(16), 0, dp(16), 0)
        }
        val searchAction = TextView(this).apply {
            text = "Buscar"
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(color(R.color.iberdrola_green), 16)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    input.error = "Escribe una dirección, ciudad o código postal"
                    return@setOnClickListener
                }
                dialog.dismiss()
                status.text = "Buscando la dirección…"
                executor.execute {
                    val location = runCatching {
                        @Suppress("DEPRECATION")
                        Geocoder(this@MainActivity, Locale("es", "ES")).getFromLocationName(query, 1)
                            ?.firstOrNull()
                    }.getOrNull()
                    if (location == null) {
                        runOnUiThread { status.text = "No se encontró esa dirección. Prueba con ciudad y provincia." }
                    } else {
                        searchNearby(location.latitude, location.longitude, "Buscando cargadores cerca de la dirección…")
                    }
                }
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(color(R.color.iberdrola_surface), 28)
            setPadding(dp(22), dp(12), dp(22), dp(24))
            addView(View(context).apply {
                background = rounded(Color.parseColor("#D6DFD9"), 99)
            }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.TOP
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "⌕"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.iberdrola_success))
                    background = rounded(color(R.color.iberdrola_mint), 12)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Buscar por dirección"
                        textSize = 23f
                        setTextColor(color(R.color.iberdrola_ink))
                    })
                    addView(TextView(context).apply {
                        text = "Te mostraremos cargadores disponibles cerca de la ubicación indicada."
                        textSize = 14f
                        setTextColor(color(R.color.iberdrola_muted))
                        setPadding(0, dp(5), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWidth().apply { bottomMargin = dp(22) })
            addView(input, matchWidth().apply { height = dp(54); bottomMargin = dp(22) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                addView(TextView(context).apply {
                    text = "Cancelar"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    setTextColor(color(R.color.iberdrola_green_dark))
                    setPadding(dp(16), 0, dp(16), 0)
                    setOnClickListener { dialog.dismiss() }
                })
                addView(searchAction)
            }, matchWidth())
        }
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.background = ColorDrawable(Color.TRANSPARENT)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                WindowInsetsControllerCompat(this, decorView).isAppearanceLightNavigationBars = true
            }
        }
        dialog.show()
    }

    private fun searchNearby(latitude: Double, longitude: Double, loadingMessage: String) {
        runOnUiThread {
            showingNearby = true
            reordering = false
            renaming = false
            finishReorderAction.visibility = View.GONE
            points.clear()
            status.text = loadingMessage
            updateSearchActions()
            renderChargePoints()
        }
        executor.execute {
            val token = tokenStore.accessToken()
            val merged = mutableListOf<ChargePoint>()
            val errors = mutableListOf<String>()
            if (token == null) {
                errors += "Inicia sesión en Iberdrola"
            } else {
                runCatching {
                    IberdrolaReadOnlyRepository(this).nearbyAvailableChargePoints(token, latitude, longitude).availablePoints
                }.onSuccess { merged.addAll(it) }.onFailure { errors += "Iberdrola: ${it.message}" }
            }
            val key = providerPreferences.getString("ocm_api_key", null).orEmpty()
            if (key.isBlank()) {
                errors += "Configura la API key de Open Charge Map en Ajustes"
            } else {
                runCatching {
                    OpenChargeMapRepository(this).nearbyChargePoints(key, latitude, longitude)
                }.onSuccess { merged.addAll(it) }.onFailure { errors += "Open Charge Map: ${it.message}" }
            }
            val resultPoints = merged.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            runOnUiThread {
                points = resultPoints.toMutableList()
                status.text = when {
                    resultPoints.isNotEmpty() -> "${resultPoints.size} cargadores encontrados cerca."
                    errors.isNotEmpty() -> errors.joinToString(" · ")
                    else -> "No hay cargadores en esta zona."
                }
                renderChargePoints()
            }
        }
    }

    private fun renderChargePoints() {
        chargerList.removeAllViews()
        points.forEachIndexed { index, point ->
            chargerList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(color(R.color.iberdrola_surface), 20)
                elevation = dp(2).toFloat()
                setPadding(dp(18), dp(16), dp(18), dp(16))
                setOnClickListener {
                    if (!reordering && !renaming) showSocketDetails(point)
                }
                addView(TextView(context).apply {
                    text = nameStore.displayName(point)
                    textSize = 19f
                    setTextColor(color(R.color.iberdrola_ink))
                })
                addView(TextView(context).apply {
                    text = point.provider
                    textSize = 12f
                    letterSpacing = 0.08f
                    setTextColor(color(R.color.iberdrola_green_dark))
                    setPadding(0, dp(5), 0, 0)
                })
                addView(TextView(context).apply {
                    text = point.id
                    textSize = 13f
                    setTextColor(color(R.color.iberdrola_muted))
                    setPadding(0, dp(4), 0, 0)
                })
                if (showingNearby) point.distanceKm?.let { distance ->
                    addView(TextView(context).apply {
                        text = String.format(Locale("es", "ES"), "A %.1f km · %s", distance, point.access)
                        textSize = 14f
                        setTextColor(color(R.color.iberdrola_green_dark))
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(14), 0, 0)
                    val availabilityLabel = if (!point.availabilityKnown) {
                        "Estado no verificado"
                    } else if (point.availableSockets == 1) {
                        "Disponible"
                    } else if (point.totalSockets == 1) {
                        "No disponible"
                    } else {
                        "${point.availableSockets}/${point.totalSockets} disponibles"
                    }
                    addView(badge(
                        availabilityLabel,
                        color(R.color.iberdrola_mint),
                        if (point.availableSockets > 0) color(R.color.iberdrola_success) else color(R.color.iberdrola_muted)
                    ))
                    addView(badge("${point.powerKw} kW", Color.parseColor("#E9EEEB"), color(R.color.iberdrola_ink)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
                })
                point.singleAvailableSocketName()?.let { socketName ->
                    addView(TextView(context).apply {
                        text = "Disponible: $socketName"
                        textSize = 14f
                        setTextColor(color(R.color.iberdrola_success))
                        setPadding(0, dp(10), 0, 0)
                    })
                }
                if (reordering) addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    background = rounded(color(R.color.iberdrola_background), 14)
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    addView(TextView(context).apply {
                        text = "ORDEN  ${index + 1}/${points.size}"
                        textSize = 12f
                        letterSpacing = 0.08f
                        setTextColor(color(R.color.iberdrola_muted))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(orderControl("↑", "Subir cargador", index > 0) {
                        moveChargePoint(index, index - 1)
                    }, LinearLayout.LayoutParams(dp(38), dp(38)))
                    addView(orderControl("↓", "Bajar cargador", index < points.lastIndex) {
                        moveChargePoint(index, index + 1)
                    }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) })
                }, matchWidth().apply { topMargin = dp(12) })
                if (renaming) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(16), 0, 0)
                    addView(TextView(context).apply {
                        text = "NOMBRE EN ANDROID AUTO"
                        textSize = 11f
                        letterSpacing = 0.08f
                        setTextColor(color(R.color.iberdrola_muted))
                    })
                    addView(TextView(context).apply {
                        text = if (nameStore.hasAlias(point)) "Alias personalizado activo" else "Usando el nombre de Iberdrola"
                        textSize = 14f
                        setTextColor(color(R.color.iberdrola_muted))
                        setPadding(0, dp(4), 0, 0)
                    })
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(12), 0, 0)
                        addView(aliasAction("Editar nombre", true) { renameChargePoint(point) },
                            LinearLayout.LayoutParams(0, dp(42), 1f))
                        if (nameStore.hasAlias(point)) {
                            addView(aliasAction("Restablecer", false) { removeAlias(point) },
                                LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
                        }
                    }, matchWidth())
                }, matchWidth())
            }, matchWidth().apply { bottomMargin = dp(12) })
        }
    }

    private fun showSocketDetails(point: ChargePoint) {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.iberdrola_surface), 30)
            setPadding(dp(24), dp(12), dp(24), dp(24))
            addView(View(context).apply {
                background = rounded(Color.parseColor("#CAD2CD"), 99)
            }, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(color(R.color.iberdrola_green_dark), 20)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(context).apply {
                    text = "CARGADOR"
                    textSize = 11f
                    letterSpacing = 0.14f
                    setTextColor(color(R.color.iberdrola_mint))
                })
                addView(TextView(context).apply {
                    text = nameStore.displayName(point)
                    textSize = 22f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(6), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "${point.availableSockets}/${point.totalSockets} disponibles · ${point.powerKw} kW"
                    textSize = 14f
                    setTextColor(color(R.color.iberdrola_mint))
                    setPadding(0, dp(8), 0, 0)
                })
            }, matchWidth().apply { bottomMargin = dp(20) })
            addView(TextView(context).apply {
                text = "TOMAS"
                textSize = 13f
                letterSpacing = 0.1f
                setTextColor(color(R.color.iberdrola_muted))
                setPadding(0, 0, 0, dp(8))
            })
            if (point.sockets.isEmpty()) {
                addView(TextView(context).apply {
                    text = "No hay detalle de tomas disponible."
                    textSize = 16f
                    setTextColor(color(R.color.iberdrola_muted))
                    background = rounded(color(R.color.iberdrola_background), 16)
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                })
            } else {
                point.sockets.forEach { socket ->
                    addView(socketCard(socket.name, socket.available), matchWidth().apply { bottomMargin = dp(8) })
                }
            }
            addView(Button(context).apply {
                text = "Listo"
                styleSecondaryButton(this)
                setOnClickListener { dialog.dismiss() }
            }, matchWidth().apply { topMargin = dp(16) })
        }
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.background = ColorDrawable(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun socketCard(name: String, available: Boolean): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        background = rounded(color(R.color.iberdrola_background), 16)
        minimumHeight = dp(72)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(context).apply {
            text = "↯"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(if (available) color(R.color.iberdrola_success) else color(R.color.iberdrola_muted))
            background = rounded(if (available) color(R.color.iberdrola_mint) else Color.parseColor("#E8ECE9"), 99)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            text = name
            textSize = 17f
            setTextColor(color(R.color.iberdrola_ink))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(badge(
            if (available) "Disponible" else "No disponible",
            if (available) color(R.color.iberdrola_mint) else Color.parseColor("#E8ECE9"),
            if (available) color(R.color.iberdrola_success) else color(R.color.iberdrola_muted)
        ))
    }

    private fun moveChargePoint(from: Int, to: Int) {
        val point = points.removeAt(from)
        points.add(to, point)
        orderStore.save(points)
        renderChargePoints()
        animateReorder(from, to)
    }

    private fun animateReorder(from: Int, to: Int) {
        chargerList.post {
            val shift = dp(68).toFloat()
            val movingUp = to < from

            chargerList.getChildAt(to)?.apply {
                translationY = if (movingUp) shift else -shift
                alpha = 0.72f
                animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            val firstAffected = minOf(from, to)
            val lastAffected = maxOf(from, to)
            for (index in firstAffected..lastAffected) {
                if (index == to) continue
                chargerList.getChildAt(index)?.apply {
                    translationY = if (movingUp) -shift else shift
                    animate()
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }
    }

    private fun renameChargePoint(point: ChargePoint) {
        val input = EditText(this).apply {
            setText(nameStore.displayName(point))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre en Android Auto")
            .setMessage("Este nombre se guarda solo en este teléfono.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Restablecer") { _, _ ->
                nameStore.clear(point)
                renderChargePoints()
            }
            .setPositiveButton("Guardar") { _, _ ->
                nameStore.save(point, input.text.toString())
                renderChargePoints()
            }
            .show()
    }

    private fun removeAlias(point: ChargePoint) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar alias")
            .setMessage("Se restaurará el nombre original de Iberdrola para este cargador.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                nameStore.clear(point)
                renderChargePoints()
            }
            .show()
    }

    private fun badge(text: String, background: Int, foreground: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(foreground)
        this.background = rounded(background, 100)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun orderControl(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 22f
        gravity = Gravity.CENTER
        contentDescription = description
        setTextColor(color(R.color.iberdrola_green_dark))
        background = rounded(color(R.color.iberdrola_mint), 12)
        alpha = if (enabled) 1f else 0.3f
        isEnabled = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun aliasAction(label: String, emphasized: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        contentDescription = label
        setTextColor(if (emphasized) Color.WHITE else color(R.color.iberdrola_green_dark))
        background = if (emphasized) {
            rounded(color(R.color.iberdrola_green), 12)
        } else {
            rounded(Color.TRANSPARENT, 12, color(R.color.iberdrola_green))
        }
        setOnClickListener { onClick() }
    }

    private fun mobileAction(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        contentDescription = label
        setTextColor(color(R.color.iberdrola_green_dark))
        background = rounded(color(R.color.iberdrola_surface), 14, color(R.color.iberdrola_green))
        setOnClickListener { onClick() }
    }

    private fun stylePrimaryButton(button: Button) = button.apply {
        setTextColor(Color.WHITE)
        background = rounded(color(R.color.iberdrola_green), 16)
        minHeight = dp(52)
    }

    private fun styleSecondaryButton(button: Button) = button.apply {
        setTextColor(color(R.color.iberdrola_green_dark))
        background = rounded(Color.TRANSPARENT, 16, color(R.color.iberdrola_green))
        minHeight = dp(48)
    }

    private fun styleDangerButton(button: Button) = button.apply {
        setTextColor(Color.WHITE)
        background = rounded(Color.parseColor("#B3261E"), 16)
        minHeight = dp(48)
    }

    private fun styleCompactButton(button: Button) = button.apply {
        setTextColor(color(R.color.iberdrola_green_dark))
        background = rounded(color(R.color.iberdrola_mint), 12)
        minHeight = dp(42)
    }

    private fun rounded(background: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(background)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun roundedTop(background: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val radius = dp(radiusDp).toFloat()
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(background)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LOCATION_PERMISSION_REQUEST = 41
    }
}
