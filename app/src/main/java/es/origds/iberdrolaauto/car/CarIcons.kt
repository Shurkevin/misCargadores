package es.origds.iberdrolaauto.car

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import es.origds.iberdrolaauto.R

/** Small, high-contrast assets; the Android Auto host controls their final size. */
object CarIcons {
    fun charger(context: Context, available: Boolean = true): CarIcon = icon(
        context,
        if (available) R.drawable.ic_charger else R.drawable.ic_charger_unavailable
    )

    fun refresh(context: Context): CarIcon = icon(context, R.drawable.ic_refresh)

    fun search(context: Context): CarIcon = icon(context, R.drawable.ic_search)

    fun socket(context: Context, available: Boolean): CarIcon = icon(
        context,
        if (available) R.drawable.ic_socket_available else R.drawable.ic_socket_unavailable
    )

    private fun icon(context: Context, resource: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, resource)).build()
}
