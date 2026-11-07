package dev.aetherx86.ferrite.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.live.BoardFrame
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.TrainMark
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.contrastOn
import dev.aetherx86.ferrite.ui.common.textStyle
import kotlin.math.min

/** The strip of line at the bottom of a board, with trains sliding along it. All of it draws from one clock. */
@Composable
fun LineStrip(
    path: BoardPath,
    dims: BoardDims,
    scheme: BoardScheme,
    lineColor: Color,
    frame: () -> BoardFrame,
    timeMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer()
    val description = remember(path) {
        val before = path.upstream.reversed().joinToString(", ") { it.name }
        "${path.line.name} 노선. $before 다음이 ${path.here.name}, 그다음 ${path.next?.name ?: "종점"}"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val layout = StripLayout(path, dims, size.width, size.height)
        val time = timeMs()
        val current = frame()
        if (!scheme.flood) {
            current.trains.forEach { drawBeam(it, layout, dims, scheme, lineColor) }
        }
        drawTrack(layout, dims, scheme, time, lineColor)
        current.trains.filter { it.moving }.forEach { drawSweep(it, layout, dims, scheme) }
        drawStations(path, layout, dims, scheme, lineColor, time, measurer, fonts.sans)
        current.trains.sortedBy { it.primary }.forEach { drawTrain(it, layout, dims, scheme, lineColor, measurer, fonts.mono) }
    }
}

/** Where things go across the strip. */
class StripLayout(path: BoardPath, dims: BoardDims, val width: Float, val height: Float) {
    val stations = path.upstream.reversed() + path.here + listOfNotNull(path.next)
    val hereIndex = path.upstream.size
    private val edge = width * (if (dims.landscape) 0.052f else 0.085f)
    val spacing = if (stations.size > 1) (width - edge * 2) / (stations.size - 1) else width
    val trackY = dims.px(if (dims.landscape) 62f else 58f)
    val hereX = xAt(0f)

    fun xAt(position: Float): Float = edge + (hereIndex + position) * spacing
}

private fun DrawScope.drawTrack(layout: StripLayout, dims: BoardDims, scheme: BoardScheme, time: Long, lineColor: Color) {
    val y = layout.trackY
    val thick = dims.px(if (dims.landscape) 7f else 5f)
    drawLine(scheme.trackAhead, Offset(layout.hereX, y), Offset(layout.width, y), thick, StrokeCap.Round)
    drawLine(scheme.track, Offset(0f, y), Offset(layout.hereX, y), thick, StrokeCap.Round)
    val gap = dims.px(if (dims.landscape) 31f else 20f)
    val arm = dims.px(if (dims.landscape) 4.2f else 3f)
    val stroke = dims.px(if (dims.landscape) 2f else 1.5f)
    val shift = ((time % CHEVRON_PERIOD_MS) / CHEVRON_PERIOD_MS.toFloat()) * gap
    var x = -gap + shift
    while (x < layout.width + gap) {
        val color = if (x < layout.hereX) scheme.chevron else scheme.chevronAhead
        val path = Path().apply {
            moveTo(x - arm * 0.6f, y - arm)
            lineTo(x + arm * 0.6f, y)
            lineTo(x - arm * 0.6f, y + arm)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        x += gap
    }
}

private fun DrawScope.drawBeam(mark: TrainMark, layout: StripLayout, dims: BoardDims, scheme: BoardScheme, lineColor: Color) {
    val metrics = TrainMetrics(dims, layout)
    val center = layout.xAt(mark.position)
    val nose = center + metrics.width / 2
    val midY = metrics.top + metrics.height / 2
    val reach = dims.px(if (dims.landscape) 185f else 86f)
    val path = Path().apply {
        moveTo(nose - dims.px(1f), midY - metrics.height * 0.14f)
        lineTo(nose + reach, metrics.top - dims.px(if (dims.landscape) 24f else 12f))
        lineTo(nose + reach, layout.trackY - dims.px(2f))
        lineTo(nose + reach * 0.74f, layout.trackY - dims.px(2f))
        lineTo(nose - dims.px(1f), midY + metrics.height * 0.22f)
        close()
    }
    val strength = if (mark.primary) 0.30f else 0.15f
    drawPath(
        path,
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = strength),
            1f to Color.White.copy(alpha = 0f),
            startX = nose,
            endX = nose + reach,
        ),
    )
}

/** A brighter stretch of track under a moving train. */
private fun DrawScope.drawSweep(mark: TrainMark, layout: StripLayout, dims: BoardDims, scheme: BoardScheme) {
    if (scheme.flood) return
    val metrics = TrainMetrics(dims, layout)
    val center = layout.xAt(mark.position)
    val nose = center + metrics.width / 2
    val tail = nose - dims.px(if (dims.landscape) 150f else 70f)
    val thick = dims.px(if (dims.landscape) 7f else 5f)
    drawLine(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0f),
            1f to Color.White.copy(alpha = if (mark.primary) 0.85f else 0.3f),
            startX = tail,
            endX = nose,
        ),
        Offset(tail, layout.trackY),
        Offset(nose, layout.trackY),
        thick,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawStations(
    path: BoardPath,
    layout: StripLayout,
    dims: BoardDims,
    scheme: BoardScheme,
    lineColor: Color,
    time: Long,
    measurer: TextMeasurer,
    family: androidx.compose.ui.text.font.FontFamily,
) {
    val y = layout.trackY
    val landscape = dims.landscape
    val ringColor = scheme.track
    layout.stations.forEachIndexed { index, station ->
        val x = layout.xAt((index - layout.hereIndex).toFloat())
        val isHere = station.id == path.here.id && index == layout.hereIndex
        val isAhead = index > layout.hereIndex
        if (isHere) {
            if (!scheme.flood) {
                val base = dims.px(if (landscape) 22f else 15f)
                val reach = dims.px(if (landscape) 46f else 26f)
                for (i in 0 until PULSE_RINGS) {
                    val phase = ((time / PULSE_PERIOD_MS.toFloat()) + i / PULSE_RINGS.toFloat()) % 1f
                    drawCircle(
                        ringColor.copy(alpha = (1f - phase) * 0.5f),
                        base + phase * reach,
                        Offset(x, y),
                        style = Stroke(dims.px(1.4f)),
                    )
                }
            }
            val outer = dims.px(if (landscape) 21f else 14f)
            drawCircle(scheme.stationFill, outer, Offset(x, y))
            drawCircle(ringColor, outer, Offset(x, y), style = Stroke(dims.px(if (landscape) 7f else 4.5f)))
            drawCircle(if (scheme.flood) ringColor else Color.White, dims.px(if (landscape) 8f else 5.5f), Offset(x, y))
        } else {
            val ring = dims.px(if (landscape) 10f else 6.5f)
            val color = if (isAhead) scheme.trackAhead else ringColor
            drawCircle(scheme.stationFill, ring, Offset(x, y))
            drawCircle(color, ring, Offset(x, y), style = Stroke(dims.px(if (landscape) 3.4f else 2.4f)))
        }
        val nameColor = when {
            isHere -> if (scheme.flood) scheme.text else scheme.accent
            isAhead -> scheme.faint
            else -> scheme.text
        }
        val enColor = when {
            isHere -> scheme.soft
            isAhead -> scheme.faint
            else -> scheme.dim
        }
        val maxWidth = (layout.spacing - dims.px(if (landscape) 16f else 3f)).toInt().coerceAtLeast(1)
        val name = measureLabel(measurer, station.name, family, if (isHere) (if (landscape) 27f else 14.5f) else (if (landscape) 21f else 12f), FontWeight.Bold, nameColor, maxWidth, dims, if (landscape) 1 else 2)
        val top = y + dims.px(if (landscape) 30f else 21f)
        val badges = if (isHere) path.transfers.take(if (landscape) 3 else 2) else emptyList()
        val badgeSize = dims.px(if (landscape) 24f else 13f)
        val badgeGap = dims.px(if (landscape) 6f else 2.5f)
        val badgeTotal = badges.size * (badgeSize + badgeGap)
        val nameLeft = (x - (name.size.width + badgeTotal) / 2).coerceIn(0f, (layout.width - name.size.width - badgeTotal).coerceAtLeast(0f))
        drawText(name, topLeft = Offset(nameLeft, top))
        badges.forEachIndexed { i, line ->
            val cx = nameLeft + name.size.width + badgeGap + badgeSize / 2 + i * (badgeSize + badgeGap)
            drawLineBadge(line, Offset(cx, top + name.size.height / 2f), badgeSize, dims, measurer, family, scheme)
        }
        val english = measureLabel(measurer, station.nameEn, family, if (landscape) 13f else 8.5f, FontWeight.Medium, enColor, maxWidth, dims, if (landscape) 1 else 2)
        val enTop = top + name.size.height + dims.px(if (landscape) 3f else 1f)
        val enLeft = (x - english.size.width / 2).coerceIn(0f, (layout.width - english.size.width).coerceAtLeast(0f))
        drawText(english, topLeft = Offset(enLeft, enTop))
    }
}

private fun DrawScope.drawLineBadge(
    line: Line,
    center: Offset,
    size: Float,
    dims: BoardDims,
    measurer: TextMeasurer,
    family: androidx.compose.ui.text.font.FontFamily,
    scheme: BoardScheme,
) {
    val color = Color(line.colorArgb)
    drawCircle(color, size / 2, center)
    if (scheme.flood) drawCircle(scheme.text, size / 2, center, style = Stroke(dims.px(1f)))
    val fontPx = if (line.short.length > 1) size * 0.40f else size * 0.62f
    val layout = measurer.measure(
        text = line.short,
        style = textStyle(family, with(this) { fontPx.toSp() }, FontWeight.Bold, contrastOn(color), TextAlign.Center, tight = true),
        maxLines = 1,
        softWrap = false,
    )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun DrawScope.measureLabel(
    measurer: TextMeasurer,
    text: String,
    family: androidx.compose.ui.text.font.FontFamily,
    designSize: Float,
    weight: FontWeight,
    color: Color,
    maxWidth: Int,
    dims: BoardDims,
    maxLines: Int,
): TextLayoutResult = measurer.measure(
    text = text,
    style = textStyle(family, with(this) { dims.px(designSize).toSp() }, weight, color, TextAlign.Center),
    constraints = Constraints(maxWidth = maxWidth),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    softWrap = maxLines > 1,
)

private class TrainMetrics(dims: BoardDims, layout: StripLayout) {
    val width = dims.px(if (dims.landscape) 110f else 58f)
    val height = dims.px(if (dims.landscape) 34f else 21f)
    val top = layout.trackY - dims.px(if (dims.landscape) 15f else 9.5f) - height
}

private fun DrawScope.drawTrain(
    mark: TrainMark,
    layout: StripLayout,
    dims: BoardDims,
    scheme: BoardScheme,
    lineColor: Color,
    measurer: TextMeasurer,
    mono: androidx.compose.ui.text.font.FontFamily,
) {
    val metrics = TrainMetrics(dims, layout)
    val w = metrics.width
    val h = metrics.height
    val left = layout.xAt(mark.position) - w / 2
    val top = metrics.top
    val radius = h * 0.16f
    val body = Path().apply {
        moveTo(left + radius, top)
        lineTo(left + w * 0.76f, top)
        cubicTo(left + w * 0.90f, top, left + w * 0.985f, top + h * 0.42f, left + w, top + h * 0.72f)
        cubicTo(left + w * 1.0f, top + h * 0.92f, left + w * 0.97f, top + h, left + w * 0.92f, top + h)
        lineTo(left + radius, top + h)
        quadraticTo(left, top + h, left, top + h - radius)
        lineTo(left, top + radius)
        quadraticTo(left, top, left + radius, top)
        close()
    }
    val bodyColor = if (mark.primary) scheme.trainBody else scheme.trainOther
    val numberColor = if (mark.primary) scheme.trainNumber else scheme.trainOtherNumber

    if (mark.moving && !scheme.flood) {
        val speedColor = Color.White.copy(alpha = if (mark.primary) 0.42f else 0.2f)
        val thin = dims.px(1.1f)
        val rear = left - dims.px(if (dims.landscape) 7f else 3f)
        drawLine(speedColor, Offset(rear - dims.px(if (dims.landscape) 60f else 26f), top + h * 0.18f), Offset(rear, top + h * 0.18f), thin)
        drawLine(speedColor, Offset(rear - dims.px(if (dims.landscape) 96f else 40f), top + h * 0.52f), Offset(rear, top + h * 0.52f), thin)
        drawLine(speedColor, Offset(rear - dims.px(if (dims.landscape) 42f else 20f), top + h * 0.86f), Offset(rear, top + h * 0.86f), thin)
    }

    drawPath(body, bodyColor)
    // Windshield, following the slope of the nose.
    val window = Path().apply {
        moveTo(left + w * 0.77f, top + h * 0.14f)
        cubicTo(left + w * 0.84f, top + h * 0.14f, left + w * 0.905f, top + h * 0.28f, left + w * 0.945f, top + h * 0.47f)
        lineTo(left + w * 0.77f, top + h * 0.47f)
        close()
    }
    drawPath(window, scheme.trainWindow)
    val stripeY = top + h * 0.82f
    drawLine(
        if (scheme.flood) scheme.stripe else lineColor,
        Offset(left + w * 0.06f, stripeY),
        Offset(left + w * 0.72f, stripeY),
        dims.px(if (dims.landscape) 2.6f else 1.6f),
        StrokeCap.Round,
    )
    if (scheme.flood) {
        drawCircle(Color.White, dims.px(if (dims.landscape) 2.6f else 1.6f), Offset(left + w * 0.985f, top + h * 0.74f))
    }
    mark.trainNumber?.let { number ->
        val text = measurer.measure(
            text = number,
            style = textStyle(mono, with(this) { (h * 0.46f).toSp() }, FontWeight.Bold, numberColor, tight = true),
            maxLines = 1,
            softWrap = false,
        )
        drawText(text, topLeft = Offset(left + w * 0.39f - text.size.width / 2f, top + h * 0.40f - text.size.height / 2f))
    }
}

/** The big arrow beside the destination. [drawn] from 0 to 1 draws it in from the tail, as when a new train comes up. */
fun DrawScope.drawHeroArrow(color: Color, stroke: Float, drawn: Float, nudge: Float) {
    val h = size.height
    val w = size.width
    val midY = h / 2
    val head = min(h * 0.46f, w * 0.34f)
    val end = (w - stroke) * drawn.coerceIn(0f, 1f)
    val x = nudge * w * 0.03f
    if (end > stroke) {
        drawLine(color, Offset(stroke / 2 + x, midY), Offset(end + x, midY), stroke, StrokeCap.Round)
    }
    if (drawn > 0.55f) {
        val headProgress = ((drawn - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val tipX = w - stroke / 2
        val path = Path().apply {
            moveTo(tipX - head * headProgress + x, midY - head * headProgress)
            lineTo(tipX + x, midY)
            lineTo(tipX - head * headProgress + x, midY + head * headProgress)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The doors of a car. [open] from 0 to 1 slides the two leaves apart. */
fun DrawScope.drawDoors(ink: Color, background: Color, open: Float, stroke: Float) {
    val w = size.width
    val h = size.height
    val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.13f)
    drawRoundRect(ink, size = Size(w, h), cornerRadius = corner)
    val inset = w * 0.09f
    val opening = (w - inset * 2) * 0.5f * open.coerceIn(0f, 1f)
    val innerH = h - inset * 2
    drawRoundRect(
        background.copy(alpha = 0.9f),
        topLeft = Offset(inset, inset),
        size = Size(w - inset * 2, innerH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
    )
    val leafW = (w - inset * 2) / 2
    val leftX = inset - opening
    val rightX = w / 2 + opening
    clipDoor(inset, w - inset, inset, h - inset) {
        drawRoundRect(ink, topLeft = Offset(leftX, inset), size = Size(leafW - stroke / 4, innerH))
        drawRoundRect(ink, topLeft = Offset(rightX + stroke / 4, inset), size = Size(leafW - stroke / 4, innerH))
        val windowW = leafW * 0.46f
        val windowH = innerH * 0.32f
        val windowY = inset + innerH * 0.13f
        drawRoundRect(background, topLeft = Offset(leftX + leafW * 0.27f, windowY), size = Size(windowW, windowH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
        drawRoundRect(background, topLeft = Offset(rightX + leafW * 0.27f, windowY), size = Size(windowW, windowH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
    }
}

private inline fun DrawScope.clipDoor(left: Float, right: Float, top: Float, bottom: Float, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(left, top, right, bottom)
    block()
    drawContext.canvas.restore()
}

private const val CHEVRON_PERIOD_MS = 1100L
private const val PULSE_PERIOD_MS = 2400L
private const val PULSE_RINGS = 3

