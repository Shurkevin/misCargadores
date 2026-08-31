package es.origds.iberdrolaauto

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import es.origds.iberdrolaauto.auth.AuthSettings
import es.origds.iberdrolaauto.auth.OAuthCoordinator
import es.origds.iberdrolaauto.auth.TokenStore
import es.origds.iberdrolaauto.data.ChargePoint
import es.origds.iberdrolaauto.data.singleAvailableSocketName
import es.origds.iberdrolaauto.data.ChargePointNameStore
import es.origds.iberdrolaauto.data.ChargePointOrderStore
import es.origds.iberdrolaauto.data.IberdrolaReadOnlyRepository
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var tokenStore: TokenStore
    private lateinit var oauth: OAuthCoordinator
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var chargerList: LinearLayout
    private lateinit var reorderAction: Button
    private lateinit var renameAction: Button
    private lateinit var settingsDrawer: DrawerLayout
    private lateinit var orderStore: ChargePointOrderStore
    private lateinit var nameStore: ChargePointNameStore
    private var points: MutableList<ChargePoint> = mutableListOf()
    private var reordering = false
    private var renaming = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        tokenStore = TokenStore(this)
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

    override fun onDestroy() {
        oauth.dispose()
        super.onDestroy()
    }

    private fun content(): DrawerLayout = DrawerLayout(this).apply {
        settingsDrawer = this
        setBackgroundColor(color(R.color.iberdrola_background))
        val drawer = this
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
                        text = "☰"
                        textSize = 21f
                        setTextColor(Color.WHITE)
                        background = rounded(Color.TRANSPARENT, 18)
                        minWidth = dp(40)
                        minHeight = dp(40)
                        setPadding(0, 0, 0, 0)
                        contentDescription = "Abrir ajustes"
                        setOnClickListener { drawer.openDrawer(GravityCompat.START) }
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
        }, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT))
        addView(settingsPanel(), DrawerLayout.LayoutParams(dp(320), DrawerLayout.LayoutParams.MATCH_PARENT, GravityCompat.START))
    }

    private fun settingsPanel(): ScrollView = ScrollView(this).apply {
        setBackgroundColor(color(R.color.iberdrola_surface))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(72), dp(24), dp(28))
            addView(TextView(context).apply {
                text = "AJUSTES"
                textSize = 12f
                letterSpacing = 0.12f
                setTextColor(color(R.color.iberdrola_green))
            })
            addView(TextView(context).apply {
                text = "Personaliza tu lista"
                textSize = 26f
                setTextColor(color(R.color.iberdrola_ink))
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(TextView(context).apply {
                text = "Los cambios se guardan en este teléfono y se reflejan en Android Auto."
                textSize = 15f
                setTextColor(color(R.color.iberdrola_muted))
                setPadding(0, 0, 0, dp(24))
            })
            reorderAction = Button(context).apply {
                text = "Reordenar cargadores"
                visibility = View.GONE
                styleSecondaryButton(this)
                setOnClickListener {
                    reordering = !reordering
                    if (reordering) renaming = false
                    text = if (reordering) "Terminar de ordenar" else "Reordenar cargadores"
                    renameAction.text = "Personalizar nombres"
                    settingsDrawer.closeDrawer(GravityCompat.START)
                    renderChargePoints()
                }
            }
            addView(reorderAction, matchWidth())
            renameAction = Button(context).apply {
                text = "Personalizar nombres"
                visibility = View.GONE
                styleSecondaryButton(this)
                setOnClickListener {
                    renaming = !renaming
                    if (renaming) reordering = false
                    text = if (renaming) "Terminar de personalizar" else "Personalizar nombres"
                    reorderAction.text = "Reordenar cargadores"
                    settingsDrawer.closeDrawer(GravityCompat.START)
                    renderChargePoints()
                }
            }
            addView(renameAction, matchWidth().apply { topMargin = dp(8) })
            addView(View(context).apply { setBackgroundColor(Color.parseColor("#DFE6E1")) }, matchWidth().apply { topMargin = dp(24); bottomMargin = dp(16); height = dp(1) })
            addView(Button(context).apply {
                text = "Cerrar sesión y borrar datos"
                styleDangerButton(this)
                setOnClickListener {
                    tokenStore.clear()
                    settingsDrawer.closeDrawer(GravityCompat.START)
                    updateState()
                }
            }, matchWidth())
        })
    }

    private fun updateState() {
        val connected = tokenStore.accessToken() != null
        if (!connected && !::status.isInitialized) return
        if (!connected) status.text = "Aún no hay sesión. El inicio de sesión se abrirá en el navegador de Iberdrola cuando tengamos una configuración OAuth autorizada."
        if (!connected) {
            points.clear()
            chargerList.removeAllViews()
            reorderAction.visibility = View.GONE
            renameAction.visibility = View.GONE
            reordering = false
            renaming = false
        }
        action.visibility = if (connected) View.GONE else View.VISIBLE
        action.text = "Iniciar sesión con Iberdrola"
        swipeRefresh.isEnabled = connected
    }

    private fun refreshChargePoints() {
        val token = tokenStore.accessToken() ?: return
        swipeRefresh.isRefreshing = true
        status.text = "Consultando cargadores autorizados…"
        executor.execute {
            val result = runCatching { IberdrolaReadOnlyRepository(this).authorizedChargePoints(token) }
            runOnUiThread {
                swipeRefresh.isRefreshing = false
                status.text = result.fold(
                    onSuccess = { points ->
                        this.points = orderStore.ordered(points).toMutableList()
                        reordering = false
                        renaming = false
                        reorderAction.text = "Reordenar cargadores"
                        renameAction.text = "Personalizar nombres"
                        reorderAction.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
                        renameAction.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
                        renderChargePoints()
                        if (points.isEmpty()) "No se han encontrado favoritos autorizados." else "${points.size} cargadores favoritos."
                    },
                    onFailure = {
                        this.points.clear()
                        chargerList.removeAllViews()
                        reorderAction.visibility = View.GONE
                        renameAction.visibility = View.GONE
                        "No se han podido actualizar los cargadores: ${it.message}"
                    }
                )
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
                    text = point.id
                    textSize = 13f
                    setTextColor(color(R.color.iberdrola_muted))
                    setPadding(0, dp(4), 0, 0)
                })
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(14), 0, 0)
                    val availabilityLabel = if (point.availableSockets == 1) {
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
                if (renaming) addView(Button(context).apply {
                    text = "Cambiar nombre"
                    styleCompactButton(this)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener { renameChargePoint(point) }
                })
                if (renaming && nameStore.hasAlias(point)) addView(Button(context).apply {
                    text = "Eliminar alias"
                    styleSecondaryButton(this)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener { removeAlias(point) }
                }, matchWidth().apply { topMargin = dp(8) })
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
        setTextColor(color(R.color.iberdrola_muted))
        background = rounded(Color.TRANSPARENT, 16, color(R.color.iberdrola_muted))
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

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
