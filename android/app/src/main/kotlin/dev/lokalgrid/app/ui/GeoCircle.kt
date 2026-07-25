package dev.lokalgrid.app.ui

import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A geographic circle as a GeoJSON polygon, so the accuracy ring scales with the
 * map like real ground distance instead of a fixed pixel blob. This is the §6
 * honesty rule made literal: the radius is the position's uncertainty, never a
 * crisp dot implying precision the GNSS did not deliver.
 */
fun geoCircle(latDeg: Double, lonDeg: Double, radiusMeters: Double, steps: Int = 64): Polygon {
    val dLat = radiusMeters / 111_320.0
    val dLon = radiusMeters / (111_320.0 * cos(latDeg * PI / 180.0))
    val ring = ArrayList<Point>(steps + 1)
    for (i in 0..steps) {
        val a = 2.0 * PI * i / steps
        ring.add(Point.fromLngLat(lonDeg + dLon * cos(a), latDeg + dLat * sin(a)))
    }
    return Polygon.fromLngLats(listOf(ring))
}

/** HDOP → a rough horizontal accuracy in metres (UERE ~5 m). Honest-ish, and
 *  enough to make the ellipse breathe as the mock varies HDOP. */
fun accuracyMeters(hdopTimes10: Int): Double = (hdopTimes10 / 10.0) * 5.0

/**
 * The same conversion backwards, for the one place that needs it: the phone
 * states its uncertainty in metres, and the wire carries HDOP×10 because that is
 * what a GNSS receiver produces (§4). Inverting the one convention keeps a single
 * definition of uncertainty in the project — a second unit on the wire would mean
 * two rings that mean different things and no way to tell which is which.
 *
 * Clamped to the field's range: 0 is not "perfect", it is the sentinel for
 * unknown, so a phone with no stated accuracy must not land there.
 */
fun hdopTimes10(accuracyM: Double): Int =
    if (accuracyM <= 0) 0 else (accuracyM / 5.0 * 10.0).toInt().coerceIn(1, 255)
