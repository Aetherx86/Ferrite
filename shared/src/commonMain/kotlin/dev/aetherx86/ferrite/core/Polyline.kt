package dev.omakasekim.ferrite.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The encoded polyline format that routing services use for shapes: each coordinate is scaled by 10^precision,
 * stored as the difference from the one before, zigzag encoded, and written five bits a character from '?' up.
 *
 * Shapes come from servers on the network, so decoding never throws. It stops at the last complete point when the
 * text is cut short, at a character outside the format, at a number too long to be real, or at a coordinate that is
 * not on the Earth.
 */
object Polyline {
    /** Six is what the street routing service sends, five the common default. More than this is not a real shape. */
    const val MAX_PRECISION = 9

    /** A 64 bit value needs 13 groups of five bits, so anything longer is corrupt. */
    private const val MAX_SHIFT = 60

    fun decode(shape: String, precision: Int = 6): List<LatLng> {
        if (precision !in 0..MAX_PRECISION) return emptyList()
        val factor = scale(precision)
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lon = 0L
        // Returns null when the text ends inside a number or holds something that is not part of the format.
        fun next(): Long? {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= shape.length || shift > MAX_SHIFT) return null
                val b = shape[index++].code - 63
                if (b !in 0..63) return null
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < shape.length) {
            val dLat = next() ?: break
            val dLon = next() ?: break
            lat += dLat
            lon += dLon
            val point = LatLng(lat / factor, lon / factor)
            if (abs(point.lat) > 90.0 || abs(point.lon) > 180.0) break
            out += point
        }
        return out
    }

    fun encode(points: List<LatLng>, precision: Int = 6): String {
        require(precision in 0..MAX_PRECISION) { "precision $precision" }
        val factor = scale(precision)
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            write(out, lat - lastLat)
            write(out, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    private fun write(out: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20L or (v and 0x1F)) + 63).toInt().toChar())
            v = v ushr 5
        }
        out.append((v + 63).toInt().toChar())
    }

    private fun scale(precision: Int): Double {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        return factor
    }
}
