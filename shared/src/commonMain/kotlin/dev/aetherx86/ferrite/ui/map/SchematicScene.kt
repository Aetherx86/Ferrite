package dev.aetherx86.ferrite.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.data.TransitNetwork
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A placed station label. Its box is relative to the station, in pixels, and stays the same size at any zoom. */
class SceneLabel(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** The zoom from which this label can show without touching a more important one. */
    var minScale: Float = 0f
}

class SceneStation(val station: Station, val x: Float, val y: Float, val interchange: Boolean, val terminus: Boolean, val label: SceneLabel)

class SceneLine(val line: Line, val color: Color, val paths: List<Path>)

/** Everything needed to draw one region's schematic, worked out once. */
class SchematicScene(
    val width: Float,
    val height: Float,
    val lines: List<SceneLine>,
    val stations: List<SceneStation>,
    val maxLabelScale: Float,
) {
    companion object {
        /** How far apart lines run when they share track, in grid cells. */
        const val LINE_GAP = 0.34f
        const val CORNER = 0.75f

        fun build(network: TransitNetwork, regionId: String, labelPx: Float, density: Float): SchematicScene {
            val region = network.regions.first { it.id == regionId }
            val runs = network.runs[regionId].orEmpty()
            val margin = 3f
            val paths = HashMap<String, MutableList<Path>>()
            val order = network.lines.withIndex().associate { (i, l) -> l.id to i }
            for (run in runs) {
                val points = ArrayList<Pair<Float, Float>>(run.path.size / 2)
                var i = 0
                while (i + 1 < run.path.size) {
                    points += (run.path[i] + margin) to (run.path[i + 1] + margin)
                    i += 2
                }
                val sorted = run.lines.sortedBy { order[it.id] ?: 0 }
                sorted.forEachIndexed { index, line ->
                    val shift = (index - (sorted.size - 1) / 2f) * LINE_GAP
                    paths.getOrPut(line.id) { mutableListOf() }.add(rounded(offset(points, shift)))
                }
            }
            val sceneLines = network.lines.filter { it.region == regionId && it.id in paths }.map {
                SceneLine(it, Color(it.colorArgb), paths.getValue(it.id))
            }

            val inRegion = network.stations.filter { it.region == regionId }
            val gap = 7f * density
            val height = labelPx * 1.25f
            val placed = inRegion.map { station ->
                val x = station.mapX + margin
                val y = station.mapY + margin
                val width = textWidth(station.name, labelPx)
                val hopCount = station.lines.sumOf { network.neighbours(it.id, station.id).size }
                val terminus = station.lines.any { network.neighbours(it.id, station.id).size == 1 }
                val directions = station.lines.flatMap { line -> network.neighbours(line.id, station.id) }
                    .map { atan2(it.mapY - station.mapY, it.mapX - station.mapX) }
                SceneStation(
                    station, x, y,
                    interchange = station.lines.size > 1,
                    terminus = terminus && hopCount <= 2,
                    label = placeLabel(directions, width, height, gap),
                )
            }

            // More important names claim their space first, and the rest appear as room opens up.
            val priority = placed.sortedWith(
                compareByDescending<SceneStation> { it.interchange }.thenByDescending { it.terminus }.thenByDescending { it.station.lines.size },
            )
            var maxLabel = 0f
            for (i in priority.indices) {
                val a = priority[i]
                var need = 0f
                for (j in 0 until i) {
                    val b = priority[j]
                    need = max(need, separation(a, b))
                }
                a.label.minScale = need
                maxLabel = max(maxLabel, need)
            }
            return SchematicScene(region.width + margin * 2, region.height + margin * 2, sceneLines, placed, maxLabel)
        }

        /** The smallest scale at which two labels no longer overlap. */
        private fun separation(a: SceneStation, b: SceneStation): Float {
            val pad = 2f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val sx = axisSeparation(dx, a.label.left - pad, a.label.right + pad, b.label.left, b.label.right)
            val sy = axisSeparation(dy, a.label.top - pad, a.label.bottom + pad, b.label.top, b.label.bottom)
            return min(sx, sy)
        }

        private fun axisSeparation(delta: Float, aLow: Float, aHigh: Float, bLow: Float, bHigh: Float): Float {
            // Along this axis the labels overlap while aLow - bHigh < delta * scale < aHigh - bLow.
            val low = aLow - bHigh
            val high = aHigh - bLow
            return when {
                delta > 0f -> if (high <= 0f) 0f else high / delta
                delta < 0f -> if (low >= 0f) 0f else low / delta
                low < 0f && high > 0f -> Float.MAX_VALUE
                else -> 0f
            }
        }

        /** Puts a name on the side with the most clear space between the lines. */
        private fun placeLabel(directions: List<Float>, width: Float, height: Float, gap: Float): SceneLabel {
            val candidates = listOf(
                Triple(0f, gap, -height / 2) to 0f,
                Triple(PI_F, -gap - width, -height / 2) to 0.2f,
                Triple(PI_F / 2, -width / 2, gap * 0.8f) to 0.4f,
                Triple(-PI_F / 2, -width / 2, -gap * 0.8f - height) to 0.45f,
                Triple(PI_F / 4, gap * 0.7f, gap * 0.5f) to 0.6f,
                Triple(-PI_F / 4, gap * 0.7f, -gap * 0.5f - height) to 0.6f,
                Triple(3 * PI_F / 4, -gap * 0.7f - width, gap * 0.5f) to 0.7f,
                Triple(-3 * PI_F / 4, -gap * 0.7f - width, -gap * 0.5f - height) to 0.7f,
            )
            val best = candidates.maxByOrNull { (spec, handicap) ->
                val clearance = directions.minOfOrNull { angleBetween(it, spec.first) } ?: PI_F
                clearance - handicap * 0.25f
            }!!.first
            return SceneLabel(best.second, best.third, best.second + width, best.third + height)
        }

        private fun angleBetween(a: Float, b: Float): Float {
            var d = kotlin.math.abs(a - b) % (2 * PI_F)
            if (d > PI_F) d = 2 * PI_F - d
            return d
        }

        /** Width from character classes. Hangul is nearly square, and everything else is narrower. */
        fun textWidth(text: String, px: Float): Float =
            text.sumOf { c -> (if (c.code in 0xAC00..0xD7A3) 0.92 else if (c == ' ') 0.3 else 0.6) * px.toDouble() }.toFloat() + px * 0.2f

        private const val PI_F = 3.1415927f

        private fun offset(points: List<Pair<Float, Float>>, distance: Float): List<Pair<Float, Float>> {
            if (distance == 0f) return points
            val out = ArrayList<Pair<Float, Float>>(points.size)
            for (i in points.indices) {
                val p = points[i]
                val previous = points.getOrNull(i - 1)
                val next = points.getOrNull(i + 1)
                val (t1x, t1y) = unit(previous?.let { p.first - it.first } ?: (next!!.first - p.first), previous?.let { p.second - it.second } ?: (next!!.second - p.second))
                val (t2x, t2y) = unit(next?.let { it.first - p.first } ?: t1x, next?.let { it.second - p.second } ?: t1y)
                val n1x = -t1y
                val n1y = t1x
                val n2x = -t2y
                val n2y = t2x
                val (mx, my) = unit(n1x + n2x, n1y + n2y)
                val k = distance / max(0.45f, mx * n1x + my * n1y)
                out += (p.first + mx * k) to (p.second + my * k)
            }
            return out
        }

        private fun unit(x: Float, y: Float): Pair<Float, Float> {
            val l = hypot(x, y)
            return if (l < 1e-5f) 0f to 0f else (x / l) to (y / l)
        }

        private fun rounded(points: List<Pair<Float, Float>>): Path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                val p = points[i]
                val next = points.getOrNull(i + 1)
                if (next == null) {
                    lineTo(p.first, p.second)
                } else {
                    val q = points[i - 1]
                    val inLength = hypot(p.first - q.first, p.second - q.second)
                    val outLength = hypot(next.first - p.first, next.second - p.second)
                    val r = min(CORNER, min(inLength, outLength) / 2)
                    val (ax, ay) = unit(p.first - q.first, p.second - q.second)
                    val (bx, by) = unit(next.first - p.first, next.second - p.second)
                    lineTo(p.first - ax * r, p.second - ay * r)
                    quadraticTo(p.first, p.second, p.first + bx * r, p.second + by * r)
                }
            }
        }
    }
}
