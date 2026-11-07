package dev.aetherx86.ferrite.live

import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.data.TransitNetwork

/**
 * The stretch of line a platform board draws: a few stations behind this one, and those ahead.
 *
 * A direction is named by the next station, which is unambiguous even on a loop or past a junction.
 */
class BoardPath(
    val line: Line,
    val here: Station,
    val next: Station?,
    /** Stations a train passes before reaching here, nearest first. */
    val upstream: List<Station>,
    /** Run times in seconds: index 0 is the hop into here, index 1 the hop before that, and so on. */
    val upstreamSeconds: List<Int>,
    /** Stations after here, nearest first. */
    val downstream: List<Station>,
    val downstreamSeconds: List<Int>,
    /** The end of the line, going this way. */
    val terminus: Station,
    /** Seconds from the start of the line to here, going this way. */
    val secondsFromOrigin: Int,
    /** Lines to change to here. */
    val transfers: List<Line>,
) {
    val isLastStop: Boolean get() = next == null

    companion object {
        private const val STRIP_UPSTREAM = 3
        private const val STRIP_DOWNSTREAM = 2

        fun build(network: TransitNetwork, line: Line, here: Station, next: Station?): BoardPath {
            val down = walk(network, line.id, here.id, next?.id)
            // Going backwards: start from any neighbour that is not where the train goes next.
            val from = network.neighbours(line.id, here.id).filter { it.id != next?.id }
            val upstreamStart = from.maxByOrNull { walk(network, line.id, here.id, it.id).stations.size }
            val up = walk(network, line.id, here.id, upstreamStart?.id)
            return BoardPath(
                line = line,
                here = here,
                next = next,
                upstream = up.stations.take(STRIP_UPSTREAM),
                upstreamSeconds = up.seconds.take(STRIP_UPSTREAM),
                downstream = down.stations.take(STRIP_DOWNSTREAM),
                downstreamSeconds = down.seconds.take(STRIP_DOWNSTREAM),
                terminus = down.stations.lastOrNull() ?: here,
                secondsFromOrigin = up.seconds.sum(),
                transfers = here.lines.filter { it.id != line.id },
            )
        }

        private class Walk(val stations: List<Station>, val seconds: List<Int>)

        /** Follows the line from [start] through [first] without turning back. At a fork it takes the longer way. */
        private fun walk(network: TransitNetwork, lineId: String, start: Int, first: Int?): Walk {
            if (first == null) return Walk(emptyList(), emptyList())
            val stations = ArrayList<Station>()
            val seconds = ArrayList<Int>()
            val seen = hashSetOf(start)
            var previous = start
            var current: Int = first
            while (current !in seen) {
                seen += current
                val station = network.station(current) ?: break
                stations += station
                seconds += network.secondsBetween(lineId, previous, current)
                    ?: network.secondsBetween(lineId, current, previous)
                    ?: DEFAULT_HOP_SECONDS
                val onward = network.neighbours(lineId, current).filter { it.id !in seen }
                val chosen = when (onward.size) {
                    0 -> break
                    1 -> onward[0]
                    else -> onward.maxByOrNull { depth(network, lineId, it.id, seen + current) } ?: break
                }
                previous = current
                current = chosen.id
            }
            return Walk(stations, seconds)
        }

        private fun depth(network: TransitNetwork, lineId: String, from: Int, blocked: Set<Int>): Int {
            val seen = HashSet(blocked)
            var frontier = listOf(from)
            var depth = 0
            while (frontier.isNotEmpty()) {
                val nextFrontier = ArrayList<Int>()
                for (id in frontier) {
                    if (seen.add(id)) {
                        depth++
                        network.neighbours(lineId, id).mapTo(nextFrontier) { it.id }
                    }
                }
                frontier = nextFrontier
            }
            return depth
        }

        private const val DEFAULT_HOP_SECONDS = 120

        /** One path per direction a train can leave this station, on this line. */
        fun allFrom(network: TransitNetwork, line: Line, here: Station): List<BoardPath> {
            val neighbours = network.neighbours(line.id, here.id)
            if (neighbours.isEmpty()) return listOf(build(network, line, here, null))
            return neighbours.map { build(network, line, here, it) }
        }
    }
}
