package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.airport.Flights
import dev.aetherx86.ferrite.airport.Movement
import dev.aetherx86.ferrite.bus.BusFeed
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.epochOfKst
import dev.aetherx86.ferrite.data.CongestionDto
import dev.aetherx86.ferrite.data.CongestionTable
import dev.aetherx86.ferrite.live.FeedException
import dev.aetherx86.ferrite.live.SeoulArrivals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTest {
    @Test
    fun readsARealFeedAnswer() {
        val rows = SeoulArrivals.parse(SEOUL_SAMPLE)
        assertEquals(5, rows.size)
        assertTrue(rows.any { it.subwayId == "1004" && "회현" in it.trainLineNm })
    }

    @Test
    fun picksOnlyTrainsGoingThroughTheNextStation() {
        val rows = SeoulArrivals.parse(SEOUL_SAMPLE)
        val now = epochOfKst(2026, 11, 7, 6, 54, 30)
        val line4 = SeoulArrivals.approaches(rows, 1004, "회현", now)
        assertEquals(1, line4.size)
        assertEquals("진접", line4[0].destination)
        assertEquals("4014", line4[0].trainNumber)
        assertTrue(SeoulArrivals.approaches(rows, 1004, "숙대입구", now).isEmpty())
        // The feed writes some directions with a line name in brackets.
        assertEquals(1, SeoulArrivals.approaches(rows, 1063, "신촌", now).size)
    }

    @Test
    fun expressAndReportTimeAreRead() {
        val rows = SeoulArrivals.parse(SEOUL_SAMPLE)
        val now = epochOfKst(2026, 11, 7, 6, 54, 30)
        val airport = SeoulArrivals.approaches(rows, 1065, "공덕", now)
        assertTrue(airport.any { it.express })
        assertEquals(epochOfKst(2026, 11, 7, 6, 54, 20), SeoulArrivals.parseKst("2026-11-07 06:54:20"))
        assertNull(SeoulArrivals.parseKst("어제"))
    }

    @Test
    fun aTrainStandingHereForAWhileStaysHere() {
        val rows = SeoulArrivals.parse(SEOUL_SAMPLE)
        val now = epochOfKst(2026, 11, 7, 7, 30, 0)
        val train = SeoulArrivals.approaches(rows, 1004, "회현", now).single()
        assertTrue(train.arrivalAtMs <= now && now - train.arrivalAtMs <= 20_000)
    }

    @Test
    fun noTrainsIsAnAnswerAndABadKeyIsNot() {
        assertTrue(SeoulArrivals.parse("""{"status":500,"code":"INFO-200","message":"없음","total":0}""").isEmpty())
        assertFailsWith<FeedException> {
            SeoulArrivals.parse("""{"status":500,"code":"INFO-100","message":"인증키가 유효하지 않습니다","total":0}""")
        }
    }

    @Test
    fun flightsParseInEitherShape() {
        val text = """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},"body":{"items":[
            {"airline":"가상항공","flightId":"XX1","scheduleDateTime":"1200","estimatedDateTime":"1230","airport":"도쿄","airportCode":"NRT","terminalId":"P03","gatenumber":"251","chkinrange":"A01-A10","remark":"지연"}
        ]}}}"""
        val flight = Flights.parse(text, Movement.DEPARTURE).single()
        assertEquals("T2", flight.terminal)
        assertTrue(flight.isDelayed)
        assertEquals("A01-A10", flight.counter)
        val wrapped = """{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{"flightId":"XX2","scheduleDateTime":"1300"}]}}}}"""
        assertEquals("XX2", Flights.parse(wrapped, Movement.ARRIVAL).single().number)
    }

    @Test
    fun flightErrorsSaySo() {
        val error = assertFailsWith<Exception> {
            Flights.parse("<OpenAPI_ServiceResponse><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></OpenAPI_ServiceResponse>", Movement.DEPARTURE)
        }
        assertTrue("REGISTERED" in error.message.orEmpty())
    }

    @Test
    fun demoFlightsLabelDelays() {
        val flights = Flights.demo(Movement.DEPARTURE, epochOfKst(2026, 11, 7, 7, 0, 0))
        assertTrue(flights.any { it.isDelayed && it.status == "지연" })
        assertFalse(flights.any { !it.isDelayed && it.status == "지연" })
    }

    @Test
    fun busFeedsParseInBothDialects() {
        val national = """{"response":{"body":{"items":{"item":{"nodeid":"N1","nodeno":"123","nodenm":"시청","gpslati":35.1,"gpslong":129.0,"citycode":21}}}}}"""
        val stop = BusFeed.parseNationalStops(national, LatLng(35.1, 129.0)).single()
        assertEquals("21", stop.cityCode)
        val arrivals = """{"response":{"body":{"items":{"item":[{"routeno":"100","arrtime":240,"arrprevstationcnt":3,"vehicletp":"저상버스"}]}}}}"""
        val arrival = BusFeed.parseNationalArrivals(arrivals).single()
        assertEquals(240, arrival.seconds)
        assertTrue(arrival.lowFloor)
        val seoul = """{"msgHeader":{"headerCd":"0","headerMsg":"ok"},"msgBody":{"itemList":[{"rtNm":"110A","adirection":"고려대","arrmsg1":"3분12초후[2번째 전]","busType1":"1"},{"rtNm":"N62","arrmsg1":"곧 도착"},{"rtNm":"X","arrmsg1":"운행종료"}]}}"""
        val rows = BusFeed.parseSeoulArrivals(seoul)
        assertEquals(192, rows[0].seconds)
        assertEquals(2, rows[0].stopsAway)
        assertEquals(30, rows[1].seconds)
        assertNull(rows[2].seconds)
        assertFailsWith<Exception> { BusFeed.parseSeoulArrivals("""{"msgHeader":{"headerCd":"7","headerMsg":"인증 실패"}}""") }
    }

    @Test
    fun congestionFollowsDayAndDirection() {
        val table = CongestionTable(
            CongestionDto(
                slots = emptyList(),
                weekday = mapOf("S4|1|U" to List(39) { it }),
                saturday = mapOf("S4|1|U" to List(39) { 100 }),
                sunday = emptyMap(),
                codes = mapOf("S4|1" to 428, "S4|2" to 427, "S1|1" to 150, "S1|2" to 151),
            ),
        )
        assertEquals(1, table.load("S4", 1, true, 0, 6 * 60))
        assertEquals(100, table.load("S4", 1, true, 5, 6 * 60))
        assertNull(table.load("S4", 1, false, 0, 6 * 60))
        assertNull(table.load("S4", 1, true, 6, 6 * 60))
        assertEquals(38, table.load("S4", 1, true, 0, 24 * 60 + 40))
        assertEquals(true, table.isUp("S4", 1, 2))
        assertEquals(true, table.isUp("S1", 1, 2))
        assertNotNull(table.isUp("S4", 2, 1))
    }
}
