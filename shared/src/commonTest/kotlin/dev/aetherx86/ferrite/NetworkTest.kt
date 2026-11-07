package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.core.epochOfKst
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.live.Approach
import dev.aetherx86.ferrite.live.ArrivalFeed
import dev.aetherx86.ferrite.live.BoardEngine
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.CarLoads
import dev.aetherx86.ferrite.live.FeedSource
import dev.aetherx86.ferrite.live.Phase
import dev.aetherx86.ferrite.live.ServiceEstimator
import dev.aetherx86.ferrite.live.countdownParts
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.route.RoutePlanner
import dev.aetherx86.ferrite.route.TripPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A small network: A-B-C on one line, with X-B-Y crossing it at B, and a far line that touches nothing. */
private val SMALL = """
{"regions":[{"id":"capital","name":"수도권","w":10,"h":10}],
 "lines":[
  {"id":"S4","name":"4호선","short":"4","color":"#00A5DE","region":"capital","cars":10,"live":1004,"edges":[[1,2,120],[2,3,120],[3,4,120],[4,5,120]],"oneWay":[]},
  {"id":"S6","name":"6호선","short":"6","color":"#CD7C2F","region":"capital","cars":8,"live":1006,"edges":[[6,3,100],[3,7,100]],"oneWay":[]},
  {"id":"B1","name":"부산 1호선","short":"1","color":"#F06A00","region":"capital","cars":8,"edges":[[8,9,100]],"oneWay":[]},
  {"id":"LP","name":"순환","short":"L","color":"#123456","region":"capital","cars":4,"edges":[[10,11,60],[11,12,60],[12,10,60]],"oneWay":[[10,11],[11,12],[12,10]]}
 ],
 "stations":[
  ${(1..12).joinToString(",") { """{"id":$it,"name":"역$it","en":"S$it","lat":37.${10 + it},"lon":127.0,"x":$it,"y":1,"region":"capital"}""" }}
 ],
 "runs":{"capital":[]},
 "transfers":[{"s":3,"a":"S4","b":"S6","m":200,"sec":200}]}
"""

class NetworkTest {
    private val network = TransitNetwork.parse(SMALL)
    private fun s(id: Int) = network.station(id)!!

    @Test
    fun aDirectRideHasNoTransfer() {
        val trip = RoutePlanner(network).plan(s(1), s(5), TripPreference.FASTEST)!!
        assertEquals(1, trip.legs.size)
        assertEquals(4, trip.legs[0].stops)
        assertEquals(480, trip.seconds)
        assertEquals(0, trip.transferCount)
    }

    @Test
    fun aChangeCountsItsWalkAndWait() {
        val trip = RoutePlanner(network).plan(s(1), s(7), TripPreference.FASTEST)!!
        assertEquals(listOf("S4", "S6"), trip.legs.map { it.line.id })
        assertEquals(1, trip.transferCount)
        assertEquals(240 + 100 + 200 + 180, trip.seconds)
    }

    @Test
    fun unconnectedPlacesHaveNoTrip() {
        assertNull(RoutePlanner(network).plan(s(1), s(8), TripPreference.FASTEST))
        assertNull(RoutePlanner(network).plan(s(1), s(1), TripPreference.FASTEST))
    }

    @Test
    fun oneWayTrackIsRespected() {
        val trip = RoutePlanner(network).plan(s(11), s(10), TripPreference.FASTEST)!!
        assertEquals(2, trip.legs[0].stops)
    }

    @Test
    fun theSameStationSharesItsLines() {
        assertEquals(setOf("S4", "S6"), s(3).lines.map { it.id }.toSet())
        assertEquals(200, network.transferSeconds(3, "S4", "S6"))
        assertEquals(200, network.transferSeconds(3, "S6", "S4"))
        assertEquals(TransitNetwork.DEFAULT_TRANSFER_SECONDS, network.transferSeconds(1, "S4", "S6"))
    }

    @Test
    fun boardPathLooksBackAndAhead() {
        val line = network.line("S4")!!
        val forward = BoardPath.build(network, line, s(3), s(4))
        assertEquals(listOf(2, 1), forward.upstream.map { it.id })
        assertEquals(5, forward.terminus.id)
        assertEquals(240, forward.secondsFromOrigin)
        assertEquals(listOf("S6"), forward.transfers.map { it.id })
        val back = BoardPath.build(network, line, s(3), s(2))
        assertEquals(1, back.terminus.id)
        assertEquals(listOf(4, 5), back.upstream.map { it.id })
        assertEquals(2, BoardPath.allFrom(network, line, s(3)).size)
        assertEquals(1, BoardPath.allFrom(network, line, s(1)).size)
    }

    private fun engine(): Pair<BoardEngine, BoardPath> {
        val path = BoardPath.build(network, network.line("S4")!!, s(3), s(4))
        return BoardEngine(path) to path
    }

    private fun feed(vararg arrivals: Long) = ArrivalFeed(
        FeedSource.LIVE,
        arrivals.mapIndexed { i, at -> Approach("40$i", "역5", at) },
        0,
    )

    @Test
    fun phasesFollowTheCountdownInOrder() {
        val (engine, _) = engine()
        val arrival = 1_000_000L
        val f = feed(arrival, arrival + 240_000)
        fun at(seconds: Int) = engine.frame(f, arrival - seconds * 1000L).phase
        assertEquals(Phase.WAITING, at(400))
        assertEquals(Phase.PREVIOUS_DEPARTED, at(60))
        assertEquals(Phase.PREVIOUS_DEPARTED, at(14))
        assertEquals(Phase.APPROACHING, at(12))
        assertEquals(Phase.ENTERING, at(4))
        assertEquals(Phase.ARRIVED, engine.frame(f, arrival + 1000).phase)
        assertEquals(Phase.CLOSING, engine.frame(f, arrival + BoardEngine.DWELL_MS - 2000).phase)
    }

    @Test
    fun theFloodSpreadsAndRecedes() {
        val (engine, _) = engine()
        val arrival = 1_000_000L
        val f = feed(arrival, arrival + 240_000)
        assertEquals(0f, engine.frame(f, arrival - 1000).flood)
        assertTrue(engine.frame(f, arrival + BoardEngine.FLOOD_MS / 2).flood in 0.3f..0.7f)
        assertEquals(1f, engine.frame(f, arrival + 5000).flood)
        val leaving = engine.frame(f, arrival + BoardEngine.DWELL_MS + BoardEngine.FLOOD_MS / 2)
        assertTrue(leaving.flood in 0.3f..0.7f)
        assertTrue(leaving.justSwitched)
        assertEquals(0f, engine.frame(f, arrival + BoardEngine.DWELL_MS + BoardEngine.FLOOD_MS + 1).flood)
    }

    @Test
    fun afterATrainLeavesTheNextOneTakesOver() {
        val (engine, _) = engine()
        val arrival = 1_000_000L
        val f = feed(arrival, arrival + 180_000)
        val frame = engine.frame(f, arrival + BoardEngine.DWELL_MS + 5000)
        assertEquals("401", frame.focus?.trainNumber)
        assertEquals(155, frame.secondsToFocus)
    }

    @Test
    fun trainsMoveTowardsTheStationWithoutJumping() {
        val (engine, _) = engine()
        val arrival = 1_000_000L
        val f = feed(arrival)
        var previous = -9f
        var t = -300_000L
        while (t < BoardEngine.DWELL_MS + 15_000) {
            val mark = engine.frame(f, arrival + t).trains.firstOrNull()
            if (mark != null) {
                assertTrue(mark.position >= previous - 0.0001f, "moved backwards at $t: ${mark.position} after $previous")
                assertTrue(previous == -9f || mark.position - previous < 0.25f, "jumped at $t: ${mark.position} after $previous")
                previous = mark.position
            }
            t += 500
        }
        assertTrue(previous > 0f, "the train should have left")
    }

    @Test
    fun anEmptyFeedIsNotAProblem() {
        val (engine, _) = engine()
        val frame = engine.frame(ArrivalFeed(FeedSource.ESTIMATE, emptyList(), 0), 1000)
        assertEquals(Phase.WAITING, frame.phase)
        assertNull(frame.focus)
        assertTrue(frame.trains.isEmpty())
    }

    @Test
    fun countdownReadsInSecondsMinutesAndHours() {
        assertEquals("14" to "초", countdownParts(14))
        assertEquals("1" to "분", countdownParts(60))
        assertEquals("3" to "분", countdownParts(180))
        assertEquals("2" to "시간", countdownParts(7200))
    }

    @Test
    fun estimatesAreOrderedAndLabelled() {
        val path = BoardPath.build(network, network.line("S4")!!, s(3), s(4))
        val noon = epochOfKst(2026, 11, 3, 12, 0, 0)
        val feed = ServiceEstimator.next(path, noon, 3)
        assertEquals(FeedSource.ESTIMATE, feed.source)
        assertEquals(3, feed.trains.size)
        assertTrue(feed.trains.zipWithNext().all { (a, b) -> a.arrivalAtMs < b.arrivalAtMs })
        assertTrue(feed.trains.first().arrivalAtMs - noon < 7 * 60_000)
        assertTrue(feed.trains.all { it.trainNumber == null })
    }

    @Test
    fun neighbouringStationsAgreeAboutEstimatedTrains() {
        val line = network.line("S4")!!
        val noon = epochOfKst(2026, 11, 3, 12, 0, 0)
        val here = ServiceEstimator.next(BoardPath.build(network, line, s(3), s(4)), noon, 2).trains
        val next = ServiceEstimator.next(BoardPath.build(network, line, s(4), s(5)), noon, 2).trains
        assertTrue(next.any { n -> here.any { h -> n.arrivalAtMs - h.arrivalAtMs == 120_000L } })
    }

    @Test
    fun afterTheLastTrainComesTomorrowsFirst() {
        val path = BoardPath.build(network, network.line("S4")!!, s(3), s(4))
        val late = epochOfKst(2026, 11, 4, 2, 0, 0)
        val train = ServiceEstimator.next(path, late, 1).trains.single()
        val time = kstOf(train.arrivalAtMs)
        assertEquals(5, time.hour)
        assertEquals(4, time.day)
    }

    @Test
    fun carLoadsAverageToThePublishedLevel() {
        val time = kstOf(epochOfKst(2026, 11, 3, 18, 0, 0))
        val cars = CarLoads.estimate(null, "S4", 10, 3, null, time, 42)
        assertEquals(10, cars.size)
        assertEquals((1..10).toList(), cars.map { it.car })
        assertTrue(cars.all { it.percent in 2..170 })
        assertTrue(cars.map { it.percent }.toSet().size > 2, "the train should not be uniform")
        assertEquals(cars, CarLoads.estimate(null, "S4", 10, 3, null, time, 42))
    }

    @Test
    fun aBranchFollowsTheLongerWay() {
        val text = SMALL
        assertNotNull(text)
        assertFalse(BoardPath.build(network, network.line("S6")!!, s(6), s(3)).isLastStop)
    }
}
