package dev.omakasekim.ferrite.trip

import dev.omakasekim.ferrite.core.LatLng
import dev.omakasekim.ferrite.core.distanceMeters
import dev.omakasekim.ferrite.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TripStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val stayMinutes: Int = 60,
    val memo: String = "",
    val cost: Int = 0,
) {
    val position: LatLng get() = LatLng(lat, lon)
}

@Serializable
data class TripDay(val stops: List<TripStop> = emptyList(), val startMinute: Int = 9 * 60)

@Serializable
data class TripPlan(val days: List<TripDay> = listOf(TripDay()), val walking: Boolean = false)

/** One stop in time, with the travel that gets you there. */
data class TimedStop(val stop: TripStop, val arriveMinute: Int, val leaveMinute: Int, val travelMinutes: Int)

/** Works out a day's timeline from the travel times between stops. */
fun timeline(day: TripDay, legSeconds: List<Int>): List<TimedStop> {
    var clock = day.startMinute
    return day.stops.mapIndexed { index, stop ->
        val travel = if (index == 0) 0 else ((legSeconds.getOrElse(index - 1) { 0 } + 30) / 60)
        clock += travel
        val arrive = clock
        clock += stop.stayMinutes
        TimedStop(stop, arrive, clock, travel)
    }
}

/** Puts stops in the order an optimizer returned, ignoring anything out of range. */
fun reorder(stops: List<TripStop>, order: List<Int>): List<TripStop> {
    val picked = order.mapNotNull { stops.getOrNull(it) }
    return if (picked.size == stops.size) picked else stops
}

/**
 * Offline visit-order optimizer using nearest-neighbor seeding followed by 2-opt local refinement
 * on great-circle distance. Keeps the first stop fixed when [fixFirst] is true.
 */
fun optimizeOrder(stops: List<TripStop>, fixFirst: Boolean = true): List<Int> {
    val n = stops.size
    if (n <= 2) return (0 until n).toList()
    val dist = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else distanceMeters(stops[i].position, stops[j].position) } }

    fun solveFrom(start: Int): Pair<IntArray, Double> {
        val used = BooleanArray(n)
        val tour = IntArray(n)
        tour[0] = start
        used[start] = true
        for (step in 1 until n) {
            val prev = tour[step - 1]
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for (cand in 0 until n) {
                if (!used[cand] && dist[prev][cand] < bestD) {
                    bestD = dist[prev][cand]
                    best = cand
                }
            }
            tour[step] = best
            used[best] = true
        }
        // 2-opt path improvement (open path, no return to start).
        var improved = true
        var rounds = 0
        val minI = if (fixFirst) 1 else 0
        while (improved && rounds++ < 64) {
            improved = false
            for (i in minI until n - 1) {
                for (k in i + 1 until n) {
                    val before = (if (i > 0) dist[tour[i - 1]][tour[i]] else 0.0) +
                        (if (k + 1 < n) dist[tour[k]][tour[k + 1]] else 0.0)
                    val after = (if (i > 0) dist[tour[i - 1]][tour[k]] else 0.0) +
                        (if (k + 1 < n) dist[tour[i]][tour[k + 1]] else 0.0)
                    if (after + 1e-6 < before) {
                        var l = i
                        var r = k
                        while (l < r) {
                            val tmp = tour[l]
                            tour[l] = tour[r]
                            tour[r] = tmp
                            l++
                            r--
                        }
                        improved = true
                    }
                }
            }
        }
        var total = 0.0
        for (i in 0 until n - 1) total += dist[tour[i]][tour[i + 1]]
        return tour to total
    }

    if (fixFirst) return solveFrom(0).first.toList()
    var bestTour: IntArray? = null
    var bestCost = Double.POSITIVE_INFINITY
    for (s in 0 until n) {
        val (tour, cost) = solveFrom(s)
        if (cost < bestCost) {
            bestCost = cost
            bestTour = tour
        }
    }
    return bestTour?.toList() ?: (0 until n).toList()
}

class TripStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): TripPlan = store.getString(KEY)?.let { runCatching { json.decodeFromString(TripPlan.serializer(), it) }.getOrNull() } ?: TripPlan()

    fun save(plan: TripPlan) = store.putString(KEY, json.encodeToString(TripPlan.serializer(), plan))

    private companion object {
        const val KEY = "trip"
    }
}
