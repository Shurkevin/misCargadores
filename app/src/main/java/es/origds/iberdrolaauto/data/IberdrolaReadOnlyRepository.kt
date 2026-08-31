package es.origds.iberdrolaauto.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.UUID

/**
 * Local, read-only adapter for the user's authorised charging catalogue.
 *
 * It deliberately contains no routes for charge starts, reservations, cancellations
 * or payment. The bearer token is sent directly from the phone to Iberdrola.
 */
data class NearbySearchResult(
    val availablePoints: List<ChargePoint>,
    val catalogueCount: Int,
    val detailCount: Int
)

private data class NearbyCandidate(
    val cuprId: Int,
    val distanceKm: Double
)

class IberdrolaReadOnlyRepository(context: Context) : ChargePointRepository {
    private val preferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("id", it).apply()
    }

    override fun authorizedChargePoints(accessToken: String): List<ChargePoint> {
        val favorites = request("GET", FAVORITES, accessToken)
        val cuprIds = linkedSetOf<Int>()
        collectCuprIds(favorites, cuprIds)
        if (cuprIds.isEmpty()) return emptyList()

        val payload = JSONObject().put("cuprId", JSONArray(cuprIds.toList())).toString()
        val details = request("POST", CHARGE_POINT_DETAIL, accessToken, payload)
        return parseChargePoints(details)
    }

    /** Finds the closest public or authorised points in a ~2 km box, then verifies socket status. */
    fun nearbyAvailableChargePoints(accessToken: String, latitude: Double, longitude: Double): NearbySearchResult {
        val radius = 0.02
        val payload = JSONObject().apply {
            put("advantageous", false)
            put("chargePointTypesCodes", JSONArray())
            put("connectorsType", JSONArray())
            put("favoriteInd", JSONObject.NULL)
            put("loadSpeed", JSONArray())
            put("socketStatus", JSONArray())
            put("latitudeMin", latitude - radius)
            put("latitudeMax", latitude + radius)
            put("longitudeMin", longitude - radius)
            put("longitudeMax", longitude + radius)
            put("parkingRestrictionsList", JSONArray())
            put("tagIds", JSONArray())
            put("chargerOperator", JSONArray())
            put("sites", JSONArray())
        }.toString()
        val matches = request(
            "POST",
            NEARBY_CHARGE_POINTS,
            accessToken,
            payload,
            mapOf("numLat" to latitude.toString(), "numLon" to longitude.toString())
        )
        val cuprIds = linkedSetOf<Int>()
        collectCuprIds(matches, cuprIds)
        if (cuprIds.isEmpty()) return NearbySearchResult(emptyList(), 0, 0)
        val nearestIds = nearbyCandidates(matches, latitude, longitude)
            .map { it.cuprId }
            .ifEmpty { cuprIds.toList() }
            .distinct()
            .take(MAX_NEARBY_DETAIL_POINTS)
        val detailedPoints = nearestIds.chunked(DETAIL_BATCH_SIZE).flatMap { batch ->
            val details = request(
                "POST",
                CHARGE_POINT_DETAIL,
                accessToken,
                JSONObject().put("cuprId", JSONArray(batch)).toString()
            )
            parseChargePoints(details, latitude, longitude)
        }
        val availablePoints = detailedPoints
            .filter { it.availableSockets > 0 }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
        return NearbySearchResult(availablePoints, cuprIds.size, detailedPoints.size)
    }

    private fun request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "es-ES")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Plataforma", "Android")
            setRequestProperty("societyId", "1")
            setRequestProperty("deviceid", deviceId)
            setRequestProperty("deviceModel", Build.MODEL)
            setRequestProperty("darkMode", "0")
            setRequestProperty("versionApp", "ANDROID-4.41.1")
            setRequestProperty(
                "User-Agent",
                "Iberdrola/4.41.1/Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})"
            )
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("c-rid", requestId())
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            check(connection.responseCode in 200..299) { "Iberdrola respondió ${connection.responseCode}. ${response.take(180)}" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun collectCuprIds(json: String, ids: MutableSet<Int>) {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.keys().forEach { key ->
                        val child = value.opt(key)
                        if (key.equals("cuprId", ignoreCase = true)) {
                            when (child) {
                                is Number -> ids += child.toInt()
                                is String -> child.trim().toIntOrNull()?.let(ids::add)
                            }
                        }
                        visit(child)
                    }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        runCatching { visit(JSONArray(json)) }.recoverCatching { visit(JSONObject(json)) }.getOrThrow()
    }

    private fun nearbyCandidates(json: String, latitude: Double, longitude: Double): List<NearbyCandidate> {
        val candidates = linkedMapOf<Int, NearbyCandidate>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val location = value.optJSONObject("locationData")
                    val cuprId = idValue(location, "cuprId").toIntOrNull()
                    val pointLatitude = location?.optDouble("latitude", Double.NaN) ?: Double.NaN
                    val pointLongitude = location?.optDouble("longitude", Double.NaN) ?: Double.NaN
                    if (cuprId != null && pointLatitude.isFinite() && pointLongitude.isFinite()) {
                        candidates[cuprId] = NearbyCandidate(
                            cuprId,
                            distanceKm(latitude, longitude, pointLatitude, pointLongitude)
                        )
                    }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        runCatching { visit(JSONArray(json)) }.recoverCatching { visit(JSONObject(json)) }.getOrThrow()
        return candidates.values.sortedBy { it.distanceKm }
    }

    private fun parseChargePoints(
        json: String,
        originLatitude: Double? = null,
        originLongitude: Double? = null
    ): List<ChargePoint> {
        val root = runCatching { JSONArray(json) }.getOrElse {
            val response = JSONObject(json)
            sequenceOf("list", "data", "chargePoints", "result")
                .mapNotNull(response::optJSONArray)
                .firstOrNull()
                ?: JSONArray()
        }
        return buildList {
            for (index in 0 until root.length()) {
                val point = root.optJSONObject(index) ?: continue
                val location = point.optJSONObject("locationData")
                val sockets = point.optJSONArray("logicalSocket")
                var total = 0
                var available = 0
                var maxPower = 0
                val socketStates = mutableListOf<ChargeSocket>()
                for (logicalIndex in 0 until (sockets?.length() ?: 0)) {
                    val logical = sockets?.optJSONObject(logicalIndex) ?: continue
                    val physical = logical.optJSONArray("physicalSocket") ?: continue
                    for (socketIndex in 0 until physical.length()) {
                        val socket = physical.optJSONObject(socketIndex) ?: continue
                        total++
                        val status = socket.optJSONObject("status")?.optInt("statusId", -1) ?: -1
                        val isAvailable = status == 1
                        if (isAvailable) available++
                        maxPower = maxOf(maxPower, socket.optInt("maxPower", 0))
                        socketStates += ChargeSocket(
                            name = socketName(logical, socket, total),
                            available = isAvailable
                        )
                    }
                }
                val publicId = idValue(location, "cuprId").ifBlank { idValue(point, "cpId") }
                val latitude = location?.optDouble("latitude", Double.NaN)
                    ?.takeIf { it.isFinite() }
                val longitude = location?.optDouble("longitude", Double.NaN)
                    ?.takeIf { it.isFinite() }
                add(ChargePoint(
                    id = if (publicId.isBlank()) "ID no disponible" else "ID.$publicId",
                    name = location?.optString("cuprName", "Cargador Iberdrola") ?: "Cargador Iberdrola",
                    availableSockets = available,
                    totalSockets = total,
                    powerKw = maxPower,
                    access = location?.optString("accessType", "Acceso autorizado") ?: "Acceso autorizado",
                    sockets = socketStates,
                    distanceKm = location?.let { locationData ->
                        if (originLatitude != null && originLongitude != null && latitude != null && longitude != null) {
                            distanceKm(originLatitude, originLongitude, latitude, longitude)
                        } else null
                    },
                    latitude = latitude,
                    longitude = longitude
                ))
            }
        }
    }

    private fun idValue(source: JSONObject?, key: String): String = when (val value = source?.opt(key)) {
        is Number -> value.toInt().toString()
        is String -> value.trim()
        else -> ""
    }

    private fun socketName(logical: JSONObject, socket: JSONObject, position: Int): String {
        val connector = sequenceOf("connectorName", "connectorTypeName", "socketTypeName", "connectorType")
            .map { socket.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: sequenceOf("connectorName", "connectorTypeName", "socketTypeName", "connectorType")
                .map { logical.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
        return if (connector.isNullOrBlank()) "Toma $position" else "$connector · Toma $position"
    }

    private fun distanceKm(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
        val earthRadiusKm = 6_371.0
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun requestId(): String = UUID.randomUUID().toString()

    private companion object {
        const val BASE_URL = "https://eva.iberdrola.com/vecomges/api"
        const val FAVORITES = "/appfavoritechargepoint/get-favorite-charge-points"
        const val CHARGE_POINT_DETAIL = "/appchargepoint/getChargePoint"
        const val NEARBY_CHARGE_POINTS = "/appchargepoint/listChargePoints"
        const val MAX_NEARBY_DETAIL_POINTS = 15
        const val DETAIL_BATCH_SIZE = 5
    }
}
