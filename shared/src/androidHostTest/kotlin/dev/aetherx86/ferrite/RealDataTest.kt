package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.data.CongestionTable
import dev.aetherx86.ferrite.data.GeoMapData
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.route.RoutePlanner
import dev.aetherx86.ferrite.route.TripPreference
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the shipped data against things anyone can verify on a real map. */
class RealDataTest {
    private fun read(name: String) = File("src/commonMain/composeResources/files/$name").readText()

    private fun load(block: (TransitNetwork) -> Unit) = block(TransitNetwork.parse(read("network.json")))

    @Test
    fun linesHaveTheirRealStationCounts() = load { network ->
        fun count(id: String) = network.stations.count { station -> station.lines.any { it.id == id } }
        assertEquals(51, count("S2"))
        assertEquals(44, count("S3"))
        assertEquals(51, count("S4"))
        assertEquals(56, count("S5"))
        assertEquals(39, count("S6"))
        assertEquals(53, count("S7"))
        assertEquals(24, count("S8"))
        assertEquals(38, count("S9"))
        assertEquals(40, count("B1"))
        assertEquals(22, count("DJ"))
        assertEquals(20, count("GW"))
    }

    @Test
    fun knownInterchangesConnectTheirLines() = load { network ->
        fun lines(name: String) = network.named(name)!!.lines.map { it.id }.toSet()
        assertTrue(lines("삼각지").containsAll(setOf("S4", "S6")))
        assertTrue(lines("서울역").containsAll(setOf("S1", "S4", "AR", "GJ")))
        assertTrue(lines("왕십리").containsAll(setOf("S2", "S5", "GJ", "SB")))
        assertTrue(lines("고속터미널").containsAll(setOf("S3", "S7", "S9")))
    }

    @Test
    fun theVideoStationReadsLikeARealPlatform() = load { network ->
        val here = network.named("삼각지")!!
        val line = network.line("S4")!!
        val next = network.named("숙대입구")!!
        val path = BoardPath.build(network, line, here, next)
        assertEquals(listOf("신용산", "이촌", "동작"), path.upstream.map { it.name })
        assertEquals("숙대입구", path.next?.name)
        assertEquals("진접", path.terminus.name)
    }

    @Test
    fun line2IsARing() = load { network ->
        val line = network.line("S2")!!
        val here = network.named("시청")!!
        val paths = BoardPath.allFrom(network, line, here)
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.upstream.isNotEmpty() && it.downstream.isNotEmpty() })
    }

    @Test
    fun longTripsFindSensibleTimes() = load { network ->
        val planner = RoutePlanner(network)
        val trip = planner.plan(network.named("삼각지")!!, network.named("강남")!!, TripPreference.FASTEST)!!
        assertTrue(trip.seconds in 15 * 60..40 * 60, "${trip.seconds}")
        assertEquals(1, trip.transferCount)
        assertNotNull(trip.fare)
        val far = planner.plan(network.named("인천공항2터미널")!!, network.named("춘천")!!, TripPreference.FASTEST)!!
        assertTrue(far.seconds in 2 * 3600..5 * 3600, "${far.seconds}")
        val busan = planner.plan(network.stations.first { it.name == "서면" }, network.stations.first { it.name == "해운대" }, TripPreference.FASTEST)!!
        assertEquals(null, busan.fare)
    }

    @Test
    fun everyStationCanBeOnABoard() = load { network ->
        for (station in network.stations) {
            assertTrue(station.lines.isNotEmpty(), station.name)
            assertTrue(station.nameEn.isNotBlank(), station.name)
            for (line in station.lines) {
                val paths = BoardPath.allFrom(network, line, station)
                assertTrue(paths.isNotEmpty(), station.name)
            }
        }
    }

    @Test
    fun theSchematicHasEveryStation() = load { network ->
        val placed = network.runs.values.flatten().flatMap { it.stationIds }.toSet()
        assertEquals(network.stations.map { it.id }.toSet(), placed)
        for (run in network.runs.values.flatten()) {
            var i = 2
            while (i + 1 < run.path.size) {
                val dx = kotlin.math.abs(run.path[i] - run.path[i - 2])
                val dy = kotlin.math.abs(run.path[i + 1] - run.path[i - 1])
                assertTrue(dx == 0f || dy == 0f || kotlin.math.abs(dx - dy) < 0.01f, "off the 45 degree grid at ${run.stationIds}")
                i += 2
            }
        }
    }

    @Test
    fun theOtherDataFilesLoad() {
        val table = CongestionTable.parse(read("congestion.json"))
        assertNotNull(table.load("S4", TransitNetwork.parse(read("network.json")).named("삼각지")!!.id, true, 0, 18 * 60))
        val geo = GeoMapData.parse(read("geo.json"))
        assertEquals(17, geo.provinces.size)
        assertEquals(250, geo.municipalities.size)
        assertTrue(geo.tracks.size > 30)
    }
}
