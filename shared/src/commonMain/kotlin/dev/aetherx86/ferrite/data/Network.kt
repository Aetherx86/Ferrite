package dev.aetherx86.ferrite.data

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.core.searchScore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RegionDto(val id: String, val name: String, val w: Float, val h: Float)

@Serializable
data class LineDto(
    val id: String,
    val name: String,
    val short: String,
    val color: String,
    val region: String,
    val cars: Int,
    val live: Int? = null,
    val edges: List<List<Int>>,
    val oneWay: List<List<Int>> = emptyList(),
)

@Serializable
data class StationDto(
    val id: Int,
    val name: String,
    val en: String = "",
    val sub: String = "",
    val lat: Double,
    val lon: Double,
    val x: Float,
    val y: Float,
    val region: String,
)

@Serializable
data class RunDto(val lines: List<String>, val stations: List<Int>, val path: List<Float>)

@Serializable
data class TransferDto(val s: Int, val a: String, val b: String, val m: Int, val sec: Int)

@Serializable
data class NetworkDto(
    val regions: List<RegionDto>,
    val lines: List<LineDto>,
    val stations: List<StationDto>,
    val runs: Map<String, List<RunDto>>,
    val transfers: List<TransferDto>,
)

class Line(
    val id: String,
    val name: String,
    val short: String,
    val colorArgb: Long,
    val region: String,
    val cars: Int,
    val liveId: Int?,
)

class Station(
    val id: Int,
    val name: String,
    val nameEn: String,
    val subName: String,
    val location: LatLng,
    val mapX: Float,
    val mapY: Float,
    val region: String,
) {
    /** Lines through here. Filled while the network loads. */
    val lines: MutableList<Line> = mutableListOf()
}

/** One stretch of track between two stations on one line. */
class Hop(val from: Int, val to: Int, val seconds: Int)

class Run(val lines: List<Line>, val stationIds: List<Int>, val path: FloatArray)

class Region(val id: String, val name: String, val width: Float, val height: Float)

/** Every rail line, as one graph. Read-only once loaded, so screens share it freely. */
class TransitNetwork(dto: NetworkDto) {
    val regions: List<Region> = dto.regions.map { Region(it.id, it.name, it.w, it.h) }
    val lines: List<Line> = dto.lines.map {
        Line(it.id, it.name, it.short, parseColor(it.color), it.region, it.cars, it.live)
    }
    val stations: List<Station> = dto.stations.map {
        Station(it.id, it.name, it.en, it.sub, LatLng(it.lat, it.lon), it.x, it.y, it.region)
    }

    private val lineById = lines.associateBy { it.id }
    private val stationById = stations.associateBy { it.id }

    /** line id -> station id -> hops leaving that station. */
    private val hops: Map<String, Map<Int, List<Hop>>>

    private val transferSeconds: Map<Triple<Int, String, String>, Int>

    val runs: Map<String, List<Run>> = dto.runs.mapValues { (_, runs) ->
        runs.map { run ->
            Run(run.lines.mapNotNull { lineById[it] }, run.stations, run.path.toFloatArray())
        }
    }

    init {
        val built = HashMap<String, Map<Int, List<Hop>>>()
        for (lineDto in dto.lines) {
            val oneWay = lineDto.oneWay.map { it[0] to it[1] }.toSet()
            val outgoing = HashMap<Int, MutableList<Hop>>()
            for (edge in lineDto.edges) {
                val (a, b, seconds) = edge
                val forward = a to b
                val backward = b to a
                if (backward !in oneWay) outgoing.getOrPut(a) { mutableListOf() }.add(Hop(a, b, seconds))
                if (forward !in oneWay) outgoing.getOrPut(b) { mutableListOf() }.add(Hop(b, a, seconds))
                val line = lineById.getValue(lineDto.id)
                for (id in listOf(a, b)) {
                    val station = stationById.getValue(id)
                    if (line !in station.lines) station.lines.add(line)
                }
            }
            built[lineDto.id] = outgoing
        }
        hops = built
        val walks = HashMap<Triple<Int, String, String>, Int>()
        for (t in dto.transfers) {
            walks[Triple(t.s, t.a, t.b)] = t.sec
            walks[Triple(t.s, t.b, t.a)] = t.sec
        }
        transferSeconds = walks
    }

    fun line(id: String): Line? = lineById[id]
    fun station(id: Int): Station? = stationById[id]

    fun hopsFrom(lineId: String, stationId: Int): List<Hop> = hops[lineId]?.get(stationId).orEmpty()

    /** Stations a train on this line could call at right after this one. */
    fun neighbours(lineId: String, stationId: Int): List<Station> =
        hopsFrom(lineId, stationId).mapNotNull { station(it.to) }

    fun secondsBetween(lineId: String, from: Int, to: Int): Int? =
        hopsFrom(lineId, from).firstOrNull { it.to == to }?.seconds

    /** Time to change lines inside a station, as measured where there is a measurement. */
    fun transferSeconds(stationId: Int, fromLine: String, toLine: String): Int =
        transferSeconds[Triple(stationId, fromLine, toLine)] ?: DEFAULT_TRANSFER_SECONDS

    /** A station by exact name. Several cities share some names, and the capital area's comes first. */
    fun named(name: String): Station? =
        stations.filter { it.name == name }.minByOrNull { if (it.region == "capital") 0 else 1 }

    fun regionName(id: String): String = regions.firstOrNull { it.id == id }?.name.orEmpty()

    /** Stations by how well their name matches. */
    fun search(query: String, limit: Int = 30): List<Station> =
        stations.mapNotNull { station ->
            val direct = searchScore(station.name, query)
            val other = if (station.subName.isNotEmpty()) searchScore(station.subName, query)?.plus(40) else null
            val english = if (station.nameEn.isNotEmpty()) searchScore(station.nameEn, query)?.plus(20) else null
            listOfNotNull(direct, other, english).minOrNull()?.let { station to it }
        }.sortedWith(compareBy({ it.second }, { it.first.name })).take(limit).map { it.first }

    /** Stations closest to a point, nearest first. */
    fun nearest(point: LatLng, limit: Int = 5): List<Pair<Station, Double>> =
        stations.map { it to distanceMeters(point, it.location) }.sortedBy { it.second }.take(limit)

    companion object {
        const val DEFAULT_TRANSFER_SECONDS = 180
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): TransitNetwork = TransitNetwork(json.decodeFromString(NetworkDto.serializer(), text))
    }
}

/** "#RRGGBB" to a packed ARGB value. */
fun parseColor(hex: String): Long = 0xFF000000L or hex.removePrefix("#").toLong(16)
