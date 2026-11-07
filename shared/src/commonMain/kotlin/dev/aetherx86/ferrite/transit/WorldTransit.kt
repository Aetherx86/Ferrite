package dev.aetherx86.ferrite.transit

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

data class WorldLeg(
    val mode: String,
    val route: String?,
    val headsign: String?,
    val from: String,
    val to: String,
    val seconds: Int,
    val colorHex: String?,
    val live: Boolean,
    val stops: Int,
    val points: List<LatLng>,
) {
    val isWalk: Boolean get() = mode == "WALK"
    val label: String
        get() = when (mode) {
            "WALK" -> "걷기"
            "BUS", "COACH" -> "버스"
            "SUBWAY", "METRO" -> "지하철"
            "TRAM" -> "트램"
            "FERRY" -> "페리"
            "HIGHSPEED_RAIL" -> "고속철도"
            "LONG_DISTANCE", "NIGHT_RAIL" -> "장거리 열차"
            "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "RAIL", "SUBURBAN" -> "열차"
            else -> "대중교통"
        }
}

data class WorldTrip(val startMs: Long, val endMs: Long, val seconds: Int, val transfers: Int, val legs: List<WorldLeg>)

@Serializable
private data class PlaceDto(val name: String = "")

@Serializable
private data class GeometryDto(val points: String = "", val precision: Int = 6)

@Serializable
private data class StopDto(val name: String = "")

@Serializable
private data class LegDto(
    val mode: String = "",
    val routeShortName: String? = null,
    val headsign: String? = null,
    val duration: Int = 0,
    val routeColor: String? = null,
    val realTime: Boolean = false,
    val from: PlaceDto = PlaceDto(),
    val to: PlaceDto = PlaceDto(),
    val legGeometry: GeometryDto = GeometryDto(),
    val intermediateStops: List<StopDto> = emptyList(),
)

@Serializable
private data class ItineraryDto(
    val duration: Int = 0,
    val startTime: String = "",
    val endTime: String = "",
    val transfers: Int = 0,
    val legs: List<LegDto> = emptyList(),
)

@Serializable
private data class PlanDto(val itineraries: List<ItineraryDto> = emptyList())

/**
 * Buses, trains and subways almost anywhere, from an open service fed by the world's published timetables. It covers
 * what those cities publish, so coverage and live updates vary by city, and the screen says when a time is live.
 */
object WorldTransit {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun plan(from: LatLng, to: LatLng, atIso: String): List<WorldTrip> {
        val url = "https://api.transitous.org/api/v1/plan?fromPlace=${from.lat},${from.lon}&toPlace=${to.lat},${to.lon}&time=$atIso"
        val result = httpRequest(url, headers = mapOf("User-Agent" to "Ferrite/0.1"), timeoutMs = 25_000)
        if (result.status != 200) throw RuntimeException("서버 응답 ${result.status}")
        return parse(result.body)
    }

    fun parse(text: String): List<WorldTrip> =
        json.decodeFromString(PlanDto.serializer(), text).itineraries.map { it ->
            WorldTrip(
                startMs = Instant.parse(it.startTime).toEpochMilliseconds(),
                endMs = Instant.parse(it.endTime).toEpochMilliseconds(),
                seconds = it.duration,
                transfers = it.transfers,
                legs = it.legs.map { leg ->
                    WorldLeg(
                        mode = leg.mode,
                        route = leg.routeShortName,
                        headsign = leg.headsign,
                        from = leg.from.name,
                        to = leg.to.name,
                        seconds = leg.duration,
                        colorHex = leg.routeColor?.takeIf { c -> c.length == 6 },
                        live = leg.realTime,
                        stops = leg.intermediateStops.size + 1,
                        points = decode(leg.legGeometry.points, leg.legGeometry.precision),
                    )
                },
            )
        }

    fun decode(shape: String, precision: Int): List<LatLng> {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = shape[i++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < shape.length) {
            lat += next()
            lon += next()
            out += LatLng(lat / factor, lon / factor)
        }
        return out
    }
}
