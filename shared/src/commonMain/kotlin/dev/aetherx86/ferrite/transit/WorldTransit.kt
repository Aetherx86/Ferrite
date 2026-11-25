package dev.omakasekim.ferrite.transit

import dev.omakasekim.ferrite.core.LatLng
import dev.omakasekim.ferrite.core.Polyline
import dev.omakasekim.ferrite.platform.httpRequest
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

    /**
     * Itineraries from the service's answer. One with a time that cannot be read is left out rather than failing the
     * whole answer, and a leg whose shape is damaged keeps the part that could be read.
     */
    fun parse(text: String): List<WorldTrip> =
        json.decodeFromString(PlanDto.serializer(), text).itineraries.mapNotNull { it ->
            val startMs = runCatching { Instant.parse(it.startTime).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            val endMs = runCatching { Instant.parse(it.endTime).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            if (endMs < startMs) return@mapNotNull null
            WorldTrip(
                startMs = startMs,
                endMs = endMs,
                seconds = it.duration.coerceAtLeast(0),
                transfers = it.transfers.coerceAtLeast(0),
                legs = it.legs.map { leg ->
                    WorldLeg(
                        mode = leg.mode,
                        route = leg.routeShortName,
                        headsign = leg.headsign,
                        from = leg.from.name,
                        to = leg.to.name,
                        seconds = leg.duration.coerceAtLeast(0),
                        colorHex = leg.routeColor?.takeIf { c -> c.length == 6 && c.all { ch -> ch.isHexDigit() } },
                        live = leg.realTime,
                        stops = leg.intermediateStops.size + 1,
                        points = decode(leg.legGeometry.points, leg.legGeometry.precision),
                    )
                },
            )
        }

    fun decode(shape: String, precision: Int): List<LatLng> = Polyline.decode(shape, precision)

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
