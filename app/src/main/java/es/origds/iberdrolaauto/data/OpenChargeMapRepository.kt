package es.origds.iberdrolaauto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Read-only Open Charge Map adapter. OCM status is treated as informational, not live. */
class OpenChargeMapRepository(private val context: Context) {
    fun nearbyChargePoints(apiKey: String, latitude: Double, longitude: Double): List<ChargePoint> {
        require(apiKey.isNotBlank()) { "Configura una API key de Open Charge Map en Ajustes." }
        val url = URL(
            "https://api.openchargemap.io/v3/poi/?output=json&countrycode=ES" +
                "&latitude=$latitude&longitude=$longitude&distance=25&distanceunit=KM" +
                "&maxresults=50&compact=false&verbose=false&key=$apiKey"
        )
        val json = request(url)
        val root = JSONArray(json)
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val address = item.optJSONObject("AddressInfo") ?: continue
                val pointLatitude = address.optDouble("Latitude", Double.NaN)
                val pointLongitude = address.optDouble("Longitude", Double.NaN)
                if (!pointLatitude.isFinite() || !pointLongitude.isFinite()) continue
                val connections = item.optJSONArray("Connections")
                val total = (0 until (connections?.length() ?: 0)).count { connections?.optJSONObject(it) != null }
                val power = (0 until (connections?.length() ?: 0))
                    .mapNotNull { connections?.optJSONObject(it)?.optDouble("PowerKW")?.takeIf(Double::isFinite) }
                    .maxOrNull()?.toInt() ?: 0
                val usage = item.optJSONObject("UsageType")
                val isPrivate = usage?.optBoolean("IsPrivate", false) == true
                val status = item.optJSONObject("StatusType")?.optString("Title").orEmpty()
                add(ChargePoint(
                    id = "OCM.${item.optInt("ID")}",
                    name = address.optString("Title", "Cargador Open Charge Map"),
                    availableSockets = total,
                    totalSockets = total,
                    powerKw = power,
                    access = if (isPrivate) "Acceso privado" else "Acceso público",
                    sockets = (1..total).map { ChargeSocket("Toma $it", false) },
                    distanceKm = distanceKm(latitude, longitude, pointLatitude, pointLongitude),
                    latitude = pointLatitude,
                    longitude = pointLongitude,
                    provider = "Open Charge Map",
                    availabilityKnown = false
                ))
            }
        }.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    }

    private fun request(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MisCargadores/1.0")
            setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            check(connection.responseCode in 200..299) { "Open Charge Map respondió ${connection.responseCode}. ${response.take(180)}" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val value = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371.0 * 2 * atan2(sqrt(value), sqrt(1 - value))
    }
}
