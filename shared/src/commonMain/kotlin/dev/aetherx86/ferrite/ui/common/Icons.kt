package dev.aetherx86.ferrite.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Icon {
    BACK, CLOSE, SEARCH, STAR, STAR_FILLED, SOUND_ON, SOUND_OFF, SWAP, WALK, TRAIN, MAP, ROUTE, PLANE, BUS, SETTINGS, TARGET, SHARE, CHEVRON,
    STAIRS, CROSSING, CLOCK, ROTATE, ELEVATOR, FLAG, GLOBE, BELL,
}

/** Icons are drawn on a 24 unit grid with one stroke weight, so they all belong together. */
@Composable
fun AppIcon(icon: Icon, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        val stroke = Stroke(1.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        fun Path.m(x: Float, y: Float) = moveTo(x * scale, y * scale)
        fun Path.l(x: Float, y: Float) = lineTo(x * scale, y * scale)
        fun Path.q(cx: Float, cy: Float, x: Float, y: Float) = quadraticTo(cx * scale, cy * scale, x * scale, y * scale)
        fun Path.c(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float) = cubicTo(a * scale, b * scale, c * scale, d * scale, x * scale, y * scale)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, p(a, b), p(c, d), stroke.width, StrokeCap.Round)
        fun outline(path: Path) = drawPath(path, color, style = stroke)
        fun solid(path: Path) = drawPath(path, color)
        fun ring(x: Float, y: Float, r: Float) = drawCircle(color, r * scale, p(x, y), style = stroke)
        fun dot(x: Float, y: Float, r: Float) = drawCircle(color, r * scale, p(x, y))
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(color, p(x, y), Size(w * scale, h * scale), CornerRadius(r * scale), style = stroke)
        when (icon) {
            Icon.BACK -> outline(path { m(15f, 5f); l(8f, 12f); l(15f, 19f) })
            Icon.CHEVRON -> outline(path { m(9f, 5f); l(16f, 12f); l(9f, 19f) })
            Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Icon.SEARCH -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20.5f, 20.5f) }
            Icon.STAR, Icon.STAR_FILLED -> {
                val star = path {
                    m(12f, 3.2f); l(14.7f, 8.9f); l(20.9f, 9.7f); l(16.3f, 14f); l(17.6f, 20.2f)
                    l(12f, 17.1f); l(6.4f, 20.2f); l(7.7f, 14f); l(3.1f, 9.7f); l(9.3f, 8.9f); close()
                }
                if (icon == Icon.STAR) outline(star) else solid(star)
            }
            Icon.SOUND_ON, Icon.SOUND_OFF -> {
                outline(path { m(4f, 9.5f); l(7.5f, 9.5f); l(12f, 5.5f); l(12f, 18.5f); l(7.5f, 14.5f); l(4f, 14.5f); close() })
                if (icon == Icon.SOUND_ON) {
                    outline(path { m(15.5f, 9f); q(17.5f, 12f, 15.5f, 15f) })
                    outline(path { m(18f, 6.5f); q(21.6f, 12f, 18f, 17.5f) })
                } else {
                    line(15.5f, 9.5f, 20.5f, 14.5f); line(20.5f, 9.5f, 15.5f, 14.5f)
                }
            }
            Icon.SWAP -> {
                outline(path { m(7f, 4f); l(3.5f, 7.5f); l(7f, 11f) }); line(3.5f, 7.5f, 20f, 7.5f)
                outline(path { m(17f, 13f); l(20.5f, 16.5f); l(17f, 20f) }); line(20.5f, 16.5f, 4f, 16.5f)
            }
            Icon.WALK -> {
                dot(13f, 4.5f, 2f)
                outline(path { m(9f, 21f); l(11.5f, 15f); l(10.5f, 10.5f); l(14f, 8.5f); l(16f, 12.5f); l(19f, 13.5f) })
                outline(path { m(11.5f, 15f); l(15f, 17.5f); l(16f, 21f) })
                outline(path { m(10.5f, 10.5f); l(7f, 13f) })
            }
            Icon.TRAIN -> {
                box(5f, 3.5f, 14f, 14.5f, 3.5f); line(5f, 11f, 19f, 11f)
                dot(8.8f, 14.6f, 1.2f); dot(15.2f, 14.6f, 1.2f); line(8f, 18f, 5.5f, 21f); line(16f, 18f, 18.5f, 21f)
            }
            Icon.MAP -> {
                outline(path { m(3.5f, 6f); l(9f, 3.8f); l(15f, 6.2f); l(20.5f, 4f); l(20.5f, 18f); l(15f, 20.2f); l(9f, 17.8f); l(3.5f, 20f); close() })
                line(9f, 3.8f, 9f, 17.8f); line(15f, 6.2f, 15f, 20.2f)
            }
            Icon.ROUTE -> {
                ring(6.5f, 18f, 2.6f); ring(17.5f, 6f, 2.6f)
                outline(path { m(9f, 18f); l(14f, 18f); q(18f, 18f, 18f, 14f); q(18f, 11f, 14f, 11f); l(10f, 11f); q(6f, 11f, 6f, 8f); q(6f, 6f, 9f, 6f); l(15f, 6f) })
            }
            Icon.PLANE -> {
                outline(path {
                    m(12f, 2.8f); c(13.3f, 2.8f, 13.7f, 5f, 13.7f, 7f); l(13.7f, 9.5f); l(21f, 14f); l(21f, 16f); l(13.7f, 13.8f)
                    l(13.7f, 18f); l(16f, 19.8f); l(16f, 21.2f); l(12f, 20.2f); l(8f, 21.2f); l(8f, 19.8f); l(10.3f, 18f)
                    l(10.3f, 13.8f); l(3f, 16f); l(3f, 14f); l(10.3f, 9.5f); l(10.3f, 7f); c(10.3f, 5f, 10.7f, 2.8f, 12f, 2.8f); close()
                })
            }
            Icon.BUS -> {
                box(4.5f, 3.5f, 15f, 15f, 3f); line(4.5f, 10.5f, 19.5f, 10.5f); line(12f, 3.5f, 12f, 10.5f)
                dot(8.2f, 14.5f, 1.2f); dot(15.8f, 14.5f, 1.2f); line(7.5f, 18.5f, 7.5f, 20.8f); line(16.5f, 18.5f, 16.5f, 20.8f)
            }
            Icon.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                drawCircle(color, 2.5f * scale, p(9f, 7f)); drawCircle(color, 2.5f * scale, p(16f, 12f)); drawCircle(color, 2.5f * scale, p(7.5f, 17f))
            }
            Icon.TARGET -> { ring(12f, 12f, 6.2f); dot(12f, 12f, 2f); line(12f, 2.5f, 12f, 5f); line(12f, 19f, 12f, 21.5f); line(2.5f, 12f, 5f, 12f); line(19f, 12f, 21.5f, 12f) }
            Icon.SHARE -> { ring(6f, 12f, 2.5f); ring(17.5f, 5.5f, 2.5f); ring(17.5f, 18.5f, 2.5f); line(8.2f, 10.8f, 15.3f, 6.8f); line(8.2f, 13.2f, 15.3f, 17.2f) }
            Icon.STAIRS -> outline(path { m(3.5f, 20f); l(8.5f, 20f); l(8.5f, 15f); l(13.5f, 15f); l(13.5f, 10f); l(18.5f, 10f); l(18.5f, 5f); l(21f, 5f) })
            Icon.CROSSING -> { box(4f, 4f, 16f, 16f, 2f); line(8f, 4f, 8f, 20f); line(12f, 4f, 12f, 20f); line(16f, 4f, 16f, 20f) }
            Icon.CLOCK -> { ring(12f, 12f, 8.5f); outline(path { m(12f, 7f); l(12f, 12f); l(15.5f, 14f) }) }
            Icon.ROTATE -> {
                outline(path { m(19f, 12f); c(19f, 8f, 16f, 5f, 12f, 5f); c(9.5f, 5f, 7.5f, 6.1f, 6.2f, 7.8f) })
                outline(path { m(5.5f, 4f); l(6f, 8f); l(10f, 7.7f) })
                outline(path { m(5f, 12f); c(5f, 16f, 8f, 19f, 12f, 19f); c(14.5f, 19f, 16.5f, 17.9f, 17.8f, 16.2f) })
                outline(path { m(18.5f, 20f); l(18f, 16f); l(14f, 16.3f) })
            }
            Icon.ELEVATOR -> { box(5f, 3.5f, 14f, 17f, 2f); line(12f, 3.5f, 12f, 20.5f); outline(path { m(7f, 10f); l(8.8f, 8f); l(10.4f, 10f) }); outline(path { m(13.6f, 8f); l(15.2f, 10f); l(17f, 8f) }) }
            Icon.FLAG -> { line(6f, 3.5f, 6f, 21f); outline(path { m(6f, 4.5f); l(18.5f, 4.5f); l(15.5f, 9f); l(18.5f, 13.5f); l(6f, 13.5f) }) }
            Icon.GLOBE -> { ring(12f, 12f, 8.8f); line(3.4f, 12f, 20.6f, 12f); outline(path { m(12f, 3.2f); q(17f, 12f, 12f, 20.8f); q(7f, 12f, 12f, 3.2f) }) }
            Icon.BELL -> {
                outline(path { m(6f, 17f); l(6f, 11f); c(6f, 7.5f, 8.5f, 5f, 12f, 5f); c(15.5f, 5f, 18f, 7.5f, 18f, 11f); l(18f, 17f); l(19.5f, 18f); l(4.5f, 18f); close() })
                line(10.5f, 21f, 13.5f, 21f); line(12f, 3f, 12f, 5f)
            }
        }
    }
}

