package es.origds.iberdrolaauto.data

data class ChargePoint(
    val id: String,
    val name: String,
    val availableSockets: Int,
    val totalSockets: Int,
    val powerKw: Int,
    val access: String,
    val sockets: List<ChargeSocket>,
    val distanceKm: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val provider: String = "Iberdrola",
    val availabilityKnown: Boolean = true
)

data class ChargeSocket(
    val name: String,
    val available: Boolean
)

/** A concise label for the list only when the availability is unambiguous. */
fun ChargePoint.singleAvailableSocketName(): String? =
    if (availableSockets == 1) sockets.singleOrNull { it.available }?.name?.takeIf { it.isNotBlank() } else null

interface ChargePointRepository {
    /** This will call only approved, read-only endpoints once OAuth is configured. */
    fun authorizedChargePoints(accessToken: String): List<ChargePoint>
}

class UnconfiguredChargePointRepository : ChargePointRepository {
    override fun authorizedChargePoints(accessToken: String): List<ChargePoint> = emptyList()
}
