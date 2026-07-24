package dev.lokalgrid.app.ui

import dev.lokalgrid.protocol.TrackRecord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DUMMY peers — a Phase-02 preview of "everyone on one map". Real peers arrive
 * over the node's per-client cursors later; for now these are synthetic, placed
 * relative to your live fix so they sit wherever you are. Colours follow the
 * master-plan Map wireframe: you = lock, user a = sig, user b = lora. user b is
 * deliberately stale so the age/uncertainty rendering (§6) is visible.
 */
data class Peer(
    val name: String,
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Double,
    val ageSec: Long,
    val colorHex: String,
    val kind: PillKind,
)

fun dummyPeers(self: TrackRecord?): List<Peer> {
    if (self == null) return emptyList()
    val t = (self.epoch % 3600).toDouble()
    // small drift so they look alive, not pinned
    val ax = 0.00015 * sin(t / 18); val ay = 0.00015 * cos(t / 18)
    return listOf(
        Peer("user a", self.latDeg + 0.0008 + ax, self.lonDeg + 0.0009 + ay, 12.0, 130, "#E39A4E", PillKind.NEUTRAL),
        Peer("user b", self.latDeg - 0.0011, self.lonDeg - 0.0006, 28.0, 1080, "#A99BDB", PillKind.LORA),
    )
}

/** "260 m NE · 2 min" — distance, 8-point compass, and age, for the people rows. */
fun relative(self: TrackRecord, p: Peer): String {
    val (meters, bearing) = distanceBearing(self.latDeg, self.lonDeg, p.latDeg, p.lonDeg)
    val dist = if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000)
    val age = if (p.ageSec < 90) "now" else "${(p.ageSec / 60)} min"
    return "$dist $bearing · $age"
}

private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

private fun distanceBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, String> {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val meters = r * 2 * atan2(sqrt(a), sqrt(1 - a))
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
        sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    val brg = (Math.toDegrees(atan2(y, x)) + 360) % 360
    return meters to COMPASS[((brg + 22.5) / 45).toInt() % 8]
}
