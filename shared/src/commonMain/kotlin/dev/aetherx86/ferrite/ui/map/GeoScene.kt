package dev.aetherx86.ferrite.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.data.GeoMapData
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.MapShape
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.data.TransitNetwork
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class GeoLabel(val text: String, val x: Float, val y: Float, val level: Int, val halfWidth: Float, val halfHeight: Float) {
    var minScale = 0f
}

class GeoStationMark(val station: Station, val x: Float, val y: Float)

/** The whole country, in map units of about a kilometre. */
class GeoScene(
    val width: Float,
    val height: Float,
    val land: Path,
    val provinceBorders: Path,
    val municipalBorders: Path,
    val intercity: Path,
    val tracks: List<Triple<Line, Color, Path>>,
    val stations: List<GeoStationMark>,
    val labels: List<GeoLabel>,
    private val project: (Float, Float) -> Pair<Float, Float>,
) {
    fun positionOf(point: LatLng): Pair<Float, Float> = project(point.lon.toFloat(), point.lat.toFloat())

    /** The same projection, for the street map. */
    fun projectPoint(lon: Double, lat: Double): Pair<Float, Float> = ((lon - WEST) * KM_PER_LON).toFloat() to ((NORTH - lat) * KM_PER_LAT).toFloat()

    fun lonAt(x: Float): Double = x / KM_PER_LON.toDouble() + WEST

    fun latAt(y: Float): Double = NORTH - y / KM_PER_LAT.toDouble()

    companion object {
        private const val WEST = 124.4f
        private const val EAST = 131.2f
        private const val NORTH = 38.75f
        private const val SOUTH = 32.95f
        private const val KM_PER_LAT = 110.95f
        private val KM_PER_LON = (111.32 * cos(36.0 * PI / 180)).toFloat()

        fun build(geo: GeoMapData, network: TransitNetwork, labelPx: Float): GeoScene {
            val project = { lon: Float, lat: Float -> ((lon - WEST) * KM_PER_LON) to ((NORTH - lat) * KM_PER_LAT) }
            fun outline(shapes: List<MapShape>): Path = Path().apply {
                for (shape in shapes) for (ring in shape.rings) addPoints(ring, project, close = true)
            }
            val land = outline(geo.provinces)
            val provinces = outline(geo.provinces)
            val municipalities = outline(geo.municipalities)
            val intercity = Path().apply { for (run in geo.rail) addPoints(run, project, close = false) }
            val tracks = network.lines.mapNotNull { line ->
                val runs = geo.tracks[line.id] ?: return@mapNotNull null
                val path = Path().apply { for (run in runs) addPoints(run, project, close = false) }
                Triple(line, Color(line.colorArgb), path)
            }
            val stations = network.stations.map {
                val (x, y) = project(it.location.lon.toFloat(), it.location.lat.toFloat())
                GeoStationMark(it, x, y)
            }

            val labels = ArrayList<GeoLabel>()
            for (shape in geo.provinces) labels += centerLabel(shape, 0, labelPx * 1.15f, project)
            for (shape in geo.municipalities) labels += centerLabel(shape, 1, labelPx * 0.95f, project)
            for (mark in stations.sortedByDescending { it.station.lines.size }) {
                val w = SchematicScene.textWidth(mark.station.name, labelPx) / 2
                labels += GeoLabel(mark.station.name, mark.x, mark.y, 2, w, labelPx * 0.62f)
            }
            for (i in labels.indices) {
                val a = labels[i]
                var need = LEVEL_FLOOR[a.level]
                for (j in 0 until i) {
                    val b = labels[j]
                    // The offset beside a station mark is small next to the distances involved, so it is left out.
                    val sx = apart(b.x - a.x, a.halfWidth + b.halfWidth + 3f)
                    val sy = apart(b.y - a.y, a.halfHeight + b.halfHeight + 1f)
                    need = max(need, min(sx, sy))
                }
                a.minScale = need
            }
            return GeoScene((EAST - WEST) * KM_PER_LON, (NORTH - SOUTH) * KM_PER_LAT, land, provinces, municipalities, intercity, tracks, stations, labels, project)
        }

        /** Below these, a kind of label would only clutter the map. In pixels per kilometre. */
        private val LEVEL_FLOOR = floatArrayOf(1f, 7f, 16f)

        private fun apart(delta: Float, reach: Float): Float {
            val d = kotlin.math.abs(delta)
            return if (d < 1e-4f) Float.MAX_VALUE else reach / d
        }

        private fun centerLabel(shape: MapShape, level: Int, px: Float, project: (Float, Float) -> Pair<Float, Float>): GeoLabel {
            val ring = shape.rings.maxByOrNull { it.size }!!
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < ring.size) {
                minX = min(minX, ring[i]); maxX = max(maxX, ring[i])
                minY = min(minY, ring[i + 1]); maxY = max(maxY, ring[i + 1])
                i += 2
            }
            val (x, y) = project((minX + maxX) / 2, (minY + maxY) / 2)
            val name = shortName(shape.name)
            return GeoLabel(name, x, y, level, SchematicScene.textWidth(name, px) / 2, px * 0.62f)
        }

        private fun shortName(name: String): String = when {
            name.endsWith("특별자치시") -> name.removeSuffix("특별자치시")
            name.endsWith("특별자치도") -> name.removeSuffix("특별자치도")
            name.endsWith("특별시") -> name.removeSuffix("특별시")
            name.endsWith("광역시") -> name.removeSuffix("광역시")
            name == "경기도" -> "경기"
            name == "강원도" -> "강원"
            name == "충청북도" -> "충북"
            name == "충청남도" -> "충남"
            name == "전라북도" -> "전북"
            name == "전라남도" -> "전남"
            name == "경상북도" -> "경북"
            name == "경상남도" -> "경남"
            else -> name
        }

        private fun Path.addPoints(points: FloatArray, project: (Float, Float) -> Pair<Float, Float>, close: Boolean) {
            var i = 0
            while (i + 1 < points.size) {
                val (x, y) = project(points[i], points[i + 1])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
                i += 2
            }
            if (close) close()
        }
    }
}
