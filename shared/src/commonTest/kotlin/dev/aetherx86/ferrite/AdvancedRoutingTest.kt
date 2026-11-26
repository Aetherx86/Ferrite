package dev.omakasekim.ferrite

import dev.omakasekim.ferrite.core.distanceMeters
import dev.omakasekim.ferrite.core.epochOfKst
import dev.omakasekim.ferrite.data.TransitNetwork
import dev.omakasekim.ferrite.route.RouteOptions
import dev.omakasekim.ferrite.route.RoutePlanner
import dev.omakasekim.ferrite.trip.TripStop
import dev.omakasekim.ferrite.trip.optimizeOrder
import dev.omakasekim.ferrite.trip.reorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ROUTING_NET = """
{"regions":[{"id":"capital","name":"수도권","w":10,"h":10}],
 "lines":[
  {"id":"S4","name":"4호선","short":"4","color":"#00A5DE","region":"capital","cars":10,"edges":[[1,2,120],[2,3,120],[3,4,120]]},
  {"id":"S6","name":"6호선","short":"6","color":"#CD7C2F","region":"capital","cars":8,"edges":[[2,5,90],[5,6,90],[6,4,90]]},
  {"id":"S2","name":"2호선","short":"2","color":"#00A84D","region":"capital","cars":10,"edges":[[1,7,200],[7,4,200]]}
 ],
 "stations":[
  {"id":1,"name":"역1","en":"S1","lat":37.50,"lon":127.00,"x":1,"y":1,"region":"capital"},
  {"id":2,"name":"역2","en":"S2","lat":37.51,"lon":127.01,"x":2,"y":1,"region":"capital"},
  {"id":3,"name":"역3","en":"S3","lat":37.52,"lon":127.02,"x":3,"y":1,"region":"capital"},
  {"id":4,"name":"역4","en":"S4","lat":37.53,"lon":127.03,"x":4,"y":1,"region":"capital"},
  {"id":5,"name":"역5","en":"S5","lat":37.51,"lon":127.04,"x":2,"y":2,"region":"capital"},
  {"id":6,"name":"역6","en":"S6","lat":37.52,"lon":127.04,"x":3,"y":2,"region":"capital"},
  {"id":7,"name":"역7","en":"S7","lat":37.51,"lon":126.98,"x":2,"y":0,"region":"capital"}
 ],
 "runs":{},
 "transfers":[
  {"s":2,"a":"S4","b":"S6","m":100,"sec":100},
  {"s":4,"a":"S4","b":"S6","m":100,"sec":100}
 ]}
"""

class AdvancedRoutingTest {
    private val net = TransitNetwork.parse(ROUTING_NET)
    private val planner = RoutePlanner(net)

    @Test
    fun avoidLinesExcludesDisruptedCorridors() {
        val from = net.station(1)!!
        val to = net.station(4)!!
        val normal = planner.journeys(from, to)
        assertEquals("S4", normal.first().trip.legs.single().line.id)

        val avoided = planner.journeys(from, to, RouteOptions(avoidLines = setOf("S4")))
        assertTrue(avoided.isNotEmpty())
        assertTrue(avoided.all { j -> j.trip.legs.none { it.line.id == "S4" } })
        assertEquals("S2", avoided.first().trip.legs.single().line.id)

        // Avoiding every connecting line returns empty.
        assertTrue(planner.journeys(from, to, RouteOptions(avoidLines = setOf("S4", "S2"))).isEmpty())
    }

    @Test
    fun planViaMergesSameLineAndInsertsTransferOnLineChange() {
        val s1 = net.station(1)!!
        val s2 = net.station(2)!!
        val s4 = net.station(4)!!
        val s5 = net.station(5)!!

        // Staying on S4 through station 2 merges into one contiguous leg (1 -> 2 -> 3 -> 4).
        val sameLine = planner.planVia(s1, listOf(s2), s4)
        assertNotNull(sameLine)
        assertEquals(1, sameLine.legs.size)
        assertEquals(listOf(1, 2, 3, 4), sameLine.legs.single().stations.map { it.id })
        assertEquals(360, sameLine.seconds)

        // Routing 1 -> 5 -> 4 visits S6 and transfers at station 2.
        val viaBranch = planner.planVia(s1, listOf(s5), s4)
        assertNotNull(viaBranch)
        assertEquals(listOf("S4", "S6"), viaBranch.legs.map { it.line.id })
        assertTrue(viaBranch.legs[1].stations.any { it.id == 5 })
        assertEquals(1, viaBranch.transferCount)

        // Unreachable when avoiding S6.
        assertNull(planner.planVia(s1, listOf(s5), s4, RouteOptions(avoidLines = setOf("S6"))))
    }

    @Test
    fun arriveByComputesLatestDepartureBeforeDeadline() {
        val from = net.station(1)!!
        val to = net.station(6)!!
        // Wednesday 08:30 KST rush hour vs Wednesday 13:30 KST midday.
        val rushTarget = epochOfKst(2026, 11, 25, 8, 30, 0)
        val middayTarget = epochOfKst(2026, 11, 25, 13, 30, 0)

        val rushPlans = planner.arriveBy(from, to, rushTarget)
        val middayPlans = planner.arriveBy(from, to, middayTarget)
        assertTrue(rushPlans.isNotEmpty() && middayPlans.isNotEmpty())
        for (plan in rushPlans) {
            assertTrue(plan.arriveAtMs <= rushTarget, "must arrive on or before deadline")
            assertEquals(plan.departAtMs + plan.journey.trip.seconds * 1000L, plan.arriveAtMs)
        }
        // Midday waits on Line 6 are longer than morning rush waits, so midday requires an earlier lead time.
        val rushLead = rushTarget - rushPlans.first().departAtMs
        val middayLead = middayTarget - middayPlans.first().departAtMs
        assertTrue(middayLead > rushLead, "expected midday lead ($middayLead) > rush lead ($rushLead)")
    }

    @Test
    fun optimizeOrderShortensZigZagItinerary() {
        val stops = listOf(
            TripStop("Start", 37.50, 127.00),
            TripStop("FarEast", 37.50, 127.10),
            TripStop("NearEast", 37.50, 127.02),
            TripStop("MidEast", 37.50, 127.06),
        )
        fun pathLen(list: List<TripStop>): Double =
            list.zipWithNext().sumOf { (a, b) -> distanceMeters(a.position, b.position) }

        val order = optimizeOrder(stops, fixFirst = true)
        assertEquals(0, order.first())
        val optimized = reorder(stops, order)
        assertEquals(listOf("Start", "NearEast", "MidEast", "FarEast"), optimized.map { it.name })
        assertTrue(pathLen(optimized) < pathLen(stops))
    }
}
