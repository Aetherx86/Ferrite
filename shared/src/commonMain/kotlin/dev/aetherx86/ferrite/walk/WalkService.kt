package dev.aetherx86.ferrite.walk

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Walking routes from an open routing service built on the same map data. It answers in about a second, in Korean,
 * and already weighs stairs, alleys, lighting and pavements. If it cannot be reached, the app falls back on routing
 * by itself, which is slower only because it has to download the streets first.
 */
object WalkService {
    private val json = Json { ignoreUnknownKeys = true }
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de/route"

    suspend fun routes(from: LatLng, to: LatLng): Map<WalkProfile, WalkRoute?> = coroutineScope {
        WalkProfile.entries.map { profile ->
            async { profile to runCatching { route(from, to, profile) }.getOrNull() }
        }.awaitAll().toMap()
    }

    private suspend fun route(from: LatLng, to: LatLng, profile: WalkProfile): WalkRoute {
        val request = buildJsonObject {
            putJsonArray("locations") {
                add(point(from))
                add(point(to))
            }
            put("costing", "pedestrian")
            putJsonObject("costing_options") {
                putJsonObject("pedestrian") {
                    put("walking_speed", 4.32)
                    when (profile) {
                        WalkProfile.FASTEST -> Unit
                        WalkProfile.STEP_FREE -> put("type", "wheelchair")
                        WalkProfile.BRIGHT -> {
                            put("use_lit", 1.0)
                            put("alley_factor", 3.0)
                            put("walkway_factor", 1.3)
                            put("sidewalk_factor", 0.85)
                            put("step_penalty", 60)
                        }
                    }
                }
            }
            put("language", "ko-KR")
            putJsonObject("directions_options") {
                put("units", "kilometers")
                put("language", "ko-KR")
            }
        }
        val result = httpRequest(
            url = "$ENDPOINT?json=" + percentEncode(request.toString()),
            headers = mapOf("User-Agent" to "Ferrite/0.1"),
            timeoutMs = 15_000,
        )
        if (result.status != 200) throw RuntimeException("경로 서버 응답 ${result.status}")
        return parse(result.body, profile)
    }

    private fun point(p: LatLng) = buildJsonObject {
        put("lat", p.lat)
        put("lon", p.lon)
    }

    fun parse(text: String, profile: WalkProfile): WalkRoute {
        val trip = json.parseToJsonElement(text).jsonObject["trip"]?.jsonObject ?: throw RuntimeException("경로 없음")
        val summary = trip.getValue("summary").jsonObject
        val leg = trip.getValue("legs").jsonArray[0].jsonObject
        val points = decodeShape(leg.getValue("shape").jsonPrimitive.content)
        var stairs = 0
        var crossings = 0
        val steps = leg.getValue("maneuvers").jsonArray.map { element ->
            val maneuver = element.jsonObject
            val type = maneuver.getValue("type").jsonPrimitive.int
            val text0 = maneuver["instruction"]?.jsonPrimitive?.content.orEmpty()
            val meters = (maneuver["length"]?.jsonPrimitive?.double ?: 0.0) * 1000
            val at = points.getOrElse(maneuver["begin_shape_index"]?.jsonPrimitive?.int ?: 0) { points.first() }
            val kind = kindOf(type, text0)
            if (kind == StepKind.STAIRS) stairs++
            if (kind == StepKind.CROSSING || "횡단보도" in text0) crossings++
            val label = when {
                kind == StepKind.ARRIVE -> "도착"
                meters >= 1 -> "$text0 · ${meters.toInt()}m"
                else -> text0
            }
            WalkStep(kind, label, meters, at)
        }
        return WalkRoute(
            profile = profile,
            points = points,
            meters = summary.getValue("length").jsonPrimitive.double * 1000,
            seconds = summary.getValue("time").jsonPrimitive.double.toInt(),
            stairs = stairs,
            crossings = crossings,
            steps = steps,
        )
    }

    private fun kindOf(type: Int, text: String): StepKind = when (type) {
        1, 2, 3 -> StepKind.START
        4, 5, 6 -> StepKind.ARRIVE
        9 -> StepKind.SLIGHT_RIGHT
        10, 11 -> StepKind.TURN_RIGHT
        15, 14 -> StepKind.TURN_LEFT
        16 -> StepKind.SLIGHT_LEFT
        40 -> StepKind.STAIRS
        else -> when {
            "계단" in text -> StepKind.STAIRS
            "횡단보도" in text -> StepKind.CROSSING
            "지하" in text -> StepKind.UNDERPASS
            "육교" in text || "다리" in text -> StepKind.OVERPASS
            else -> StepKind.STRAIGHT
        }
    }

    /** The service sends the line as an encoded string with six decimal places. */
    fun decodeShape(shape: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = shape[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < shape.length) {
            lat += next()
            lon += next()
            out += LatLng(lat / 1e6, lon / 1e6)
        }
        return out
    }

    private fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) append(c.toChar())
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
