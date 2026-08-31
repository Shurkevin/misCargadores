package es.origds.iberdrolaauto.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import es.origds.iberdrolaauto.data.ChargePoint
import es.origds.iberdrolaauto.data.ChargePointNameStore

/** Read-only detail screen for the sockets belonging to a favourite charge point. */
class FavoriteChargePointDetailScreen(
    carContext: CarContext,
    private val chargePoint: ChargePoint,
    private val showNavigation: Boolean = false
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val sockets = ItemList.Builder()
        if (chargePoint.sockets.isEmpty()) {
            sockets.addItem(Row.Builder().setTitle("No hay detalle de tomas disponible.").build())
        } else {
            chargePoint.sockets.forEach { socket ->
                sockets.addItem(
                    Row.Builder()
                        .setTitle(socket.name)
                        .addText(if (socket.available) "Disponible" else "No disponible")
                        .setImage(CarIcons.socket(carContext, socket.available))
                        .build()
                )
            }
        }
        val template = ListTemplate.Builder()
            .setTitle(ChargePointNameStore(carContext).displayName(chargePoint))
            .setHeaderAction(Action.BACK)
            .setSingleList(sockets.build())
        if (showNavigation && chargePoint.latitude != null && chargePoint.longitude != null) {
            template.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Ir")
                            .setOnClickListener {
                                carContext.startCarApp(
                                    Intent(CarContext.ACTION_NAVIGATE).setData(
                                        Uri.parse("geo:${chargePoint.latitude},${chargePoint.longitude}")
                                    )
                                )
                            }
                            .build()
                    )
                    .build()
            )
        }
        return template.build()
    }
}
