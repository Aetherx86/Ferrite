package dev.aetherx86.ferrite.ui.board

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.aetherx86.ferrite.core.KstTime
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.CarLoad
import dev.aetherx86.ferrite.live.FeedSource
import dev.aetherx86.ferrite.live.LoadLevel
import dev.aetherx86.ferrite.ui.common.FitText
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.contrastOn
import dev.aetherx86.ferrite.ui.common.textStyle

@Composable
fun BoardHeader(path: BoardPath, dims: BoardDims, scheme: BoardScheme, lineColor: Color, time: KstTime) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val badge = if (landscape) 48f else 36f
        Box(
            Modifier.size(dims.d(badge)).background(if (scheme.flood) scheme.text else lineColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FitText(
                path.line.short,
                maxSize = dims.t(badge * 0.58f),
                color = if (scheme.flood) lineColor else contrastOn(lineColor),
                modifier = Modifier.padding(horizontal = dims.d(badge * 0.1f)),
                alignment = Alignment.Center,
                weight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(dims.d(if (landscape) 15f else 9f)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lineWidth = if (landscape) 148f else 74f
                FitText(
                    path.line.name,
                    maxSize = dims.t(if (landscape) 28f else 15f),
                    color = scheme.text,
                    weight = FontWeight.Bold,
                    modifier = if (path.line.name.length > 4) Modifier.width(dims.d(lineWidth)) else Modifier,
                )
                Spacer(Modifier.width(dims.d(if (landscape) 16f else 8f)))
                Box(Modifier.width(dims.d(1f)).height(dims.d(if (landscape) 32f else 18f)).background(scheme.rule))
                Spacer(Modifier.width(dims.d(if (landscape) 16f else 8f)))
                FitText(
                    path.here.name,
                    maxSize = dims.t(if (landscape) 30f else 19f),
                    color = scheme.text,
                    weight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                path.transfers.take(if (landscape) 4 else 3).forEach { line ->
                    Spacer(Modifier.width(dims.d(if (landscape) 8f else 4f)))
                    TransferBadge(line, dims, if (landscape) 25f else 17f, scheme)
                }
                if (landscape) {
                    Spacer(Modifier.width(dims.d(12f)))
                    FitText(path.here.nameEn, maxSize = dims.t(15f), color = scheme.dim, weight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (!landscape) {
                Spacer(Modifier.height(dims.d(1f)))
                FitText(path.here.nameEn, maxSize = dims.t(10.5f), color = scheme.dim, weight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.width(dims.d(if (landscape) 18f else 8f)))
        ClockBlock(dims, scheme, time)
    }
}

@Composable
private fun TransferBadge(line: Line, dims: BoardDims, size: Float, scheme: BoardScheme) {
    val color = Color(line.colorArgb)
    Box(
        Modifier.size(dims.d(size)).background(color, CircleShape)
            .then(if (scheme.flood) Modifier.border(dims.d(1f), scheme.text, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            line.short,
            maxSize = dims.t(size * 0.6f),
            color = contrastOn(color),
            weight = FontWeight.Bold,
            alignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = dims.d(size * 0.08f)),
        )
    }
}

@Composable
private fun ClockBlock(dims: BoardDims, scheme: BoardScheme, time: KstTime) {
    val fonts = LocalFonts.current
    val date = "${time.month}월 ${time.day}일 ${time.weekdayName}요일"
    val clock = "${two(time.hour)}:${two(time.minute)}"
    if (dims.landscape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(date, style = textStyle(fonts.sans, dims.t(17f), FontWeight.Medium, scheme.dim))
            Spacer(Modifier.width(dims.d(24f)))
            BasicText(clock, style = textStyle(fonts.sans, dims.t(39f), FontWeight.Black, scheme.text, tight = true))
            Spacer(Modifier.width(dims.d(5f)))
            BasicText(two(time.second), Modifier.padding(bottom = dims.d(16f)), style = textStyle(fonts.sans, dims.t(16f), FontWeight.Medium, scheme.dim, tight = true))
        }
    } else {
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Top) {
                BasicText(clock, style = textStyle(fonts.sans, dims.t(24f), FontWeight.Black, scheme.text, tight = true))
                Spacer(Modifier.width(dims.d(2f)))
                BasicText(two(time.second), style = textStyle(fonts.sans, dims.t(10f), FontWeight.Medium, scheme.dim, tight = true))
            }
            Spacer(Modifier.height(dims.d(3f)))
            BasicText(date, style = textStyle(fonts.sans, dims.t(9.5f), FontWeight.Medium, scheme.dim), maxLines = 1)
        }
    }
}

@Composable
fun StatusPill(text: String, dims: BoardDims, scheme: BoardScheme) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    Row(
        Modifier
            .border(dims.d(1.5f), scheme.accent, RoundedCornerShape(dims.d(50f)))
            .padding(horizontal = dims.d(if (landscape) 16f else 11f), vertical = dims.d(if (landscape) 7f else 4.5f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(dims.d(if (landscape) 9f else 6.5f)).background(scheme.accent, CircleShape))
        Spacer(Modifier.width(dims.d(if (landscape) 13f else 7f)))
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "pill",
        ) { value ->
            BasicText(value, style = textStyle(fonts.sans, dims.t(if (landscape) 18f else 12.5f), FontWeight.Bold, scheme.accent), maxLines = 1)
        }
    }
}

/** The big number, which rolls when it changes. */
@Composable
fun Countdown(value: String, unit: String, urgent: Boolean, dims: BoardDims, scheme: BoardScheme, modifier: Modifier = Modifier) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    val numberSize = if (landscape) 192f else 118f
    val unitSize = if (landscape) 60f else 36f
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(220)) { -it / 5 } + fadeOut(tween(180)))
            },
            contentAlignment = Alignment.BottomEnd,
            label = "countdown",
        ) { shown ->
            BasicText(
                shown,
                style = textStyle(fonts.sans, dims.t(numberSize), FontWeight.Black, if (urgent) scheme.accent else scheme.text, tight = true, tracking = dims.t(numberSize * -0.03f)),
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.width(dims.d(if (landscape) 6f else 3f)))
        BasicText(unit, style = textStyle(fonts.sans, dims.t(unitSize), FontWeight.Black, scheme.accent, tight = true), maxLines = 1, softWrap = false)
    }
}

@Composable
fun SectionLabel(source: FeedSource, dims: BoardDims, scheme: BoardScheme) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    val (title, tag) = when (source) {
        FeedSource.LIVE -> "실시간 열차 위치" to "LIVE"
        FeedSource.ESTIMATE -> "열차 위치 추정" to "추정"
        FeedSource.DEMO -> "시연 화면" to "DEMO"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = textStyle(fonts.sans, dims.t(if (landscape) 16f else 11.5f), FontWeight.Medium, scheme.dim))
        Spacer(Modifier.width(dims.d(if (landscape) 10f else 6f)))
        Box(
            Modifier.background(if (source == FeedSource.LIVE) scheme.accent.copy(alpha = 0.62f) else scheme.dim.copy(alpha = 0.32f), RoundedCornerShape(dims.d(2.5f)))
                .padding(horizontal = dims.d(if (landscape) 7f else 5f), vertical = dims.d(if (landscape) 2.5f else 1.5f)),
        ) {
            BasicText(
                tag,
                style = textStyle(if (tag == "추정") fonts.sans else fonts.mono, dims.t(if (landscape) 11.5f else 8.5f), FontWeight.Bold, if (source == FeedSource.LIVE) Palette.Text else scheme.soft, tight = true),
            )
        }
    }
}

@Composable
fun CarsHeader(dims: BoardDims, scheme: BoardScheme, onlyEstimate: Boolean, roomy: List<Int>?) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    val levels = listOf(LoadLevel.ROOMY to Palette.Roomy, LoadLevel.MODERATE to Palette.Moderate, LoadLevel.CROWDED to Palette.Crowded)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (roomy != null) {
            BasicText("여유 있는 칸", style = textStyle(fonts.sans, dims.t(if (landscape) 16f else 11.5f), FontWeight.Bold, scheme.text))
            Spacer(Modifier.width(dims.d(if (landscape) 12f else 7f)))
            Box(
                Modifier.background(Palette.Ink, RoundedCornerShape(dims.d(4f)))
                    .padding(horizontal = dims.d(if (landscape) 10f else 7f), vertical = dims.d(if (landscape) 4f else 2.5f)),
            ) {
                BasicText(
                    if (roomy.isEmpty()) "없음" else roomy.joinToString(" · "),
                    style = textStyle(fonts.mono, dims.t(if (landscape) 17f else 12f), FontWeight.Bold, Palette.Roomy, tight = true),
                    maxLines = 1,
                )
            }
        } else {
            BasicText("칸별 혼잡도", style = textStyle(fonts.sans, dims.t(if (landscape) 17f else 12f), FontWeight.Bold, scheme.dim))
            Spacer(Modifier.width(dims.d(if (landscape) 12f else 6f)))
            BasicText("진행 방향 →", style = textStyle(fonts.sans, dims.t(if (landscape) 12f else 9f), FontWeight.Medium, scheme.faint))
        }
        if (onlyEstimate) {
            Spacer(Modifier.width(dims.d(if (landscape) 10f else 5f)))
            Box(
                Modifier.background(scheme.dim.copy(alpha = 0.28f), RoundedCornerShape(dims.d(2.5f)))
                    .padding(horizontal = dims.d(if (landscape) 6f else 4f), vertical = dims.d(1.5f)),
            ) {
                BasicText("통계 추정", style = textStyle(fonts.sans, dims.t(if (landscape) 11f else 8f), FontWeight.Bold, scheme.soft, tight = true), maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        levels.forEach { (level, color) ->
            Spacer(Modifier.width(dims.d(if (landscape) 18f else 7f)))
            Box(Modifier.size(dims.d(if (landscape) 10f else 7f)).background(color, RoundedCornerShape(dims.d(2f))))
            Spacer(Modifier.width(dims.d(if (landscape) 6f else 3f)))
            BasicText(level.label, style = textStyle(fonts.sans, dims.t(if (landscape) 13.5f else 9.5f), FontWeight.Medium, scheme.dim), maxLines = 1)
        }
    }
}

/** The train drawn as a row of cars, nose to the right, so it reads in the direction of travel. */
@Composable
fun CarRow(cars: List<CarLoad>, dims: BoardDims, scheme: BoardScheme, highlightRoomy: Boolean) {
    val landscape = dims.landscape
    val gap = dims.d(if (landscape) 7f else 2.5f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        cars.sortedByDescending { it.car }.forEach { car ->
            CarCell(car, dims, scheme, isNose = car.car == 1, highlight = highlightRoomy && car.level == LoadLevel.ROOMY, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CarCell(car: CarLoad, dims: BoardDims, scheme: BoardScheme, isNose: Boolean, highlight: Boolean, modifier: Modifier) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    val (fill, deep) = when (car.level) {
        LoadLevel.ROOMY -> Palette.Roomy to Palette.RoomyDeep
        LoadLevel.MODERATE -> Palette.Moderate to Palette.ModerateDeep
        LoadLevel.CROWDED -> Palette.Crowded to Palette.CrowdedDeep
    }
    val fraction by animateFloatAsState((car.percent / 130f).coerceIn(0.03f, 1f), tween(900, easing = FastOutSlowInEasing), label = "load")
    val corner = dims.d(if (landscape) 9f else 5f)
    val nose = dims.d(if (landscape) 32f else 14f)
    val shape = if (isNose) RoundedCornerShape(corner, nose, nose, corner) else RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(deep)
            .then(if (highlight) Modifier.border(dims.d(if (landscape) 2.5f else 1.5f), Color.White, shape) else Modifier)
            .semantics { contentDescription = "${car.car}번째 칸 ${car.level.label}" }
            .padding(
                start = dims.d(if (landscape) 11f else 3.5f),
                end = dims.d(if (landscape) (if (isNose) 22f else 11f) else (if (isNose) 7f else 3.5f)),
                top = dims.d(if (landscape) 9f else 5f),
                bottom = dims.d(if (landscape) 10f else 6f),
            ),
    ) {
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(car.car.toString(), style = textStyle(fonts.mono, dims.t(13f), FontWeight.Bold, Palette.TextSoft, tight = true))
                Spacer(Modifier.weight(1f))
                BasicText(car.level.label, style = textStyle(fonts.sans, dims.t(13f), FontWeight.Bold, fill, tight = true), maxLines = 1)
            }
            Spacer(Modifier.height(dims.d(9f)))
        } else {
            BasicText(car.car.toString(), style = textStyle(fonts.mono, dims.t(10f), FontWeight.Bold, Palette.TextSoft, tight = true), maxLines = 1)
            Spacer(Modifier.height(dims.d(3f)))
            BasicText(car.level.label, style = textStyle(fonts.sans, dims.t(9f), FontWeight.Bold, fill, tight = true), maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
            Spacer(Modifier.height(dims.d(5f)))
        }
        Canvas(Modifier.fillMaxWidth().height(dims.d(if (landscape) 5f else 3f))) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = radius)
            drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height), cornerRadius = radius)
        }
    }
}

