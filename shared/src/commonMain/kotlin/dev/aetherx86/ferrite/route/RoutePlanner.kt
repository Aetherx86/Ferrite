package dev.aetherx86.ferrite.route

import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.data.TransitNetwork

/** A ride on one line. */
data class Leg(val line: Line, val stations: List<Station>, val seconds: Int) {
    val from: Station get() = stations.first()
    val to: Station get() = stations.last()
    val stops: Int get() = stations.size - 1
}

data class TripTransfer(val station: Station, val fromLine: Line, val toLine: Line, val seconds: Int)

data class Trip(
    val legs: List<Leg>,
    val transfers: List<TripTransfer>,
    val seconds: Int,
    val meters: Double,
    /** Fare in won by card for an adult, where the fare rules are known. */
    val fare: Int?,
) {
    val transferCount: Int get() = transfers.size
}

enum class TripPreference(val label: String, val transferWeightSeconds: Int) {
    FASTEST("최단 시간", 0),
    FEWEST_TRANSFERS("최소 환승", 900),
}

/** Finds trips on the rail network. It runs on the device and needs no connection. */
class RoutePlanner(private val network: TransitNetwork) {
    private data class State(val station: Int, val line: String)

    fun plan(from: Station, to: Station, preference: TripPreference): Trip? {
        if (from.id == to.id || from.lines.isEmpty() || to.lines.isEmpty()) return null
        val best = HashMap<State, Int>()
        val parent = HashMap<State, State>()
        val queue = BinaryHeap()
        for (line in from.lines) {
            val state = State(from.id, line.id)
            best[state] = 0
            queue.push(0, state)
        }
        var goal: State? = null
        while (true) {
            val (cost, state) = queue.pop() ?: break
            if (cost > (best[state] ?: Int.MAX_VALUE)) continue
            if (state.station == to.id) {
                goal = state
                break
            }
            for (hop in network.hopsFrom(state.line, state.station)) {
                relax(State(hop.to, state.line), cost + hop.seconds, state, best, parent, queue)
            }
            network.station(state.station)?.lines?.forEach { other ->
                if (other.id != state.line) {
                    val walk = network.transferSeconds(state.station, state.line, other.id)
                    relax(State(state.station, other.id), cost + walk + WAIT_SECONDS + preference.transferWeightSeconds, state, best, parent, queue)
                }
            }
        }
        val end = goal ?: return null
        val states = generateSequence(end) { parent[it] }.toList().reversed()
        return assemble(states)
    }

    /** The different trips worth showing. Identical answers appear once. */
    fun options(from: Station, to: Station): List<Pair<TripPreference, Trip>> {
        val out = ArrayList<Pair<TripPreference, Trip>>()
        for (preference in TripPreference.entries) {
            val trip = plan(from, to, preference) ?: continue
            if (out.none { it.second.legs.map { l -> l.line.id to l.stations.map { s -> s.id } } == trip.legs.map { l -> l.line.id to l.stations.map { s -> s.id } } }) {
                out += preference to trip
            }
        }
        return out
    }

    private fun relax(
        next: State,
        cost: Int,
        from: State,
        best: HashMap<State, Int>,
        parent: HashMap<State, State>,
        queue: BinaryHeap,
    ) {
        if (cost < (best[next] ?: Int.MAX_VALUE)) {
            best[next] = cost
            parent[next] = from
            queue.push(cost, next)
        }
    }

    private fun assemble(states: List<State>): Trip {
        val legs = ArrayList<Leg>()
        val transfers = ArrayList<TripTransfer>()
        var legStations = ArrayList<Station>()
        var legSeconds = 0
        var currentLine = states.first().line
        fun flush() {
            if (legStations.size > 1) {
                legs += Leg(network.line(currentLine)!!, legStations, legSeconds)
            }
        }
        var previous: State? = null
        for (state in states) {
            val station = network.station(state.station)!!
            val before = previous
            if (before != null && before.line != state.line) {
                flush()
                val seconds = network.transferSeconds(state.station, before.line, state.line)
                if (legStations.size > 1) {
                    transfers += TripTransfer(station, network.line(before.line)!!, network.line(state.line)!!, seconds)
                }
                currentLine = state.line
                legStations = arrayListOf(station)
                legSeconds = 0
            } else {
                if (before != null) legSeconds += network.secondsBetween(state.line, before.station, state.station) ?: 0
                legStations.add(station)
            }
            previous = state
        }
        flush()
        val rideSeconds = legs.sumOf { it.seconds }
        val changeSeconds = transfers.sumOf { it.seconds + WAIT_SECONDS }
        val meters = legs.sumOf { leg -> leg.stations.zipWithNext().sumOf { (a, b) -> distanceMeters(a.location, b.location) } } * TRACK_FACTOR
        return Trip(legs, transfers, rideSeconds + changeSeconds, meters, fareOf(legs, meters))
    }

    /**
     * The capital area's distance fare: a base charge for the first 10 km, then a step every 5 km up to 50 km, and
     * every 8 km beyond that. Other cities use other rules, which are not modelled, so they get no figure.
     */
    private fun fareOf(legs: List<Leg>, meters: Double): Int? {
        if (legs.any { it.line.region != "capital" }) return null
        val km = meters / 1000
        var fare = BASE_FARE
        if (km > 10) fare += STEP_FARE * ceilDiv(minOf(km, 50.0) - 10, 5.0)
        if (km > 50) fare += STEP_FARE * ceilDiv(km - 50, 8.0)
        return fare
    }

    private fun ceilDiv(value: Double, step: Double): Int = kotlin.math.ceil(value / step).toInt()

    private class BinaryHeap {
        private val items = ArrayList<Pair<Int, State>>()

        fun push(cost: Int, state: State) {
            items.add(cost to state)
            var i = items.lastIndex
            while (i > 0) {
                val parent = (i - 1) / 2
                if (items[parent].first <= items[i].first) break
                val t = items[parent]; items[parent] = items[i]; items[i] = t
                i = parent
            }
        }

        fun pop(): Pair<Int, State>? {
            if (items.isEmpty()) return null
            val top = items[0]
            val last = items.removeAt(items.lastIndex)
            if (items.isNotEmpty()) {
                items[0] = last
                var i = 0
                while (true) {
                    val l = i * 2 + 1
                    val r = l + 1
                    var smallest = i
                    if (l < items.size && items[l].first < items[smallest].first) smallest = l
                    if (r < items.size && items[r].first < items[smallest].first) smallest = r
                    if (smallest == i) break
                    val t = items[smallest]; items[smallest] = items[i]; items[i] = t
                    i = smallest
                }
            }
            return top
        }
    }

    private companion object {
        /** Average wait for the train on the second line. */
        const val WAIT_SECONDS = 180
        const val BASE_FARE = 1550
        const val STEP_FARE = 100

        /** Track is a little longer than a straight line between stations. */
        const val TRACK_FACTOR = 1.08
    }
}
