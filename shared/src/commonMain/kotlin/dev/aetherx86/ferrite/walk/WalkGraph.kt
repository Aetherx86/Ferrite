package dev.aetherx86.ferrite.walk

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.bearingDegrees
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OverpassPoint(val lat: Double, val lon: Double)

@Serializable
private data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val nodes: List<Long> = emptyList(),
    val geometry: List<OverpassPoint?> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
private data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

/** Kinds of path, in the terms that matter on foot. */
enum class PathKind { MAIN_ROAD, ROAD, ALLEY, FOOTWAY, PATH, STEPS, CORRIDOR }

class WalkEdge(
    val to: Int,
    val meters: Double,
    val kind: PathKind,
    val name: String?,
    val lit: Boolean?,
    val tunnel: Boolean,
    val bridge: Boolean,
    val crossing: Boolean,
    /** A road with no pavement mapped along it, where walkers share the road. */
    val sidewalkKnown: Boolean,
    /** A step-free path, as far as anyone has recorded. */
    val wheelchairOk: Boolean?,
)

class WalkNode(val position: LatLng, val signal: Boolean, val crossing: Boolean, val elevator: Boolean) {
    val edges = ArrayList<WalkEdge>()
}

/** One exit from a station. */
class StationExit(val position: LatLng, val ref: String?, val name: String?)

/** A way, for drawing. */
class MapWay(val points: FloatArray, val kind: PathKind)

/** Every way someone can walk in an area, with enough detail to prefer one over another. */
class WalkGraph(val nodes: List<WalkNode>, val ways: List<MapWay>, val exits: List<StationExit>) {
    /** The closest point on the network that connects to the most of it. */
    fun nearestNode(point: LatLng): Int? {
        if (nodes.isEmpty()) return null
        val component = largestComponent()
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for (i in nodes.indices) {
            if (!component[i]) continue
            val d = distanceMeters(point, nodes[i].position)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best.takeIf { it >= 0 }
    }

    private var largest: BooleanArray? = null

    private fun largestComponent(): BooleanArray {
        largest?.let { return it }
        val label = IntArray(nodes.size) { -1 }
        var bestLabel = -1
        var bestSize = 0
        var current = 0
        val stack = ArrayList<Int>()
        for (start in nodes.indices) {
            if (label[start] != -1) continue
            var size = 0
            stack.add(start)
            label[start] = current
            while (stack.isNotEmpty()) {
                val n = stack.removeAt(stack.lastIndex)
                size++
                for (edge in nodes[n].edges) {
                    if (label[edge.to] == -1) {
                        label[edge.to] = current
                        stack.add(edge.to)
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = current
            }
            current++
        }
        val out = BooleanArray(nodes.size) { label[it] == bestLabel }
        largest = out
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val MIRRORS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        /** The widest area fetched, since a walk longer than this is better done another way. */
        const val MAX_SPAN_METERS = 9000.0

        private val recent = ArrayList<Pair<String, WalkGraph>>()
        private const val RECENT_LIMIT = 3

        /** Fetches the walkable network around two points. The last few answers are kept, since the walk is often reopened. */
        suspend fun fetch(from: LatLng, to: LatLng): WalkGraph {
            val key = listOf(from.lat, from.lon, to.lat, to.lon).joinToString(",") { ((it * 2000).toLong()).toString() }
            recent.firstOrNull { it.first == key }?.let { return it.second }
            val graph = parse(fetchText(queryFor(from, to)))
            recent.add(0, key to graph)
            while (recent.size > RECENT_LIMIT) recent.removeAt(recent.lastIndex)
            return graph
        }

        /** Just the exits around a station, which is a small question even a busy server answers. */
        suspend fun fetchExits(center: LatLng): List<StationExit> {
            val query = "[out:json][timeout:15];node[\"railway\"=\"subway_entrance\"](around:450,${center.lat},${center.lon});out body;"
            return parse(fetchText(query)).exits
        }

        /** These are shared public servers and any of them can be busy, so all are asked and the first answer wins. */
        private suspend fun fetchText(query: String): String = coroutineScope {
            val answers = Channel<Result<String>>(MIRRORS.size)
            val requests = MIRRORS.map { mirror ->
                launch {
                    answers.send(
                        runCatching {
                            val result = httpRequest(
                                url = mirror,
                                method = "POST",
                                body = "data=" + percentEncode(query),
                                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to "Ferrite/0.1"),
                                timeoutMs = 55_000,
                            )
                            if (result.status != 200 || !result.body.trimStart().startsWith("{")) throw RuntimeException("지도 서버 응답 ${result.status}")
                            result.body
                        },
                    )
                }
            }
            var failure: Throwable? = null
            repeat(MIRRORS.size) {
                val answer = answers.receive()
                answer.onSuccess { text ->
                    requests.forEach { it.cancel() }
                    return@coroutineScope text
                }
                answer.onFailure { if (it is CancellationException) throw it else failure = it }
            }
            throw failure ?: RuntimeException("지도 서버에 연결하지 못했습니다")
        }

        private fun queryFor(from: LatLng, to: LatLng): String {
            val span = distanceMeters(from, to)
            val pad = (span * 0.25).coerceIn(250.0, 900.0) / 111_000.0
            val south = minOf(from.lat, to.lat) - pad
            val north = maxOf(from.lat, to.lat) + pad
            val west = minOf(from.lon, to.lon) - pad * 1.25
            val east = maxOf(from.lon, to.lon) + pad * 1.25
            val box = "$south,$west,$north,$east"
            return """
                [out:json][timeout:40];
                (
                  way["highway"]["highway"!~"^(motorway|motorway_link|trunk_link|construction|proposed|raceway|bus_guideway|escape|platform|abandoned|razed)$"]["access"!~"^(private|no)$"]["foot"!~"^(no|private)$"]($box);
                );
                out body geom;
                (
                  node["highway"="crossing"]($box);
                  node["crossing"]($box);
                  node["highway"="traffic_signals"]($box);
                  node["highway"="elevator"]($box);
                  node["railway"="subway_entrance"]($box);
                );
                out body;
            """.trimIndent()
        }

        private fun percentEncode(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) append(c.toChar())
                else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }

        fun parse(text: String): WalkGraph {
            val response = json.decodeFromString(OverpassResponse.serializer(), text)
            val tagged = HashMap<Long, Map<String, String>>()
            val exits = ArrayList<StationExit>()
            for (e in response.elements) {
                if (e.type != "node" || e.lat == null || e.lon == null) continue
                tagged[e.id] = e.tags
                if (e.tags["railway"] == "subway_entrance") {
                    exits += StationExit(LatLng(e.lat, e.lon), e.tags["ref"], e.tags["name"])
                }
            }
            val index = HashMap<Long, Int>()
            val nodes = ArrayList<WalkNode>()
            val ways = ArrayList<MapWay>()
            fun nodeOf(id: Long, point: OverpassPoint): Int = index.getOrPut(id) {
                val tags = tagged[id].orEmpty()
                nodes += WalkNode(
                    LatLng(point.lat, point.lon),
                    signal = tags["highway"] == "traffic_signals" || tags["crossing"] == "traffic_signals",
                    crossing = tags["highway"] == "crossing" || "crossing" in tags,
                    elevator = tags["highway"] == "elevator",
                )
                nodes.lastIndex
            }
            for (way in response.elements) {
                if (way.type != "way" || way.geometry.size < 2 || way.nodes.size != way.geometry.size) continue
                val tags = way.tags
                val kind = kindOf(tags) ?: continue
                val points = FloatArray(way.geometry.size * 2)
                way.geometry.forEachIndexed { i, p ->
                    if (p != null) {
                        points[i * 2] = p.lon.toFloat()
                        points[i * 2 + 1] = p.lat.toFloat()
                    }
                }
                ways += MapWay(points, kind)
                val oneWayOnFoot = tags["oneway:foot"] == "yes"
                val isCrossing = tags["footway"] == "crossing" || tags["cycleway"] == "crossing"
                val sidewalk = tags["sidewalk"]?.let { it != "no" && it != "none" } ?: false
                val wheelchair = when (tags["wheelchair"]) {
                    "yes", "designated" -> true
                    "no" -> false
                    else -> null
                }
                val name = tags["name:ko"] ?: tags["name"]
                val lit = when (tags["lit"]) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                }
                for (i in 0 until way.nodes.size - 1) {
                    val pa = way.geometry[i] ?: continue
                    val pb = way.geometry[i + 1] ?: continue
                    val a = nodeOf(way.nodes[i], pa)
                    val b = nodeOf(way.nodes[i + 1], pb)
                    if (a == b) continue
                    val meters = distanceMeters(nodes[a].position, nodes[b].position)
                    fun edge(to: Int) = WalkEdge(to, meters, kind, name, lit, "tunnel" in tags, "bridge" in tags, isCrossing, sidewalk, wheelchair)
                    nodes[a].edges += edge(b)
                    if (!oneWayOnFoot) nodes[b].edges += edge(a)
                }
            }
            return WalkGraph(nodes, ways, exits)
        }

        private fun kindOf(tags: Map<String, String>): PathKind? = when (tags["highway"]) {
            "steps" -> PathKind.STEPS
            "corridor" -> PathKind.CORRIDOR
            "footway", "pedestrian" -> PathKind.FOOTWAY
            "path", "track", "cycleway", "bridleway" -> PathKind.PATH
            "service", "living_street" -> PathKind.ALLEY
            "residential", "unclassified", "road" -> PathKind.ROAD
            "primary", "secondary", "tertiary", "primary_link", "secondary_link", "tertiary_link" -> PathKind.MAIN_ROAD
            "trunk" -> if (tags["foot"] == "yes" || tags["sidewalk"] != null) PathKind.MAIN_ROAD else null
            else -> null
        }
    }
}

/** What a walker wants from a route. */
enum class WalkProfile(val label: String, val hint: String) {
    FASTEST("가장 빠른 길", "계단과 골목도 지나갑니다"),
    STEP_FREE("계단 없는 길", "유모차, 휠체어, 큰 짐"),
    BRIGHT("밝은 큰길", "밤길에 좋은 조명과 큰길 위주"),
}

enum class StepKind { START, STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, CROSSING, STAIRS, UNDERPASS, OVERPASS, ARRIVE }

data class WalkStep(val kind: StepKind, val text: String, val meters: Double, val position: LatLng)

class WalkRoute(
    val profile: WalkProfile,
    val points: List<LatLng>,
    val meters: Double,
    val seconds: Int,
    val stairs: Int,
    val crossings: Int,
    val steps: List<WalkStep>,
    /** How far short of the destination this route had to stop, because the rest has steps. */
    val shortOfGoalMeters: Int = 0,
)

/** Finds a walking route on the device, so changing what you prefer costs no network request. */
object WalkRouter {
    private const val WALK_SPEED = 1.2 // Metres a second, the figure the transit operators measure with.
    private const val STAIRS_SPEED = 0.55
    private const val SIGNAL_WAIT_SECONDS = 28.0
    private const val CROSSING_WAIT_SECONDS = 6.0
    private const val TURN_THRESHOLD = 32.0
    private const val SLIGHT_THRESHOLD = 18.0
    private const val MAX_SHORT_METERS = 260.0

    fun route(graph: WalkGraph, from: LatLng, to: LatLng, profile: WalkProfile): WalkRoute? {
        val start = graph.nearestNode(from) ?: return null
        val goal = graph.nearestNode(to) ?: return null
        val nodes = graph.nodes
        val goalPosition = nodes[goal].position
        val n = nodes.size
        val best = DoubleArray(n) { Double.MAX_VALUE }
        val parentNode = IntArray(n) { -1 }
        val parentEdge = arrayOfNulls<WalkEdge>(n)
        val heap = NodeHeap(n)
        best[start] = 0.0
        heap.push(start, distanceMeters(nodes[start].position, goalPosition) / WALK_SPEED)
        var reached = goal
        var closest = start
        var closestMeters = distanceMeters(nodes[start].position, goalPosition)
        while (true) {
            val current = heap.pop()
            if (current < 0) {
                // No way all the way there. A step-free route still gets as close as it can, and says how close.
                if (profile != WalkProfile.STEP_FREE || closestMeters > MAX_SHORT_METERS) return null
                reached = closest
                break
            }
            if (current == goal) break
            val away = distanceMeters(nodes[current].position, goalPosition)
            if (away < closestMeters) {
                closestMeters = away
                closest = current
            }
            for (edge in nodes[current].edges) {
                val cost = edgeCost(edge, nodes[edge.to], profile)
                if (cost.isInfinite()) continue
                val total = best[current] + cost
                if (total < best[edge.to]) {
                    best[edge.to] = total
                    parentNode[edge.to] = current
                    parentEdge[edge.to] = edge
                    heap.push(edge.to, total + distanceMeters(nodes[edge.to].position, goalPosition) / WALK_SPEED)
                }
            }
        }
        val sequence = ArrayList<Int>()
        var at = reached
        while (at != -1) {
            sequence.add(at)
            at = parentNode[at]
        }
        sequence.reverse()
        val shortBy = if (reached == goal) 0 else closestMeters.toInt()
        return assemble(graph, sequence, parentEdge, profile, shortBy)
    }

    /** Seconds to walk an edge, plus how much this profile dislikes it. */
    private fun edgeCost(edge: WalkEdge, target: WalkNode, profile: WalkProfile): Double {
        val speed = if (edge.kind == PathKind.STEPS) STAIRS_SPEED else WALK_SPEED
        var cost = edge.meters / speed
        var factor = 1.0
        when (profile) {
            WalkProfile.FASTEST -> {
                if (edge.kind == PathKind.MAIN_ROAD && !edge.sidewalkKnown) factor *= 1.12
            }
            WalkProfile.STEP_FREE -> {
                if (edge.kind == PathKind.STEPS || edge.wheelchairOk == false) return Double.POSITIVE_INFINITY
                if (edge.kind == PathKind.PATH) factor *= 1.5
            }
            WalkProfile.BRIGHT -> {
                factor *= when (edge.kind) {
                    PathKind.MAIN_ROAD -> 0.9
                    PathKind.ROAD -> 1.05
                    PathKind.FOOTWAY -> 1.2
                    PathKind.CORRIDOR -> 1.0
                    PathKind.ALLEY -> 1.7
                    PathKind.PATH -> 2.2
                    PathKind.STEPS -> 1.6
                }
                if (edge.lit == false) factor *= 1.6
                if (edge.lit == true) factor *= 0.9
                if (edge.tunnel) factor *= 1.4
            }
        }
        cost *= factor
        if (target.signal) cost += SIGNAL_WAIT_SECONDS else if (target.crossing || edge.crossing) cost += CROSSING_WAIT_SECONDS
        return cost
    }

    private fun assemble(graph: WalkGraph, sequence: List<Int>, parents: Array<WalkEdge?>, profile: WalkProfile, shortBy: Int): WalkRoute {
        val nodes = graph.nodes
        val points = sequence.map { nodes[it].position }
        var meters = 0.0
        var seconds = 0.0
        var stairs = 0
        var crossings = 0
        var previousKind: PathKind? = null
        var previousCrossing = false
        val steps = ArrayList<WalkStep>()
        var pending = 0.0
        var heading: Double? = null
        var roadName: String? = null
        var pendingKind = StepKind.START
        var pendingAt = points.first()
        var pendingName: String? = null

        fun flush(next: StepKind, at: LatLng, name: String?) {
            steps += WalkStep(pendingKind, describe(pendingKind, pending, pendingName), pending, pendingAt)
            pending = 0.0
            pendingKind = next
            pendingAt = at
            pendingName = name
        }

        for (i in 1 until sequence.size) {
            val edge = parents[sequence[i]]!!
            val node = nodes[sequence[i]]
            val a = nodes[sequence[i - 1]].position
            val b = node.position
            meters += edge.meters
            seconds += edge.meters / (if (edge.kind == PathKind.STEPS) STAIRS_SPEED else WALK_SPEED)
            if (node.signal) seconds += SIGNAL_WAIT_SECONDS
            val bearing = bearingDegrees(a, b)
            val isCrossingHere = edge.crossing || node.crossing
            val turnBy = heading?.let { normalize(bearing - it) } ?: 0.0
            val special = when {
                edge.kind == PathKind.STEPS && previousKind != PathKind.STEPS -> StepKind.STAIRS
                edge.tunnel && edge.meters > 0 && previousKind != null && pendingKind != StepKind.UNDERPASS -> StepKind.UNDERPASS
                edge.bridge && previousKind != null && pendingKind != StepKind.OVERPASS -> StepKind.OVERPASS
                isCrossingHere && !previousCrossing -> StepKind.CROSSING
                else -> null
            }
            if (edge.kind == PathKind.STEPS && previousKind != PathKind.STEPS) stairs++
            if (isCrossingHere && !previousCrossing) crossings++
            when {
                special != null && pending > 0 -> flush(special, a, edge.name)
                special != null -> {
                    pendingKind = special
                    pendingAt = a
                    pendingName = edge.name
                }
                heading != null && kotlin.math.abs(turnBy) > TURN_THRESHOLD && pending > 8 -> flush(if (turnBy < 0) StepKind.TURN_LEFT else StepKind.TURN_RIGHT, a, edge.name)
                heading != null && kotlin.math.abs(turnBy) > SLIGHT_THRESHOLD && edge.name != roadName && pending > 8 -> flush(if (turnBy < 0) StepKind.SLIGHT_LEFT else StepKind.SLIGHT_RIGHT, a, edge.name)
                pendingKind in setOf(StepKind.STAIRS, StepKind.CROSSING, StepKind.UNDERPASS, StepKind.OVERPASS) && edge.kind != PathKind.STEPS && !isCrossingHere && !edge.tunnel && !edge.bridge && pending > 0 -> flush(StepKind.STRAIGHT, a, edge.name)
            }
            pending += edge.meters
            if (pendingName == null) pendingName = edge.name
            heading = bearing
            roadName = edge.name
            previousKind = edge.kind
            previousCrossing = isCrossingHere
        }
        flush(StepKind.ARRIVE, points.last(), null)
        val last = if (shortBy > 0) "남은 ${shortBy}m는 계단 없는 길이 지도에 없습니다. 엘리베이터 위치를 확인해 주세요." else "도착"
        steps += WalkStep(StepKind.ARRIVE, last, 0.0, points.last())
        return WalkRoute(profile, points, meters, seconds.toInt(), stairs, crossings, steps.filter { it.meters >= 1.0 || it.kind == StepKind.ARRIVE }, shortBy)
    }

    private fun describe(kind: StepKind, meters: Double, name: String?): String {
        val distance = "${meters.toInt()}m"
        val along = name?.let { "$it 따라 " }.orEmpty()
        return when (kind) {
            StepKind.START -> "${along}출발해 $distance 걷기"
            StepKind.STRAIGHT -> "${along}$distance 직진"
            StepKind.TURN_LEFT -> "왼쪽으로 돌아 ${along}$distance"
            StepKind.TURN_RIGHT -> "오른쪽으로 돌아 ${along}$distance"
            StepKind.SLIGHT_LEFT -> "왼쪽 방향으로 ${along}$distance"
            StepKind.SLIGHT_RIGHT -> "오른쪽 방향으로 ${along}$distance"
            StepKind.CROSSING -> "횡단보도 건너기"
            StepKind.STAIRS -> "계단 $distance"
            StepKind.UNDERPASS -> "지하도로 $distance"
            StepKind.OVERPASS -> "육교나 다리로 $distance"
            StepKind.ARRIVE -> "도착"
        }
    }

    private fun normalize(degrees: Double): Double {
        var d = degrees % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** A small priority queue keyed by node. Stale entries are skipped when popped. */
    private class NodeHeap(size: Int) {
        private var nodes = IntArray(maxOf(16, size / 4))
        private var keys = DoubleArray(nodes.size)
        private var count = 0
        private val settled = BooleanArray(size)

        fun push(node: Int, key: Double) {
            if (count == nodes.size) {
                nodes = nodes.copyOf(count * 2)
                keys = keys.copyOf(count * 2)
            }
            var i = count++
            nodes[i] = node
            keys[i] = key
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= keys[i]) break
                swap(parent, i)
                i = parent
            }
        }

        fun pop(): Int {
            while (count > 0) {
                val top = nodes[0]
                count--
                if (count > 0) {
                    nodes[0] = nodes[count]
                    keys[0] = keys[count]
                    var i = 0
                    while (true) {
                        val l = i * 2 + 1
                        val r = l + 1
                        var smallest = i
                        if (l < count && keys[l] < keys[smallest]) smallest = l
                        if (r < count && keys[r] < keys[smallest]) smallest = r
                        if (smallest == i) break
                        swap(smallest, i)
                        i = smallest
                    }
                }
                if (!settled[top]) {
                    settled[top] = true
                    return top
                }
            }
            return -1
        }

        private fun swap(a: Int, b: Int) {
            val n = nodes[a]; nodes[a] = nodes[b]; nodes[b] = n
            val k = keys[a]; keys[a] = keys[b]; keys[b] = k
        }
    }
}
