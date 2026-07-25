package dev.lokalgrid.app.ui

import dev.lokalgrid.app.LiveState
import dev.lokalgrid.protocol.NodeFrame
import dev.lokalgrid.protocol.TrackRecord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The other people on this node — real now, from the node's `peer` frames
 * (they were synthetic through Phase 01). The node is authoritative about who
 * exists and where they last said they were; this type is only the render shape.
 *
 * Every peer carries its uncertainty and its age for the same reason your own
 * dot does (§6): a position from 18 minutes ago drawn as a crisp dot is a lie
 * about where someone is now.
 */
data class Peer(
    val id: Int,
    val name: String,
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Double,
    val ageSec: Long,
    val colorHex: String,
    val kind: PillKind,
    val ghost: Boolean = false,
)

// Map wireframe colours, assigned round-robin by client id.
private val PEER_COLORS = listOf(
    "#E39A4E" to PillKind.NEUTRAL,
    "#A99BDB" to PillKind.LORA,
    "#6FB2A6" to PillKind.OK,
    "#D2725F" to PillKind.WARN,
)

/** Everyone except you — your own dot is drawn from your live fix. */
fun peersOf(state: LiveState): List<Peer> {
    val ghosts = state.roster.filter { it.isGhost }.map { it.id }.toSet()
    return state.peers
        .filter { it.id != state.selfId }
        .sortedBy { it.id }
        .map { p ->
            val (color, kind) = PEER_COLORS[p.id.coerceAtLeast(0) % PEER_COLORS.size]
            Peer(
                id = p.id,
                name = p.name,
                latDeg = p.latE7 / 1e7,
                lonDeg = p.lonE7 / 1e7,
                // No HDOP from a peer means unknown, not perfect: draw a wide ring.
                accuracyM = if (p.hd > 0) accuracyMeters(p.hd) else 40.0,
                ageSec = p.ageS,
                colorHex = color,
                kind = kind,
                ghost = p.id in ghosts,
            )
        }
}

/** "260 m NE · 2 min" — distance, 8-point compass, and age, for the people rows. */
fun relative(self: TrackRecord, p: Peer): String = relative(self.latDeg, self.lonDeg, p)

/** The same, measured from your own phone fix rather than the node's — which is
 *  the question you actually meant to ask once your phone knows where it is. */
fun relative(self: Peer, p: Peer): String = relative(self.latDeg, self.lonDeg, p)

fun relative(fromLat: Double, fromLon: Double, p: Peer): String {
    val (meters, bearing) = distanceBearing(fromLat, fromLon, p.latDeg, p.lonDeg)
    val dist = if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000)
    val age = when {
        p.ageSec < 90 -> "now"
        p.ageSec < 3600 -> "${p.ageSec / 60} min"
        else -> "${p.ageSec / 3600} h"
    }
    return "$dist $bearing · $age"
}

/** Age as a severity, so a stale position never reads as a current one. */
fun Peer.staleness(): PillKind = when {
    ageSec < 90 -> PillKind.OK
    ageSec < 900 -> PillKind.NEUTRAL
    else -> PillKind.WARN
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
