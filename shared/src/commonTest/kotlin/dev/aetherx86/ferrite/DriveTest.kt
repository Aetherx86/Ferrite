package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.isInKorea
import dev.aetherx86.ferrite.drive.BatteryPlan
import dev.aetherx86.ferrite.drive.DriveService
import dev.aetherx86.ferrite.drive.TaxiFare
import dev.aetherx86.ferrite.trip.TripDay
import dev.aetherx86.ferrite.trip.TripPlan
import dev.aetherx86.ferrite.trip.TripStop
import dev.aetherx86.ferrite.trip.TripStore
import dev.aetherx86.ferrite.platform.KeyValueStore
import dev.aetherx86.ferrite.trip.reorder
import dev.aetherx86.ferrite.trip.timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveTest {
    @Test
    fun taxiFareStartsAtTheBaseAndClimbsLateAtNight() {
        assertEquals(4800, TaxiFare.estimate(1000.0, 12))
        assertEquals(4900, TaxiFare.estimate(1650.0, 12))
        val day = TaxiFare.estimate(10_000.0, 12)
        assertTrue(day in 11_000..11_400, "$day")
        assertTrue(TaxiFare.estimate(10_000.0, 23) > TaxiFare.estimate(10_000.0, 22))
        assertTrue(TaxiFare.estimate(10_000.0, 22) > day)
    }

    @Test
    fun worldTransitAnswersAreRead() {
        val text = """{"itineraries":[{"duration":1080,"startTime":"2026-11-09T09:00:00Z","endTime":"2026-11-09T09:18:00Z","transfers":0,"legs":[
            {"mode":"WALK","from":{"name":"START"},"to":{"name":"Hotel de Ville"},"duration":180,"legGeometry":{"points":"_p~iF~ps|U","precision":5}},
            {"mode":"SUBWAY","routeShortName":"1","headsign":"La Defense","routeColor":"ffbe00","realTime":true,"from":{"name":"Hotel de Ville"},"to":{"name":"Etoile"},"duration":720,"intermediateStops":[{"name":"a"},{"name":"b"}],"legGeometry":{"points":"_p~iF~ps|U_ulLnnqC","precision":5}}
        ]}]}"""
        val trip = dev.aetherx86.ferrite.transit.WorldTransit.parse(text).single()
        assertEquals(1080, trip.seconds)
        assertEquals(1_080_000L, trip.endMs - trip.startMs)
        assertEquals("걷기", trip.legs[0].label)
        val subway = trip.legs[1]
        assertEquals("지하철", subway.label)
        assertEquals(3, subway.stops)
        assertTrue(subway.live)
        assertEquals(38.5, subway.points[0].lat, 0.001)
        assertEquals(-120.2, subway.points[0].lon, 0.001)
    }

    @Test
    fun theKoreaCheckSeparatesHomeFromAbroad() {
        assertTrue(LatLng(37.55, 126.97).isInKorea())
        assertTrue(LatLng(35.1, 129.0).isInKorea())
        assertTrue(!LatLng(32.75, -97.33).isInKorea())
        assertTrue(!LatLng(35.68, 139.77).isInKorea())
    }

    @Test
    fun batteryRunsDownWithDistance() {
        val plan = BatteryPlan(percentNow = 50, capacityKwh = 60.0, kwhPerKm = 0.15)
        assertEquals(200.0, plan.rangeKm)
        assertEquals(25, plan.percentAfter(100_000.0))
        assertTrue(plan.percentAfter(400_000.0) < 0)
        assertEquals(152_000.0, plan.chargeBeforeMeters(12), 1.0)
    }

    @Test
    fun aRouteWithStopsKeepsEachLeg() {
        val text = """{"trip":{"summary":{"time":600,"length":5.0,"has_toll":true,"has_highway":false},"legs":[
            {"summary":{"time":200,"length":2.0},"shape":"_p~iF~ps|U_ulLnnqC","maneuvers":[{"type":1,"instruction":"출발","length":2.0,"begin_shape_index":0},{"type":4,"instruction":"도착","length":0,"begin_shape_index":1}]},
            {"summary":{"time":400,"length":3.0},"shape":"_p~iF~ps|U_ulLnnqC","maneuvers":[{"type":10,"instruction":"우회전","length":3.0,"begin_shape_index":0}]}
        ]}}"""
        val route = DriveService.parse(text)
        assertTrue(route.hasToll)
        assertEquals(listOf(200, 400), route.legs.map { it.first })
        assertEquals(5000, route.route.meters.toInt())
        assertEquals("출발 · 2.0km", route.route.steps.first().text)
    }

    @Test
    fun theOptimizedOrderIsApplied() {
        val text = """{"trip":{"locations":[{"original_index":0},{"original_index":2},{"original_index":1},{"original_index":3}]}}"""
        val order = DriveService.parseOrder(text)
        assertEquals(listOf(0, 2, 1, 3), order)
        val stops = listOf("a", "b", "c", "d").map { TripStop(it, 37.0, 127.0) }
        assertEquals(listOf("a", "c", "b", "d"), reorder(stops, order).map { it.name })
        assertEquals(stops, reorder(stops, listOf(0, 9)))
    }

    @Test
    fun aDaysTimelineAddsTravelAndStays() {
        val day = TripDay(listOf(TripStop("a", 37.0, 127.0, stayMinutes = 60), TripStop("b", 37.0, 127.0, stayMinutes = 30)), startMinute = 600)
        val timed = timeline(day, listOf(1200))
        assertEquals(600, timed[0].arriveMinute)
        assertEquals(660, timed[0].leaveMinute)
        assertEquals(20, timed[1].travelMinutes)
        assertEquals(680, timed[1].arriveMinute)
        assertEquals(710, timed[1].leaveMinute)
    }

    @Test
    fun plansSurviveARestart() {
        val memory = HashMap<String, String>()
        val store = TripStore(object : KeyValueStore {
            override fun getString(key: String) = memory[key]
            override fun putString(key: String, value: String?) {
                if (value == null) memory.remove(key) else memory[key] = value
            }
        })
        assertEquals(TripPlan(), store.load())
        val plan = TripPlan(listOf(TripDay(listOf(TripStop("경복궁", 37.57, 126.97, 90, "한복", 3000)))), walking = true)
        store.save(plan)
        assertEquals(plan, store.load())
        memory["trip"] = "not json"
        assertEquals(TripPlan(), store.load())
    }
}
