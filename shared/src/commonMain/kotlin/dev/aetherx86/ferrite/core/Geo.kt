package dev.aetherx86.ferrite.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the ground. */
data class LatLng(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_008.8

/** Whether a point is in South Korea, roughly. Things like fares and rail data only apply there. */
fun LatLng.isInKorea(): Boolean = lat in 33.0..38.7 && lon in 124.5..131.0

/** Great circle distance in metres. */
fun distanceMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.lat - a.lat) * PI / 180
    val dLon = (b.lon - a.lon) * PI / 180
    val lat1 = a.lat * PI / 180
    val lat2 = b.lat * PI / 180
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

/** Compass bearing from [a] to [b] in degrees, clockwise from north, in the range 0 until 360. */
fun bearingDegrees(a: LatLng, b: LatLng): Double {
    val lat1 = a.lat * PI / 180
    val lat2 = b.lat * PI / 180
    val dLon = (b.lon - a.lon) * PI / 180
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x) * 180 / PI + 360) % 360
}

/** Flat projection good enough for a map of one country, in metres from [origin]. */
class LocalProjection(val origin: LatLng) {
    private val metersPerLon = 111_320.0 * cos(origin.lat * PI / 180)
    private val metersPerLat = 110_950.0

    fun x(lon: Double): Double = (lon - origin.lon) * metersPerLon
    fun y(lat: Double): Double = (origin.lat - lat) * metersPerLat
    fun lon(x: Double): Double = x / metersPerLon + origin.lon
    fun lat(y: Double): Double = origin.lat - y / metersPerLat
}

/** Distance from a point to a segment, in the same units as the arguments. */
fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
