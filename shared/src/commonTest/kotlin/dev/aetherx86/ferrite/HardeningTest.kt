package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.Polyline
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.data.parseColor
import dev.aetherx86.ferrite.data.parseColorOrNull
import dev.aetherx86.ferrite.route.RoutePlanner
import dev.aetherx86.ferrite.route.TripPreference
import dev.aetherx86.ferrite.transit.WorldTransit
import dev.aetherx86.ferrite.walk.WalkProfile
import dev.aetherx86.ferrite.walk.WalkService
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {
    @Test
    fun polylineReadsTheFormatsReferenceExample() {
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, points.size)
        assertClose(LatLng(38.5, -120.2), points[0], 1e-5)
        assertClose(LatLng(40.7, -120.95), points[1], 1e-5)
        assertClose(LatLng(43.252, -126.453), points[2], 1e-5)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points, 5))
    }

    @Test
    fun polylineRoundTripsRandomShapes() {
        val random = Random(7)
        repeat(300) {
            val precision = if (random.nextBoolean()) 5 else 6
            val points = List(1 + random.nextInt(40)) { LatLng(random.nextDouble(-89.0, 89.0), random.nextDouble(-179.0, 179.0)) }
            val decoded = Polyline.decode(Polyline.encode(points, precision), precision)
            assertEquals(points.size, decoded.size)
            val tolerance = if (precision == 5) 0.6e-5 else 0.6e-6
            points.zip(decoded).forEach { (a, b) -> assertClose(a, b, tolerance) }
        }
    }

    @Test
    fun aCutShortPolylineKeepsWhatItHas() {
        val random = Random(11)
        val points = List(25) { LatLng(37.5 + random.nextDouble() / 10, 127.0 + random.nextDouble() / 10) }
        val text = Polyline.encode(points, 6)
        val full = Polyline.decode(text, 6)
        for (length in 0..text.length) {
            val partial = Polyline.decode(text.substring(0, length), 6)
            assertTrue(partial.size <= full.size)
            assertEquals(full.subList(0, partial.size), partial, "cut at $length")
        }
    }

    @Test
    fun polylineGarbageNeverThrows() {
        val random = Random(3)
        repeat(2000) {
            val text = CharArray(random.nextInt(60)) { random.nextInt(0x20, 0x2000).toChar() }.concatToString()
            val points = Polyline.decode(text, 6)
            assertTrue(points.all { abs(it.lat) <= 90 && abs(it.lon) <= 180 })
        }
        assertTrue(Polyline.decode("~".repeat(40), 6).isEmpty(), "a number that never ends")
        assertTrue(Polyline.decode("??  ??", 6).size <= 1)
        assertTrue(Polyline.decode("_p~iF~ps|U", 99).isEmpty())
        assertTrue(Polyline.decode("_p~iF~ps|U", -1).isEmpty())
        assertEquals(Polyline.decode("_p~iF~ps|U", 6), WalkService.decodeShape("_p~iF~ps|U"))
    }

    @Test
    fun aWalkAnswerWithNoShapeIsAClearFailure() {
        val text = """{"trip":{"summary":{"length":0.1,"time":60},"legs":[{"shape":"","maneuvers":[{"type":1,"instruction":"출발","length":0.1,"begin_shape_index":0}]}]}}"""
        val error = assertFailsWith<RuntimeException> { WalkService.parse(text, WalkProfile.FASTEST) }
        assertEquals("경로 모양 없음", error.message)
    }

    @Test
    fun aDamagedItineraryIsLeftOutAndTheRestKept() {
        val good = Polyline.encode(listOf(LatLng(32.75, -97.33), LatLng(32.76, -97.34)), 7)
        val text = """
            {"itineraries":[
             {"duration":600,"startTime":"2026-11-21T10:00:00Z","endTime":"2026-11-21T10:10:00Z","transfers":0,
              "legs":[{"mode":"BUS","routeShortName":"1","duration":600,"routeColor":"zzzzzz","from":{"name":"A"},"to":{"name":"B"},
                       "legGeometry":{"points":"${good.dropLast(2).replace("\\", "\\\\")}","precision":7}}]},
             {"duration":-5,"startTime":"yesterday","endTime":"2026-11-21T10:10:00Z","transfers":-1,"legs":[]},
             {"duration":60,"startTime":"2026-11-21T10:10:00Z","endTime":"2026-11-21T10:00:00Z","transfers":0,"legs":[]}
            ]}
        """.trimIndent()
        val trips = WorldTransit.parse(text)
        assertEquals(1, trips.size)
        val leg = trips.single().legs.single()
        assertNull(leg.colorHex)
        assertEquals(1, leg.points.size)
        assertClose(LatLng(32.75, -97.33), leg.points[0], 1e-7)
    }

    @Test
    fun coloursAreReadOrReplaced() {
        assertEquals(0xFF00A5DEL, parseColor("#00A5DE"))
        assertEquals(0xFF00A5DEL, parseColor("00a5de"))
        assertEquals(0xFFAABBCCL, parseColor("#abc"))
        assertNull(parseColorOrNull("#12345"))
        assertNull(parseColorOrNull("blue"))
        assertNull(parseColorOrNull("#"))
        assertEquals(TransitNetwork.NEUTRAL_COLOR, parseColor("#GGGGGG"))
    }

    /** Everything that can be wrong in a network file, next to a small part that is fine. */
    private val DAMAGED = """
    {"regions":[{"id":"capital","name":"수도권","w":10,"h":10},{"id":"","name":"?","w":1,"h":1}],
     "lines":[
      {"id":"S4","name":"4호선","short":"4","color":"#00A5DE","region":"capital","cars":10,
       "edges":[[1,2,120],[2,3,120],[3],[3,99,120],[3,3,120],[1,3,0],[1,3,999999],[2,1,-5]],"oneWay":[[1],[]]},
      {"id":"S4","name":"again","short":"4","color":"#000000","region":"capital","cars":10,"edges":[[1,4,10]]},
      {"id":"S6","name":"6호선","short":"6","color":"brown","region":"capital","cars":0,"edges":[[3,5,100],[5,6,100]]},
      {"id":"","name":"nameless","short":"","color":"#FFFFFF","region":"capital","cars":4,"edges":[[1,2,60]]}
     ],
     "stations":[
      {"id":1,"name":"가","lat":37.1,"lon":127.0,"x":1,"y":1,"region":"capital"},
      {"id":2,"name":"나","lat":37.2,"lon":127.0,"x":2,"y":1,"region":"capital"},
      {"id":3,"name":"다","lat":37.3,"lon":127.0,"x":3,"y":1,"region":"capital"},
      {"id":3,"name":"다 again","lat":37.3,"lon":127.0,"x":3,"y":1,"region":"capital"},
      {"id":4,"name":"라","lat":137.4,"lon":127.0,"x":4,"y":1,"region":"capital"},
      {"id":5,"name":"마","lat":37.5,"lon":127.0,"x":5,"y":1,"region":"capital"},
      {"id":6,"name":"바","lat":37.6,"lon":127.0,"x":6,"y":1,"region":"capital"}
     ],
     "runs":{"capital":[{"lines":["S4","ZZ"],"stations":[1,2,99],"path":[0,0,1,1]},{"lines":["S4"],"stations":[1],"path":[0,0,1]}]},
     "transfers":[{"s":3,"a":"S4","b":"S6","m":100,"sec":90},{"s":3,"a":"S4","b":"XX","m":1,"sec":1},{"s":42,"a":"S4","b":"S6","m":1,"sec":1},
                  {"s":3,"a":"S4","b":"S4","m":1,"sec":1},{"s":3,"a":"S6","b":"S4","m":1,"sec":99999}]}
    """

    @Test
    fun aDamagedNetworkLoadsWhatItCanAndSaysWhatItLeftOut() {
        val network = TransitNetwork.parse(DAMAGED)
        assertEquals(listOf("capital"), network.regions.map { it.id })
        assertEquals(listOf("S4", "S6"), network.lines.map { it.id })
        assertEquals("4호선", network.line("S4")!!.name)
        assertEquals(TransitNetwork.NEUTRAL_COLOR, network.line("S6")!!.colorArgb)
        assertEquals(1, network.line("S6")!!.cars)
        assertEquals(listOf(1, 2, 3, 5, 6), network.stations.map { it.id })
        assertEquals("다", network.station(3)!!.name)
        // Only the sound hops are on the map.
        assertEquals(listOf(2), network.hopsFrom("S4", 1).map { it.to })
        assertEquals(listOf(2), network.hopsFrom("S4", 3).map { it.to })
        assertEquals(90, network.transferSeconds(3, "S4", "S6"))
        assertEquals(listOf(listOf(1, 2)), network.runs.getValue("capital").map { it.stationIds })
        assertEquals(listOf("S4"), network.runs.getValue("capital").single().lines.map { it.id })

        val problems = network.issues.map { "${it.where}: ${it.problem}" }
        val expected = listOf(
            "region" to "blank id",
            "line S4" to "listed twice",
            "line S6" to "colour",
            "line S6" to "0 cars",
            "lines" to "no id",
            "station 3" to "listed twice",
            "station 4" to "position",
            "line S4" to "two stations and a time",
            "line S4" to "unknown station",
            "line S4" to "to itself",
            "line S4" to "0 seconds",
            "line S4" to "999999 seconds",
            "line S4" to "-5 seconds",
            "transfers" to "unknown lines",
            "transfers" to "unknown station 42",
            "transfers" to "99999 seconds",
            "run in capital" to "unknown stations",
            "run in capital" to "damaged path",
        )
        for ((where, what) in expected) {
            assertTrue(network.issues.any { it.where == where && what in it.problem }, "missing $where: $what in $problems")
        }
        assertEquals(expected.size, network.issues.size, problems.joinToString("\n"))

        val trip = RoutePlanner(network).plan(network.station(1)!!, network.station(6)!!, TripPreference.FASTEST)!!
        assertEquals(listOf("S4", "S6"), trip.legs.map { it.line.id })
        assertEquals(240 + 90 + 180 + 200, trip.seconds)
    }

    private fun assertClose(expected: LatLng, actual: LatLng, tolerance: Double) {
        assertTrue(abs(expected.lat - actual.lat) <= tolerance && abs(expected.lon - actual.lon) <= tolerance, "$expected vs $actual")
    }
}
