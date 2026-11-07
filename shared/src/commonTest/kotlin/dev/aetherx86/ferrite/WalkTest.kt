package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.walk.PlaceSearch
import dev.aetherx86.ferrite.walk.StepKind
import dev.aetherx86.ferrite.walk.WalkGraph
import dev.aetherx86.ferrite.walk.WalkProfile
import dev.aetherx86.ferrite.walk.WalkRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two ways from A to D. The short one climbs stairs through an alley with no lights. The long one follows a lit main
 * road with a signalled crossing.
 */
private val SAMPLE = """
{"elements":[
 {"type":"node","id":20,"lat":37.5010,"lon":127.0035,"tags":{"highway":"traffic_signals"}},
 {"type":"node","id":30,"lat":37.5020,"lon":127.0000,"tags":{"railway":"subway_entrance","ref":"3"}},
 {"type":"way","id":1,"nodes":[1,10,11,4],"geometry":[{"lat":37.5000,"lon":127.0000},{"lat":37.5003,"lon":127.0003},{"lat":37.5006,"lon":127.0006},{"lat":37.5009,"lon":127.0000}],"tags":{"highway":"steps"}},
 {"type":"way","id":2,"nodes":[4,5],"geometry":[{"lat":37.5009,"lon":127.0000},{"lat":37.5020,"lon":127.0000}],"tags":{"highway":"service","lit":"no"}},
 {"type":"way","id":3,"nodes":[1,20],"geometry":[{"lat":37.5000,"lon":127.0000},{"lat":37.5010,"lon":127.0035}],"tags":{"highway":"secondary","name":"큰길","lit":"yes","sidewalk":"both"}},
 {"type":"way","id":4,"nodes":[20,5],"geometry":[{"lat":37.5010,"lon":127.0035},{"lat":37.5020,"lon":127.0000}],"tags":{"highway":"secondary","name":"큰길","lit":"yes","sidewalk":"both"}},
 {"type":"way","id":5,"nodes":[100,101],"geometry":[{"lat":37.6000,"lon":127.1000},{"lat":37.6001,"lon":127.1000}],"tags":{"highway":"footway"}},
 {"type":"way","id":6,"nodes":[1,200],"geometry":[{"lat":37.5000,"lon":127.0000},{"lat":37.4990,"lon":127.0000}],"tags":{"highway":"motorway"}}
]}
"""

class WalkTest {
    private val graph = WalkGraph.parse(SAMPLE)
    private val start = LatLng(37.5000, 127.0000)
    private val end = LatLng(37.5020, 127.0000)

    @Test
    fun theFastestRouteTakesTheStairs() {
        val route = WalkRouter.route(graph, start, end, WalkProfile.FASTEST)!!
        assertTrue(route.stairs >= 1)
        assertTrue(route.steps.any { it.kind == StepKind.STAIRS })
        assertEquals(StepKind.ARRIVE, route.steps.last().kind)
    }

    @Test
    fun stepFreeAvoidsStairsEvenWhenLonger() {
        val fast = WalkRouter.route(graph, start, end, WalkProfile.FASTEST)!!
        val flat = WalkRouter.route(graph, start, end, WalkProfile.STEP_FREE)!!
        assertEquals(0, flat.stairs)
        assertFalse(flat.steps.any { it.kind == StepKind.STAIRS })
        assertTrue(flat.meters >= fast.meters)
    }

    @Test
    fun brightRouteFollowsTheLitRoad() {
        val bright = WalkRouter.route(graph, start, end, WalkProfile.BRIGHT)!!
        assertEquals(0, bright.stairs)
        assertTrue(bright.steps.any { "큰길" in it.text })
    }

    @Test
    fun aSignalAddsWaitingTime() {
        val flat = WalkRouter.route(graph, start, end, WalkProfile.STEP_FREE)!!
        assertTrue(flat.seconds >= (flat.meters / 1.2).toInt() + 25)
    }

    @Test
    fun stepFreeStopsShortWhenOnlyStairsReachTheEnd() {
        val text = """{"elements":[
          {"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":37.5,"lon":127.0},{"lat":37.501,"lon":127.0}],"tags":{"highway":"footway"}},
          {"type":"way","id":2,"nodes":[2,3],"geometry":[{"lat":37.501,"lon":127.0},{"lat":37.5012,"lon":127.0}],"tags":{"highway":"steps"}}
        ]}"""
        val route = WalkRouter.route(WalkGraph.parse(text), LatLng(37.5, 127.0), LatLng(37.5012, 127.0), WalkProfile.STEP_FREE)!!
        assertEquals(0, route.stairs)
        assertTrue(route.shortOfGoalMeters in 15..30)
        assertTrue("계단 없는 길" in route.steps.last().text)
    }

    @Test
    fun motorwaysAreNotWalkable() {
        assertNull(graph.nodes.firstOrNull { it.position.lat == 37.4990 })
    }

    @Test
    fun aDisconnectedPathIsNotUsedToStartARoute() {
        // Closest to the island of footway, but routing has to begin on the main network.
        val route = WalkRouter.route(graph, LatLng(37.6000, 127.1000), end, WalkProfile.FASTEST)
        assertNotNull(route)
    }

    @Test
    fun theRoutingServiceAnswerIsRead() {
        val text = """{"trip":{"summary":{"time":1805.4,"length":2.073},"legs":[{"shape":"_p~iF~ps|U_ulLnnqC","maneuvers":[
            {"type":1,"instruction":"왕십리광장로에서 동쪽으로 걷기","length":0.029,"begin_shape_index":0},
            {"type":15,"instruction":"횡단보도에서 좌회전","length":0.039,"begin_shape_index":1},
            {"type":40,"instruction":"계단으로 올라가기","length":0.02,"begin_shape_index":1},
            {"type":4,"instruction":"목적지 도착","length":0.0,"begin_shape_index":1}
        ]}]}}"""
        val route = dev.aetherx86.ferrite.walk.WalkService.parse(text, WalkProfile.FASTEST)
        assertEquals(2073, route.meters.toInt())
        assertEquals(1805, route.seconds)
        assertEquals(1, route.stairs)
        assertEquals(1, route.crossings)
        assertEquals(StepKind.ARRIVE, route.steps.last().kind)
        assertEquals(2, route.points.size)
    }

    @Test
    fun tileGeometryDecodes() {
        // A square: move to (1,1), three lines, close. From the vector tile format's own example encoding.
        val parts = dev.aetherx86.ferrite.map.VectorTileReader.decodeGeometry(intArrayOf(9, 2, 2, 26, 8, 0, 0, 8, 7, 0, 15))
        assertEquals(1, parts.size)
        assertEquals(listOf(1, 1, 5, 1, 5, 5, 1, 1), parts[0].toList().take(8).let { listOf(it[0], it[1], it[2], it[3], it[4], it[5], it[0], it[1]) })
    }

    @Test
    fun aTileLayerIsRead() {
        // layer { name="road" feature { tags=[0,0] type=2 geometry=[9,2,2,10,4,4] } keys="class" values={string="minor"} extent=4096 }
        val name = "road".encodeToByteArray()
        val feature = byteArrayOf(0x12, 2, 0, 0, 0x18, 2, 0x22, 6, 9, 2, 2, 10, 4, 4)
        val key = "class".encodeToByteArray()
        val value = byteArrayOf(0x0A, 5) + "minor".encodeToByteArray()
        val layer = byteArrayOf(0x0A, name.size.toByte()) + name +
            byteArrayOf(0x12, feature.size.toByte()) + feature +
            byteArrayOf(0x1A, key.size.toByte()) + key +
            byteArrayOf(0x22, value.size.toByte()) + value
        val tile = byteArrayOf(0x1A, layer.size.toByte()) + layer
        val read = dev.aetherx86.ferrite.map.VectorTileReader.read(tile, setOf("road")).single()
        assertEquals("road", read.name)
        assertEquals("minor", read.features.single().tags["class"])
        assertEquals(2, read.features.single().type)
        assertEquals(listOf(1, 1, 3, 3), read.features.single().parts.single().toList())
        assertTrue(dev.aetherx86.ferrite.map.VectorTileReader.read(tile, setOf("other")).isEmpty())
    }

    @Test
    fun exitsAreRead() {
        assertEquals("3", graph.exits.single().ref)
    }

    @Test
    fun noPathsMeansNoRoute() {
        assertNull(WalkRouter.route(WalkGraph.parse("""{"elements":[]}"""), start, end, WalkProfile.FASTEST))
    }

    @Test
    fun placesParseAndDropTheCountry() {
        val places = PlaceSearch.parse("""[{"lat":"37.53","lon":"126.97","name":"전쟁기념관","display_name":"전쟁기념관, 이태원로, 용산구, 서울, 04353, 대한민국"},{"lat":"bad","lon":"1","name":"x","display_name":"x"}]""")
        assertEquals(1, places.size)
        assertEquals("전쟁기념관", places[0].name)
        assertEquals("서울 용산구 이태원로", places[0].detail)
    }
}
