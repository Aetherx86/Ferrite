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

data class TripTransfer(
    val station: Station,
    val fromLine: Line,
    val toLine: Line,
    /** The walk between platforms. */
    val seconds: Int,
    /** The expected wait for the next train once on its platform. */
    val waitSeconds: Int = TransferModel.AVERAGE_WAIT_SECONDS,
)

data class Trip(
    val legs: List<Leg>,
    val transfers: List<TripTransfer>,
    val seconds: Int,
    val meters: Double,
    /** Fare in won by card for an adult, where the fare rules are known. */
    val fare: Int?,
    /** The trip starts, or a change happens, at an hour when trains do not usually run. Only known with a start time. */
    val outsideServiceHours: Boolean = false,
) {
    val transferCount: Int get() = transfers.size
}

enum class TripPreference(val label: String, val transferWeightSeconds: Int) {
    FASTEST("최단 시간", 0),
    FEWEST_TRANSFERS("최소 환승", 900),
}

/** One of the trips worth showing: none of the others is both quicker and has fewer changes. */
data class Journey(
    val trip: Trip,
    /** The quickest of the set. */
    val fastest: Boolean,
    /** The one with the fewest changes in the set. */
    val fewestTransfers: Boolean,
) {
    val label: String
        get() = when {
            fastest -> "최단 시간"
            trip.transferCount == 0 -> "환승 없음"
            else -> "환승 ${trip.transferCount}회"
        }
}

/** Finds trips on the rail network. It runs on the device and needs no connection. */
class RoutePlanner(private val network: TransitNetwork) {
    private data class State(val station: Int, val line: String)

    /** How a search reached a state: by riding from [parent] in the same round, or by changing lines in the round before. */
    private class Label(val time: Int, val parent: State?, val ride: Boolean, val walk: Int = 0, val wait: Int = 0)

    /** One step of a found trip, in order. */
    private sealed class Step {
        class Ride(val line: String, val from: Int, val to: Int, val seconds: Int) : Step()
        class Change(val station: Int, val fromLine: String, val toLine: String, val walk: Int, val wait: Int, val atSeconds: Int) : Step()
    }

    /**
     * The single best trip for a preference, weighing each change by the preference. Every change costs the measured
     * walk and a flat average wait, as it always has.
     */
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
                    relax(State(state.station, other.id), cost + walk + TransferModel.AVERAGE_WAIT_SECONDS + preference.transferWeightSeconds, state, best, parent, queue)
                }
            }
        }
        val end = goal ?: return null
        val states = generateSequence(end) { parent[it] }.toList().reversed()
        val steps = ArrayList<Step>()
        var elapsed = 0
        for ((a, b) in states.zipWithNext()) {
            if (a.line == b.line) {
                val seconds = network.secondsBetween(a.line, a.station, b.station) ?: 0
                steps += Step.Ride(a.line, a.station, b.station, seconds)
                elapsed += seconds
            } else {
                val walk = network.transferSeconds(b.station, a.line, b.line)
                steps += Step.Change(b.station, a.line, b.line, walk, TransferModel.AVERAGE_WAIT_SECONDS, elapsed)
                elapsed += walk + TransferModel.AVERAGE_WAIT_SECONDS
            }
        }
        return assemble(steps, null)
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

    /**
     * Every trip that is not beaten on both time and changes, quickest first, so each one after the first trades a
     * few minutes for fewer changes.
     *
     * The search goes in rounds, the way timetable planners do: round k finds the quickest way to every platform with
     * exactly k changes, starting from where round k - 1 left off, and only keeps what beats every earlier round. A
     * round that cannot improve on the destination stops early. Each change costs the walk at [RouteOptions.pace]
     * and the wait for the next line, which depends on the hour when [RouteOptions.departAtMs] is given.
     */
    fun pareto(from: Station, to: Station, options: RouteOptions = RouteOptions()): List<Trip> {
        if (from.id == to.id || from.lines.isEmpty() || to.lines.isEmpty()) return emptyList()
        val rounds = ArrayList<HashMap<State, Label>>()
        val bestAny = HashMap<State, Int>()
        var bestTarget = Int.MAX_VALUE
        val found = ArrayList<Pair<Int, State>>()
        var seeds: Map<State, Label> = from.lines.associate { State(from.id, it.id) to Label(0, null, ride = false) }
        for (round in 0..options.maxTransfers.coerceIn(0, MAX_ROUNDS)) {
            if (seeds.isEmpty()) break
            val labels = HashMap<State, Label>()
            val settled = ArrayList<State>()
            val queue = BinaryHeap()
            for ((state, label) in seeds) {
                if (label.time < bestTarget && label.time < (bestAny[state] ?: Int.MAX_VALUE)) {
                    labels[state] = label
                    queue.push(label.time, state)
                }
            }
            var arrival: State? = null
            while (true) {
                val (time, state) = queue.pop() ?: break
                if (time > labels.getValue(state).time) continue
                if (state.station == to.id) {
                    // Anything still queued is no quicker, so it cannot lead anywhere worth going.
                    arrival = state
                    break
                }
                settled += state
                for (hop in network.hopsFrom(state.line, state.station)) {
                    val next = State(hop.to, state.line)
                    val t = time + hop.seconds
                    if (t >= bestTarget || t >= (bestAny[next] ?: Int.MAX_VALUE)) continue
                    if (t < (labels[next]?.time ?: Int.MAX_VALUE)) {
                        labels[next] = Label(t, state, ride = true)
                        queue.push(t, next)
                    }
                }
            }
            rounds += labels
            if (arrival != null) {
                bestTarget = labels.getValue(arrival).time
                found += round to arrival
            }
            for (state in settled) {
                val t = labels.getValue(state).time
                if (t < (bestAny[state] ?: Int.MAX_VALUE)) bestAny[state] = t
            }
            val nextSeeds = HashMap<State, Label>()
            for (state in settled) {
                val label = labels.getValue(state)
                // Changing twice in a row at one station is never better than changing once.
                if (!label.ride) continue
                val station = network.station(state.station) ?: continue
                for (other in station.lines) {
                    if (other.id == state.line) continue
                    val walk = TransferModel.walkSeconds(network.transferSeconds(state.station, state.line, other.id), options.pace)
                    val onPlatform = label.time + walk
                    val wait = TransferModel.waitSeconds(other.id, options.departAtMs?.plus(onPlatform * 1000L))
                    val t = onPlatform + wait
                    val next = State(state.station, other.id)
                    if (t < bestTarget && t < (nextSeeds[next]?.time ?: Int.MAX_VALUE)) {
                        nextSeeds[next] = Label(t, state, ride = false, walk = walk, wait = wait)
                    }
                }
            }
            seeds = nextSeeds
        }
        return found.map { (round, end) -> assemble(stepsTo(rounds, round, end), options.departAtMs) }.sortedBy { it.seconds }
    }

    /**
     * The trips worth offering: the [pareto] set without any that take far longer than the quickest, which no one
     * would choose to save a change. A trip is kept while it takes at most [MIN_EXTRA_SECONDS] or half as long again
     * as the quickest, whichever is more.
     */
    fun journeys(from: Station, to: Station, options: RouteOptions = RouteOptions()): List<Journey> {
        val front = pareto(from, to, options)
        if (front.isEmpty()) return emptyList()
        val quickest = front.first().seconds
        val limit = quickest + maxOf(MIN_EXTRA_SECONDS, quickest / 2)
        val kept = front.filter { it.seconds <= limit }
        val fewest = kept.minOf { it.transferCount }
        return kept.mapIndexed { index, trip -> Journey(trip, fastest = index == 0, fewestTransfers = trip.transferCount == fewest) }
    }

    private fun stepsTo(rounds: List<Map<State, Label>>, round: Int, end: State): List<Step> {
        val steps = ArrayList<Step>()
        var k = round
        var state = end
        while (true) {
            val label = rounds[k].getValue(state)
            val parent = label.parent ?: break
            if (label.ride) {
                val before = rounds[k].getValue(parent)
                steps += Step.Ride(state.line, parent.station, state.station, label.time - before.time)
            } else {
                k--
                val before = rounds[k].getValue(parent)
                steps += Step.Change(state.station, parent.line, state.line, label.walk, label.wait, before.time)
            }
            state = parent
        }
        steps.reverse()
        return steps
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

    private fun assemble(steps: List<Step>, departAtMs: Long?): Trip {
        val legs = ArrayList<Leg>()
        val transfers = ArrayList<TripTransfer>()
        var legLine: String? = null
        var legStations = ArrayList<Station>()
        var legSeconds = 0
        var outside = departAtMs != null && !TransferModel.inService(departAtMs)
        fun flush() {
            val line = legLine
            if (line != null && legStations.size > 1) legs += Leg(network.line(line)!!, legStations, legSeconds)
            legLine = null
            legStations = ArrayList()
            legSeconds = 0
        }
        for (step in steps) {
            when (step) {
                is Step.Ride -> {
                    if (legLine != step.line) {
                        flush()
                        legLine = step.line
                        legStations.add(network.station(step.from)!!)
                    }
                    legStations.add(network.station(step.to)!!)
                    legSeconds += step.seconds
                }
                is Step.Change -> {
                    flush()
                    transfers += TripTransfer(network.station(step.station)!!, network.line(step.fromLine)!!, network.line(step.toLine)!!, step.walk, step.wait)
                    if (departAtMs != null && !TransferModel.inService(departAtMs + (step.atSeconds + step.walk) * 1000L)) outside = true
                }
            }
        }
        flush()
        val rideSeconds = legs.sumOf { it.seconds }
        val changeSeconds = transfers.sumOf { it.seconds + it.waitSeconds }
        val meters = legs.sumOf { leg -> leg.stations.zipWithNext().sumOf { (a, b) -> distanceMeters(a.location, b.location) } } * TRACK_FACTOR
        return Trip(legs, transfers, rideSeconds + changeSeconds, meters, fareOf(legs, meters), outside)
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

    companion object {
        /** A slower trip is still offered while it takes at most this much longer than the quickest, in seconds. */
        const val MIN_EXTRA_SECONDS = 20 * 60

        /** A hard ceiling on changes, whatever a caller asks for, so a search always ends quickly. */
        const val MAX_ROUNDS = 8

        private const val BASE_FARE = 1550
        private const val STEP_FARE = 100

        /** Track is a little longer than a straight line between stations. */
        private const val TRACK_FACTOR = 1.08
    }
}
