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

/** Something in the network data that could not be used as it was. Loading leaves it out and carries on. */
data class NetworkIssue(val where: String, val problem: String)

/**
 * Every rail line, as one graph. Read-only once loaded, so screens share it freely.
 *
 * Loading checks the data rather than trusting it: a hop between unknown stations, a station off the Earth, a
 * repeated id, a run time that cannot be right or a colour that is not one is left out or replaced, and noted in
 * [issues], so a damaged file gives a smaller network instead of a crash.
 */
class TransitNetwork(dto: NetworkDto) {
    private val problems = ArrayList<NetworkIssue>()

    /** What loading had to leave out or fix. Empty for the data that ships with the app. */
    val issues: List<NetworkIssue> get() = problems

    val regions: List<Region> = dto.regions
        .filter { region -> region.id.isNotBlank().also { if (!it) problems += NetworkIssue("region", "blank id") } }
        .distinctBy { it.id }
        .map { Region(it.id, it.name, it.w, it.h) }

    val lines: List<Line> = buildLines(dto.lines)

    val stations: List<Station> = buildStations(dto.stations)

    private val lineById = lines.associateBy { it.id }
    private val stationById = stations.associateBy { it.id }

    /** line id -> station id -> hops leaving that station. */
    private val hops: Map<String, Map<Int, List<Hop>>>

    private val transferSeconds: Map<Triple<Int, String, String>, Int>

    val runs: Map<String, List<Run>> = dto.runs.mapValues { (region, runs) ->
        runs.mapNotNull { run ->
            val known = run.stations.filter { it in stationById }
            if (known.size != run.stations.size) problems += NetworkIssue("run in $region", "${run.stations.size - known.size} unknown stations left out")
            if (run.path.any { !it.isFinite() } || run.path.size % 2 != 0) {
                problems += NetworkIssue("run in $region", "damaged path")
                null
            } else {
                Run(run.lines.mapNotNull { lineById[it] }, known, run.path.toFloatArray())
            }
        }
    }

    init {
        val built = HashMap<String, Map<Int, List<Hop>>>()
        for (lineDto in dto.lines) {
            val line = lineById[lineDto.id] ?: continue
            if (line.id in built) continue
            val oneWay = lineDto.oneWay.filter { it.size >= 2 }.map { it[0] to it[1] }.toSet()
            val outgoing = HashMap<Int, MutableList<Hop>>()
            for (edge in lineDto.edges) {
                val problem = when {
                    edge.size < 3 -> "a hop needs two stations and a time: $edge"
                    edge[0] == edge[1] -> "a hop from station ${edge[0]} to itself"
                    edge[0] !in stationById || edge[1] !in stationById -> "a hop to an unknown station: $edge"
                    edge[2] !in MIN_HOP_SECONDS..MAX_HOP_SECONDS -> "a run time of ${edge[2]} seconds"
                    else -> null
                }
                if (problem != null) {
                    problems += NetworkIssue("line ${line.id}", problem)
                    continue
                }
                val (a, b, seconds) = edge
                val forward = a to b
                val backward = b to a
                if (backward !in oneWay) outgoing.getOrPut(a) { mutableListOf() }.add(Hop(a, b, seconds))
                if (forward !in oneWay) outgoing.getOrPut(b) { mutableListOf() }.add(Hop(b, a, seconds))
                for (id in listOf(a, b)) {
                    val station = stationById.getValue(id)
                    if (line !in station.lines) station.lines.add(line)
                }
            }
            built[line.id] = outgoing
        }
        hops = built
        val walks = HashMap<Triple<Int, String, String>, Int>()
        // A change between two branches of one line is real data, measured like any other, so it is kept.
        for (t in dto.transfers) {
            val problem = when {
                t.s !in stationById -> "a transfer at unknown station ${t.s}"
                t.a !in lineById || t.b !in lineById -> "a transfer between unknown lines ${t.a} and ${t.b}"
                t.sec !in 0..MAX_TRANSFER_SECONDS -> "a transfer walk of ${t.sec} seconds"
                else -> null
            }
            if (problem != null) {
                problems += NetworkIssue("transfers", problem)
                continue
            }
            walks[Triple(t.s, t.a, t.b)] = t.sec
            walks[Triple(t.s, t.b, t.a)] = t.sec
        }
        transferSeconds = walks
    }

    private fun buildLines(dtos: List<LineDto>): List<Line> {
        val seen = HashSet<String>()
        return dtos.mapNotNull {
            when {
                it.id.isBlank() -> problems += NetworkIssue("lines", "a line with no id")
                !seen.add(it.id) -> problems += NetworkIssue("line ${it.id}", "listed twice; the first is kept")
                else -> {
                    val color = parseColorOrNull(it.color) ?: NEUTRAL_COLOR.also { _ ->
                        problems += NetworkIssue("line ${it.id}", "colour \"${it.color}\" is not #RRGGBB")
                    }
                    val cars = it.cars.coerceIn(1, MAX_CARS)
                    if (cars != it.cars) problems += NetworkIssue("line ${it.id}", "${it.cars} cars")
                    return@mapNotNull Line(it.id, it.name, it.short, color, it.region, cars, it.live)
                }
            }
            null
        }
    }

    private fun buildStations(dtos: List<StationDto>): List<Station> {
        val seen = HashSet<Int>()
        return dtos.mapNotNull {
            val problem = when {
                !seen.add(it.id) -> "listed twice; the first is kept"
                !it.lat.isFinite() || !it.lon.isFinite() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 -> "position ${it.lat}, ${it.lon}"
                !it.x.isFinite() || !it.y.isFinite() -> "map position ${it.x}, ${it.y}"
                else -> null
            }
            if (problem != null) {
                problems += NetworkIssue("station ${it.id}", problem)
                null
            } else {
                Station(it.id, it.name, it.en, it.sub, LatLng(it.lat, it.lon), it.x, it.y, it.region)
            }
        }
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

        /** Run times between neighbouring stations outside this range are taken as damage, in seconds. */
        const val MIN_HOP_SECONDS = 10
        const val MAX_HOP_SECONDS = 3 * 3600
        const val MAX_TRANSFER_SECONDS = 1800
        private const val MAX_CARS = 20

        /** Used for a line whose colour cannot be read, so it still draws. */
        const val NEUTRAL_COLOR = 0xFF8A8F98L
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): TransitNetwork = TransitNetwork(json.decodeFromString(NetworkDto.serializer(), text))
    }
}

/** "#RRGGBB" or "#RGB", with or without the hash, to a packed ARGB value. Anything else gives a neutral grey. */
fun parseColor(hex: String): Long = parseColorOrNull(hex) ?: TransitNetwork.NEUTRAL_COLOR

/** "#RRGGBB" or "#RGB", with or without the hash, to a packed ARGB value, or null if it is not a colour. */
fun parseColorOrNull(hex: String): Long? {
    val digits = hex.trim().removePrefix("#")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val full = when (digits.length) {
        6 -> digits
        3 -> buildString { digits.forEach { append(it).append(it) } }
        else -> return null
    }
    return 0xFF000000L or full.toLong(16)
}
