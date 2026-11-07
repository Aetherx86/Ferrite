package dev.aetherx86.ferrite.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.aetherx86.ferrite.platform.httpBytes
import dev.aetherx86.ferrite.platform.httpRequest
import dev.aetherx86.ferrite.ui.common.textStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.sinh
import kotlin.math.tan

private val LAND = Color(0xFF131923)
private val GREEN = Color(0xFF16291F)
private val WATER = Color(0xFF12344C)
private val BUILDING = Color(0xFF1E2531)
private val RAIL = Color(0xFF4A5261)
private val LABEL_PLACE = Color(0xFFE3E8F0)
private val LABEL_ROAD = Color(0xFFA3AEBF)
private val LABEL_POI = Color(0xFF8C98AB)
private val LABEL_WATER = Color(0xFF6FA6C9)

/** Road classes from thinnest to thickest, with their color and width in screen units. */
private enum class RoadKind(val color: Color, val width: Float) {
    PATH(Color(0xFF39424F), 1.1f),
    SERVICE(Color(0xFF333C4A), 1.5f),
    MINOR(Color(0xFF3E4857), 2.6f),
    TERTIARY(Color(0xFF4B5667), 3.4f),
    SECONDARY(Color(0xFF586476), 4.4f),
    PRIMARY(Color(0xFF6A778B), 5.4f),
    MOTORWAY(Color(0xFF7D8BA1), 6.4f),
}

private class MapLabel(val text: String, val x: Float, val y: Float, val priority: Int, val kind: Int)

private class TileScene(
    val water: Path,
    val green: Path,
    val buildings: Path,
    val roads: Map<RoadKind, Path>,
    val rail: Path,
    val labels: List<MapLabel>,
)

/**
 * Streets, buildings, water, parks and Korean place names, from open vector map tiles, drawn by the app in its own
 * colors. Tiles come off a CDN in a fraction of a second, and a map screen only asks for those it can see.
 *
 * @param project turns longitude and latitude into the coordinates the screen draws in
 * @param unitsPerKm how many of those coordinates make a kilometre
 */
class BaseMap(
    private val scope: CoroutineScope,
    private val unitsPerKm: Float,
    private val project: (Double, Double) -> Pair<Float, Float>,
) {
    private val tiles = HashMap<Long, TileScene>()
    private val requested = HashSet<Long>()
    private val labelCache = HashMap<String, TextLayoutResult>()

    /** Bumps when a tile arrives, so whoever draws the map redraws. */
    var revision by mutableIntStateOf(0)
        private set

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    /** Ensures the tiles for an area are on their way, and returns the ones that have come. */
    private fun tilesFor(west: Double, north: Double, east: Double, south: Double, z: Int): List<TileScene> {
        val n = 1 shl z
        val x0 = lonToTile(west, n).coerceIn(0, n - 1)
        val x1 = lonToTile(east, n).coerceIn(0, n - 1)
        val y0 = latToTile(north, n).coerceIn(0, n - 1)
        val y1 = latToTile(south, n).coerceIn(0, n - 1)
        val out = ArrayList<TileScene>()
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val k = key(z, x, y)
                val tile = tiles[k]
                if (tile != null) out += tile else request(k, z, x, y)
                x++
            }
            y++
        }
        return out
    }

    private fun request(k: Long, z: Int, x: Int, y: Int) {
        if (!requested.add(k)) return
        scope.launch {
            try {
                val bytes = limit.withPermit { httpBytes(tileUrl(z, x, y), timeoutMs = 15_000) }
                val scene = withContext(Dispatchers.Default) { build(bytes, z, x, y) }
                tiles[k] = scene
                revision++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                requested.remove(k)
            }
        }
    }

    private fun build(bytes: ByteArray, z: Int, tx: Int, ty: Int): TileScene {
        val layers = VectorTileReader.read(bytes, WANTED_LAYERS)
        val water = Path()
        val green = Path()
        val buildings = Path()
        val rail = Path()
        val roads = RoadKind.entries.associateWith { Path() }
        val labels = ArrayList<MapLabel>()
        val n = (1 shl z).toDouble()
        for (layer in layers) {
            val e = layer.extent.toDouble()
            fun point(px: Int, py: Int): Pair<Float, Float> {
                val lon = (tx + px / e) / n * 360.0 - 180.0
                val lat = atan(sinh(PI * (1 - 2 * (ty + py / e) / n))) * 180.0 / PI
                return project(lon, lat)
            }
            fun Path.add(part: IntArray) {
                var i = 0
                while (i + 1 < part.size) {
                    val (x, y) = point(part[i], part[i + 1])
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                    i += 2
                }
            }
            for (feature in layer.features) {
                val cls = feature.tags["class"]
                when (layer.name) {
                    "water" -> if (feature.type == 3) feature.parts.forEach { water.add(it) }
                    "waterway" -> {}
                    "park" -> if (feature.type == 3) feature.parts.forEach { green.add(it) }
                    "landcover" -> if (feature.type == 3 && cls in GREEN_CLASSES) feature.parts.forEach { green.add(it) }
                    "landuse" -> if (feature.type == 3 && cls in GREEN_USES) feature.parts.forEach { green.add(it) }
                    "building" -> if (feature.type == 3) feature.parts.forEach { buildings.add(it) }
                    "transportation" -> if (feature.type == 2) {
                        val kind = roadKind(cls, feature.tags["subclass"])
                        when {
                            cls == "rail" || cls == "transit" -> feature.parts.forEach { rail.add(it) }
                            kind != null -> feature.parts.forEach { roads.getValue(kind).add(it) }
                        }
                    }
                    "transportation_name", "place", "poi", "water_name" -> {
                        val name = feature.tags["name:ko"] ?: feature.tags["name"] ?: continue
                        val part = feature.parts.firstOrNull() ?: continue
                        val mid = (part.size / 4) * 2
                        val (x, y) = point(part[mid], part[mid + 1])
                        val rank = feature.tags["rank"]?.toIntOrNull() ?: 20
                        val (priority, kind) = when (layer.name) {
                            "place" -> (if (cls == "city" || cls == "town") 0 else 2) to 0
                            "transportation_name" -> (if (cls in MAJOR_ROADS) 3 else 6) to 1
                            "water_name" -> 4 to 3
                            else -> (5 + rank) to 2
                        }
                        labels += MapLabel(name, x, y, priority, kind)
                    }
                }
            }
        }
        return TileScene(water, green, buildings, roads, rail, labels)
    }

    private fun roadKind(cls: String?, subclass: String?): RoadKind? = when (cls) {
        "motorway", "trunk" -> RoadKind.MOTORWAY
        "primary" -> RoadKind.PRIMARY
        "secondary" -> RoadKind.SECONDARY
        "tertiary" -> RoadKind.TERTIARY
        "minor" -> RoadKind.MINOR
        "service" -> RoadKind.SERVICE
        "path", "track" -> RoadKind.PATH
        else -> if (subclass != null && cls?.endsWith("_construction") != true) RoadKind.PATH else null
    }

    /**
     * Draws the map. The caller gives the visible area and how many pixels a coordinate takes.
     *
     * @param toScreen turns the map's coordinates into pixels, for the labels
     */
    fun draw(
        scope: DrawScope,
        west: Double,
        north: Double,
        east: Double,
        south: Double,
        pixelsPerUnit: Float,
        transform: DrawScope.(DrawScope.() -> Unit) -> Unit,
        toScreen: (Float, Float) -> Offset,
        measurer: TextMeasurer,
        family: FontFamily,
    ) = with(scope) {
        val read = revision // Reading this redraws the map when a tile arrives.
        check(read >= 0)
        val density = density
        // A tile should show at around 300 pixels or larger, which keeps the number of tiles down.
        val kmPerPixel = 1f / (pixelsPerUnit * unitsPerKm)
        val wantedTileKm = kmPerPixel * 256f * density / 2.6f
        val z = floor(log2(EARTH_KM_AT_KOREA / wantedTileKm.toDouble())).toInt().coerceIn(6, 14)
        val scenes = tilesFor(west, north, east, south, z)
        drawRect(LAND)
        transform {
            val px = 1f / pixelsPerUnit
            for (tile in scenes) drawPath(tile.green, GREEN)
            for (tile in scenes) drawPath(tile.water, WATER)
            if (z >= 14) for (tile in scenes) drawPath(tile.buildings, BUILDING)
            // Zoomed out, roads thin down and small ones drop away, or the city turns into a solid mesh.
            val roadScale = when {
                z >= 14 -> 1f
                z == 13 -> 0.72f
                z == 12 -> 0.5f
                else -> 0.34f
            }
            for (kind in RoadKind.entries) {
                if (z < 13 && kind <= RoadKind.SERVICE) continue
                if (z < 12 && kind <= RoadKind.MINOR) continue
                val stroke = Stroke(kind.width * roadScale * density * px, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val color = if (z < 13) kind.color.copy(alpha = 0.7f) else kind.color
                for (tile in scenes) tile.roads[kind]?.let { drawPath(it, color, style = stroke) }
            }
            val railStroke = Stroke(1.3f * density * px)
            for (tile in scenes) drawPath(tile.rail, RAIL, style = railStroke)
        }
        drawLabels(scenes, z, toScreen, measurer, family)
    }

    private fun DrawScope.drawLabels(scenes: List<TileScene>, z: Int, toScreen: (Float, Float) -> Offset, measurer: TextMeasurer, family: FontFamily) {
        val placed = ArrayList<Rect>()
        val width = size.width
        val height = size.height
        val seen = HashSet<String>()
        val candidates = scenes.flatMap { it.labels }.filter { (z >= 13 || it.kind == 0) && (z >= 14 || it.kind != 2) }.sortedBy { it.priority }
        var shown = 0
        for (label in candidates) {
            if (shown >= MAX_LABELS) break
            val at = toScreen(label.x, label.y)
            if (at.x < 0f || at.y < 0f || at.x > width || at.y > height) continue
            if (label.kind != 2 && !seen.add(label.text)) continue
            val (color, weight, sizeSp) = when (label.kind) {
                0 -> Triple(LABEL_PLACE, FontWeight.Bold, 12f)
                1 -> Triple(LABEL_ROAD, FontWeight.Medium, 10.5f)
                3 -> Triple(LABEL_WATER, FontWeight.Medium, 11f)
                else -> Triple(LABEL_POI, FontWeight.Normal, 10f)
            }
            val layout = labelCache.getOrPut("${label.kind}${label.text}") {
                measurer.measure(label.text, textStyle(family, (sizeSp * density).toSp(), weight, color), softWrap = false, maxLines = 1)
            }
            val box = Rect(at.x - layout.size.width / 2f - 3f, at.y - layout.size.height / 2f, at.x + layout.size.width / 2f + 3f, at.y + layout.size.height / 2f)
            if (box.left < 0f || box.right > width || placed.any { it.overlaps(box) }) continue
            placed += box
            drawText(layout, topLeft = Offset(box.left + 3f, box.top))
            shown++
        }
    }

    companion object {
        private val WANTED_LAYERS = setOf("water", "park", "landcover", "landuse", "building", "transportation", "transportation_name", "place", "poi", "water_name")
        private val GREEN_CLASSES = setOf("wood", "grass", "farmland")
        private val GREEN_USES = setOf("park", "cemetery", "pitch", "playground", "stadium")
        private val MAJOR_ROADS = setOf("motorway", "trunk", "primary", "secondary")
        private const val MAX_LABELS = 36

        /** How many kilometres the whole tile grid spans at Korea's latitude, for picking a zoom level. */
        private const val EARTH_KM_AT_KOREA = 40075.0 * 0.8

        private val limit = Semaphore(6)
        private val json = Json { ignoreUnknownKeys = true }
        private var template: String? = null

        /** The service publishes where its current tiles are. */
        internal suspend fun tileUrl(z: Int, x: Int, y: Int): String {
            val base = template ?: run {
                val body = httpRequest("https://tiles.openfreemap.org/planet").body
                json.parseToJsonElement(body).jsonObject.getValue("tiles").jsonArray[0].jsonPrimitive.content.also { template = it }
            }
            return base.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        }

        private fun lonToTile(lon: Double, n: Int): Int = floor((lon + 180.0) / 360.0 * n).toInt()

        private fun latToTile(lat: Double, n: Int): Int =
            floor((1.0 - asinh(tan(lat * PI / 180.0)) / PI) / 2.0 * n).toInt()
    }
}
