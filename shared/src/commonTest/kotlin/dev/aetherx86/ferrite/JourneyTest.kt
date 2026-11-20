package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.core.epochOfKst
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.route.RouteOptions
import dev.aetherx86.ferrite.route.RoutePlanner
import dev.aetherx86.ferrite.route.TransferModel
import dev.aetherx86.ferrite.route.TransferPace
import dev.aetherx86.ferrite.route.TripPreference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun stationsJson(ids: IntRange) =
    ids.joinToString(",") { """{"id":$it,"name":"역$it","en":"S$it","lat":37.${10 + it},"lon":127.0,"x":$it,"y":1,"region":"capital"}""" }

/** A-B-C-D-E on line 4, crossed at C by line 6, with a measured 200 second change between them. */
private val CROSS = """
{"regions":[{"id":"capital","name":"수도권","w":10,"h":10}],
 "lines":[
  {"id":"S4","name":"4호선","short":"4","color":"#00A5DE","region":"capital","cars":10,"edges":[[1,2,120],[2,3,120],[3,4,120],[4,5,120]]},
  {"id":"S6","name":"6호선","short":"6","color":"#CD7C2F","region":"capital","cars":8,"edges":[[6,3,100],[3,7,100]]}
 ],
 "stations":[${stationsJson(1..7)}],
 "runs":{},
 "transfers":[{"s":3,"a":"S4","b":"S6","m":200,"sec":200}]}
"""

/**
 * Three ways from 1 to 6. Line LA goes straight there in 40 minutes. LB and LC change once at 5. LD, LE and LF
 * change twice, at 7 and 8, where the platforms are next to each other, and that is quickest.
 */
private val THREE_WAYS = """
{"regions":[{"id":"capital","name":"수도권","w":10,"h":10}],
 "lines":[
  {"id":"LA","name":"A","short":"A","color":"#111111","region":"capital","cars":6,"edges":[[1,2,600],[2,3,600],[3,4,600],[4,6,600]]},
  {"id":"LB","name":"B","short":"B","color":"#222222","region":"capital","cars":6,"edges":[[1,5,200]]},
  {"id":"LC","name":"C","short":"C","color":"#333333","region":"capital","cars":6,"edges":[[5,6,200]]},
  {"id":"LD","name":"D","short":"D","color":"#444444","region":"capital","cars":6,"edges":[[1,7,60]]},
  {"id":"LE","name":"E","short":"E","color":"#555555","region":"capital","cars":6,"edges":[[7,8,60]]},
  {"id":"LF","name":"F","short":"F","color":"#666666","region":"capital","cars":6,"edges":[[8,6,60]]}
 ],
 "stations":[${stationsJson(1..8)}],
 "runs":{},
 "transfers":[{"s":7,"a":"LD","b":"LE","m":10,"sec":10},{"s":8,"a":"LE","b":"LF","m":10,"sec":10}]}
"""

class JourneyTest {
    private val three = TransitNetwork.parse(THREE_WAYS)
    private val cross = TransitNetwork.parse(CROSS)

    @Test
    fun theParetoSetTradesTimeForChanges() {
        val trips = RoutePlanner(three).pareto(three.station(1)!!, three.station(6)!!)
        assertEquals(listOf(2, 1, 0), trips.map { it.transferCount })
        // Two changes: 3 rides of 60, two walks of 10 and two average waits.
        assertEquals(listOf(180 + 2 * (10 + 180), 400 + 180 + 180, 2400), trips.map { it.seconds })
        assertEquals(listOf("LD", "LE", "LF"), trips[0].legs.map { it.line.id })
        assertEquals(listOf("LB", "LC"), trips[1].legs.map { it.line.id })
        assertEquals(listOf("LA"), trips[2].legs.map { it.line.id })
        assertTrue(trips.zipWithNext().all { (a, b) -> a.seconds < b.seconds && a.transferCount > b.transferCount })
    }

    @Test
    fun aFarSlowerTripIsNotOffered() {
        val journeys = RoutePlanner(three).journeys(three.station(1)!!, three.station(6)!!)
        assertEquals(listOf(2, 1), journeys.map { it.trip.transferCount })
        assertEquals(listOf("최단 시간", "환승 1회"), journeys.map { it.label })
        assertEquals(listOf(true, false), journeys.map { it.fastest })
        assertEquals(listOf(false, true), journeys.map { it.fewestTransfers })
    }

    @Test
    fun theChangeLimitIsRespected() {
        val planner = RoutePlanner(three)
        val from = three.station(1)!!
        val to = three.station(6)!!
        assertEquals(listOf(1, 0), planner.pareto(from, to, RouteOptions(maxTransfers = 1)).map { it.transferCount })
        assertEquals(listOf(0), planner.pareto(from, to, RouteOptions(maxTransfers = 0)).map { it.transferCount })
        assertEquals(3, planner.pareto(from, to, RouteOptions(maxTransfers = 1000)).size)
    }

    @Test
    fun theSingleBestPlanIsOnTheParetoSet() {
        val planner = RoutePlanner(three)
        val from = three.station(1)!!
        val to = three.station(6)!!
        assertEquals(planner.pareto(from, to).first().seconds, planner.plan(from, to, TripPreference.FASTEST)!!.seconds)
        // Weighing each change at 15 minutes picks the one change trip, which is also on the set.
        val weighed = planner.plan(from, to, TripPreference.FEWEST_TRANSFERS)!!
        assertEquals(1, weighed.transferCount)
        assertTrue(planner.pareto(from, to).any { it.seconds == weighed.seconds && it.transferCount == 1 })
    }

    @Test
    fun noTripIsNoJourney() {
        val planner = RoutePlanner(cross)
        assertTrue(planner.pareto(cross.station(1)!!, cross.station(1)!!).isEmpty())
        assertTrue(planner.journeys(cross.station(1)!!, cross.station(1)!!).isEmpty())
    }

    @Test
    fun waitsFollowHowOftenTheNextLineRuns() {
        val tuesday8 = epochOfKst(2026, 11, 3, 8, 0, 0)
        val tuesdayNoon = epochOfKst(2026, 11, 3, 12, 0, 0)
        val saturdayNoon = epochOfKst(2026, 11, 7, 12, 0, 0)
        assertEquals(TransferModel.AVERAGE_WAIT_SECONDS, TransferModel.waitSeconds("S2", null))
        assertEquals(90, TransferModel.waitSeconds("S2", tuesday8))
        assertEquals(180, TransferModel.waitSeconds("S2", tuesdayNoon))
        assertEquals(180, TransferModel.waitSeconds("S2", saturdayNoon))
        assertEquals(1800, TransferModel.waitSeconds("GO", tuesday8))
        // A line with no known interval waits as an average line would.
        assertEquals(300, TransferModel.waitSeconds("??", tuesdayNoon))
    }

    @Test
    fun aTripTakesLongerWhenTheNextLineRunsLessOften() {
        val planner = RoutePlanner(cross)
        val from = cross.station(1)!!
        val to = cross.station(7)!!
        val flat = planner.pareto(from, to).single()
        assertEquals(240 + 200 + 180 + 100, flat.seconds)
        assertEquals(planner.plan(from, to, TripPreference.FASTEST)!!.seconds, flat.seconds)
        // Reaching line 6 at 08:07 on a Tuesday, it runs every 5 minutes. At noon, every 8.
        val rush = planner.pareto(from, to, RouteOptions(departAtMs = epochOfKst(2026, 11, 3, 8, 0, 0))).single()
        assertEquals(150, rush.transfers.single().waitSeconds)
        assertEquals(240 + 200 + 150 + 100, rush.seconds)
        val noon = planner.pareto(from, to, RouteOptions(departAtMs = epochOfKst(2026, 11, 3, 12, 0, 0))).single()
        assertEquals(240, noon.transfers.single().waitSeconds)
        assertEquals(240 + 200 + 240 + 100, noon.seconds)
        assertFalse(noon.outsideServiceHours)
    }

    @Test
    fun aRelaxedPaceAllowsLongerForTheWalk() {
        assertEquals(200, TransferModel.walkSeconds(200, TransferPace.NORMAL))
        assertEquals(360, TransferModel.walkSeconds(200, TransferPace.RELAXED))
        val planner = RoutePlanner(cross)
        val trip = planner.pareto(cross.station(1)!!, cross.station(7)!!, RouteOptions(pace = TransferPace.RELAXED)).single()
        assertEquals(360, trip.transfers.single().seconds)
        assertEquals(240 + 360 + 180 + 100, trip.seconds)
    }

    @Test
    fun aTripAtNightIsFlagged() {
        // The service day runs from 05:30 until 00:30 the next morning.
        assertTrue(TransferModel.inService(epochOfKst(2026, 11, 4, 0, 20, 0)))
        assertFalse(TransferModel.inService(epochOfKst(2026, 11, 4, 0, 40, 0)))
        assertFalse(TransferModel.inService(epochOfKst(2026, 11, 4, 2, 0, 0)))
        assertTrue(TransferModel.inService(epochOfKst(2026, 11, 4, 5, 30, 0)))
        val planner = RoutePlanner(cross)
        val late = planner.pareto(cross.station(1)!!, cross.station(7)!!, RouteOptions(departAtMs = epochOfKst(2026, 11, 4, 2, 0, 0))).single()
        assertTrue(late.outsideServiceHours)
        // Leaving at 00:28 is within the service day, but the change at 00:35 is not.
        val lastTrain = planner.pareto(cross.station(1)!!, cross.station(7)!!, RouteOptions(departAtMs = epochOfKst(2026, 11, 4, 0, 28, 0))).single()
        assertTrue(lastTrain.outsideServiceHours)
    }

    @Test
    fun legsAndChangesAddUpToTheTotal() {
        val planner = RoutePlanner(three)
        for (trip in planner.pareto(three.station(1)!!, three.station(6)!!, RouteOptions(departAtMs = epochOfKst(2026, 11, 3, 18, 30, 0)))) {
            assertEquals(trip.seconds, trip.legs.sumOf { it.seconds } + trip.transfers.sumOf { it.seconds + it.waitSeconds })
            assertEquals(trip.legs.size, trip.transfers.size + 1)
            trip.legs.zipWithNext().forEachIndexed { i, (a, b) ->
                assertEquals(a.to.id, b.from.id)
                assertEquals(trip.transfers[i].station.id, a.to.id)
                assertEquals(trip.transfers[i].fromLine.id, a.line.id)
                assertEquals(trip.transfers[i].toLine.id, b.line.id)
            }
        }
    }

    /**
     * The rounds against an exhaustive search over every simple path, on many small random networks. For each number
     * of changes the quickest time must match, and so must which of them are worth showing.
     */
    @Test
    fun roundsMatchAnExhaustiveSearch() {
        val random = Random(20261121)
        var compared = 0
        repeat(250) {
            val network = TransitNetwork.parse(randomNetwork(random))
            assertTrue(network.issues.isEmpty(), network.issues.toString())
            val planner = RoutePlanner(network)
            val ids = network.stations.filter { it.lines.isNotEmpty() }.map { it.id }
            repeat(4) {
                val from = network.station(ids.random(random))!!
                val to = network.station(ids.random(random))!!
                if (from.id == to.id) return@repeat
                val expected = exhaustiveFront(network, from.id, to.id, maxTransfers = 4)
                val actual = planner.pareto(from, to, RouteOptions(maxTransfers = 4)).map { it.transferCount to it.seconds }.sortedBy { it.first }
                assertEquals(expected, actual, "from ${from.id} to ${to.id}")
                compared++
            }
        }
        assertTrue(compared > 500, "$compared")
    }

    private fun randomNetwork(random: Random): String {
        val stationCount = 7 + random.nextInt(4)
        val lines = (0 until 2 + random.nextInt(3)).map { index ->
            val stops = (1..stationCount).shuffled(random).take(2 + random.nextInt(4))
            val edges = stops.zipWithNext().map { (a, b) -> "[$a,$b,${30 + random.nextInt(600)}]" }
            val oneWay = if (random.nextInt(4) == 0) "[[${stops[0]},${stops[1]}]]" else "[]"
            """{"id":"R$index","name":"R$index","short":"$index","color":"#10${index}0A0","region":"capital","cars":4,"edges":[${edges.joinToString(",")}],"oneWay":$oneWay}"""
        }
        val lineCount = lines.size
        val transfers = (1..stationCount).flatMap { s ->
            (0 until lineCount).flatMap { a -> (a + 1 until lineCount).map { b -> Triple(s, a, b) } }
        }.filter { random.nextInt(3) == 0 }
        val kept = transfers.joinToString(",") { (s, a, b) -> """{"s":$s,"a":"R$a","b":"R$b","m":1,"sec":${random.nextInt(400)}}""" }
        return """{"regions":[{"id":"capital","name":"r","w":1,"h":1}],"lines":[${lines.joinToString(",")}],"stations":[${stationsJson(1..stationCount)}],"runs":{},"transfers":[$kept]}"""
    }

    /** Every simple path through (station, line) states, keeping the quickest arrival for each number of changes. */
    private fun exhaustiveFront(network: TransitNetwork, from: Int, to: Int, maxTransfers: Int): List<Pair<Int, Int>> {
        val best = IntArray(maxTransfers + 1) { Int.MAX_VALUE }
        val visited = HashSet<Pair<Int, String>>()
        fun visit(station: Int, line: String, time: Int, changes: Int, rode: Boolean) {
            if (station == to) {
                if (time < best[changes]) best[changes] = time
                return
            }
            for (hop in network.hopsFrom(line, station)) {
                val next = hop.to to line
                if (visited.add(next)) {
                    visit(hop.to, line, time + hop.seconds, changes, true)
                    visited.remove(next)
                }
            }
            if (rode && changes < maxTransfers) {
                for (other in network.station(station)!!.lines) {
                    val next = station to other.id
                    if (other.id != line && visited.add(next)) {
                        val cost = network.transferSeconds(station, line, other.id) + TransferModel.AVERAGE_WAIT_SECONDS
                        visit(station, other.id, time + cost, changes + 1, false)
                        visited.remove(next)
                    }
                }
            }
        }
        for (line in network.station(from)!!.lines) {
            visited.add(from to line.id)
            visit(from, line.id, 0, 0, false)
            visited.remove(from to line.id)
        }
        val front = ArrayList<Pair<Int, Int>>()
        var bestSoFar = Int.MAX_VALUE
        for (k in 0..maxTransfers) {
            if (best[k] < bestSoFar) {
                bestSoFar = best[k]
                front += k to best[k]
            }
        }
        return front
    }
}
