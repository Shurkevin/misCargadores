package es.origds.iberdrolaauto.data

import android.content.Context

/** Personal aliases stored only on this phone, keyed by Iberdrola's point ID. */
class ChargePointNameStore(context: Context) {
    private val preferences = context.getSharedPreferences("charger_names", Context.MODE_PRIVATE)

    fun displayName(point: ChargePoint): String = preferences.getString(point.id, null) ?: point.name

    fun hasAlias(point: ChargePoint): Boolean = preferences.contains(point.id)

    fun save(point: ChargePoint, name: String) {
        val alias = name.trim()
        if (alias.isBlank() || alias == point.name) {
            preferences.edit().remove(point.id).apply()
        } else {
            preferences.edit().putString(point.id, alias).apply()
        }
    }

    fun clear(point: ChargePoint) {
        preferences.edit().remove(point.id).apply()
    }
}
