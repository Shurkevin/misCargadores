package es.origds.iberdrolaauto.data

import android.content.Context
import org.json.JSONArray

/** Keeps a personal display order on this device; it never writes to Iberdrola. */
class ChargePointOrderStore(context: Context) {
    private val preferences = context.getSharedPreferences("charger_order", Context.MODE_PRIVATE)

    fun ordered(points: List<ChargePoint>): List<ChargePoint> {
        val positions = savedIds().withIndex().associate { it.value to it.index }
        return points.sortedWith(compareBy({ positions[it.id] ?: Int.MAX_VALUE }, { it.name }))
    }

    fun save(points: List<ChargePoint>) {
        preferences.edit().putString(KEY, JSONArray(points.map { it.id }).toString()).apply()
    }

    private fun savedIds(): List<String> = runCatching {
        val values = JSONArray(preferences.getString(KEY, "[]"))
        List(values.length()) { values.getString(it) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "ids"
    }
}
