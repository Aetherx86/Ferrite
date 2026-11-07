package dev.aetherx86.ferrite.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.live.ArrivalFeed
import dev.aetherx86.ferrite.live.BoardEngine
import dev.aetherx86.ferrite.live.BoardFrame
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.CarLoad
import dev.aetherx86.ferrite.live.CarLoads
import dev.aetherx86.ferrite.live.DemoFeed
import dev.aetherx86.ferrite.live.FeedSource
import dev.aetherx86.ferrite.live.LoadLevel
import dev.aetherx86.ferrite.live.Phase
import dev.aetherx86.ferrite.live.SeoulArrivals
import dev.aetherx86.ferrite.live.ServiceEstimator
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.FitText
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.contrastOn
import dev.aetherx86.ferrite.ui.common.textStyle
import dev.aetherx86.ferrite.ui.common.visibleOnDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.sin

private const val POLL_MS = 20_000L
private const val DEMO_LOAD_SCALE = 1.9f

private fun darkScheme(line: Color) = BoardScheme(
    background = null,
    text = Palette.Text,
    soft = Palette.TextSoft,
    dim = Palette.TextDim,
    faint = Palette.TextFaint,
    accent = line,
    rule = Palette.Hairline,
    track = line,
    trackAhead = line.copy(alpha = 0.26f),
    chevron = Color.White.copy(alpha = 0.88f),
    chevronAhead = Color.White.copy(alpha = 0.62f),
    stationFill = Color(0xFF0B1420),
    trainBody = Color(0xFFF4F5F7),
    trainOther = Color(0xFFBFC2C9),
    trainNumber = Color(0xFF12161E),
    trainOtherNumber = Color(0xFF242932),
    trainWindow = Color(0xFF232A36),
    stripe = line,
    flood = false,
)

private fun floodScheme(line: Color): BoardScheme {
    val ink = contrastOn(line)
    return BoardScheme(
        background = line,
        text = ink,
        soft = ink.copy(alpha = 0.72f),
        dim = ink.copy(alpha = 0.58f),
        faint = ink.copy(alpha = 0.34f),
        accent = ink,
        rule = ink.copy(alpha = 0.3f),
        track = ink,
        trackAhead = ink.copy(alpha = 0.2f),
        chevron = line,
        chevronAhead = line.copy(alpha = 0.7f),
        stationFill = line,
        trainBody = ink,
        trainOther = ink.copy(alpha = 0.72f),
        trainNumber = line,
        trainOtherNumber = line,
        trainWindow = line.copy(alpha = 0.72f),
        stripe = if (ink == Palette.Ink) Color.White else Palette.Ink,
        flood = true,
    )
}

/**
 * The board for one platform: the next train, large enough to read across a station, with the line moving under it.
 *
 * When a train arrives, the line's color spreads out from this station's marker and takes over the whole page.
 */
@Composable
fun BoardScreen(station: Station, initialLine: Line?, demo: Boolean, onBack: () -> Unit, onWalkHere: (Station) -> Unit) {
    val services = LocalServices.current
    val host = LocalHost.current
    val network = services.network
    val settings = services.settings

    var line by remember(station) { mutableStateOf(initialLine?.takeIf { it in station.lines } ?: station.lines.first()) }
    val paths = remember(line, station) { BoardPath.allFrom(network, line, station) }
    var directionIndex by remember(line, station) { mutableStateOf(0) }
    val path = paths[directionIndex.coerceIn(0, paths.lastIndex)]
    val engine = remember(path) { BoardEngine(path) }
    val lineColor = remember(line) { visibleOnDark(Color(line.colorArgb)) }
    val dark = remember(lineColor) { darkScheme(lineColor) }
    val flood = remember(line) { floodScheme(Color(line.colorArgb)) }

    val now = remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now.longValue = nowMillis() }
    }

    val useDemo = demo || settings.demoMode
    val demoFeed = remember(path, useDemo) {
        if (useDemo) DemoFeed(line, path.terminus.name, nowMillis()) else null
    }
    var liveFeed by remember(path) { mutableStateOf<ArrivalFeed?>(null) }
    var feedProblem by remember(path) { mutableStateOf<String?>(null) }
    val liveId = line.liveId
    LaunchedEffect(path, settings.seoulKey, useDemo) {
        liveFeed = null
        feedProblem = null
        if (useDemo || liveId == null || settings.seoulKey.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                val rows = SeoulArrivals.fetch(settings.seoulKey, station.name)
                val time = nowMillis()
                val trains = SeoulArrivals.approaches(rows, liveId, path.next?.name, time)
                liveFeed = if (trains.isEmpty()) null else ArrivalFeed(FeedSource.LIVE, trains, time)
                feedProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedProblem = e.message ?: "실시간 정보를 받지 못했습니다"
            }
            delay(POLL_MS)
        }
    }

    // The clock runs every frame, so all of this is read only where it is drawn, or through values that rarely change.
    val feedAt: (Long) -> ArrivalFeed = { time ->
        demoFeed?.at(time) ?: liveFeed?.takeIf { time - it.atMs < 120_000 } ?: ServiceEstimator.next(path, time)
    }
    val frameOf: () -> BoardFrame = { engine.frame(feedAt(now.longValue), now.longValue) }
    val source by remember(path, demoFeed) { derivedStateOf { feedAt(now.longValue).source } }
    val secondTick by remember { derivedStateOf { now.longValue / 1000 } }
    val time = remember(secondTick) { kstOf(secondTick * 1000) }

    val englishOf = remember(line) {
        { name: String ->
            network.stations.firstOrNull { it.name == name && line in it.lines }?.nameEn.orEmpty()
        }
    }
    val display by remember(path, demoFeed) { derivedStateOf { BoardDisplay.of(frameOf(), path, source, englishOf) } }
    val platform by remember(path, demoFeed) { derivedStateOf { platformDisplayOf(frameOf(), path, secondTick * 1000) } }
    val floodProgress = remember(path, demoFeed) { derivedStateOf { frameOf().flood } }

    fun carsFor(key: Long): List<CarLoad> {
        val up = path.next?.let { services.congestion?.isUp(line.id, station.id, it.id) }
        val cars = CarLoads.estimate(services.congestion, line.id, line.cars, station.id, up, kstOf(if (useDemo) rushHour() else nowMillis()), (key / 1000).toInt())
        // A demonstration should show every level, whatever this station is like at that hour.
        return if (useDemo) cars.map { it.copy(percent = (it.percent * DEMO_LOAD_SCALE).toInt()) } else cars
    }
    val cars = remember(display.trainKey, line) { carsFor(display.trainKey) }
    val platformCars = remember(platform?.trainKey, line) { carsFor(platform?.trainKey ?: 0L) }

    // Sounds fire once for each thing that happens to each train.
    LaunchedEffect(path, settings.soundOn, demoFeed) {
        if (!settings.soundOn) {
            host.stopSounds()
            return@LaunchedEffect
        }
        val played = HashSet<Pair<Long, BoardSound>>()
        snapshotFlow {
            val frame = frameOf()
            val train = frame.atPlatform ?: frame.focus
            val key = train?.arrivalAtMs ?: 0L
            val sound = when {
                frame.justSwitched -> BoardSound.DEPARTURE
                frame.platformPhase == Phase.CLOSING && frame.flood > 0f -> BoardSound.DOORS_CLOSING
                frame.platformPhase == Phase.ARRIVED && frame.flood > 0f -> BoardSound.ARRIVAL
                frame.phase == Phase.ENTERING || (frame.focus != null && frame.secondsToFocus in 6..8) -> BoardSound.ROLLING_IN
                frame.phase == Phase.APPROACHING -> BoardSound.APPROACH
                else -> null
            }
            key to sound
        }.collect { (key, sound) ->
            if (sound != null && played.add(key to sound)) host.play(sound)
        }
    }

    DisposableEffect(Unit) {
        host.keepScreenOn(true)
        onDispose {
            host.keepScreenOn(false)
            host.stopSounds()
        }
    }

    var holdLandscape by remember { mutableStateOf(false) }
    DisposableEffect(holdLandscape) {
        host.holdLandscape(holdLandscape)
        onDispose { if (holdLandscape) host.holdLandscape(false) }
    }

    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls) {
        if (controls) {
            delay(4500)
            controls = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ink)) {
        val density = LocalDensity.current
        val dims = remember(maxWidth, maxHeight, density) { BoardDims(maxWidth.value, maxHeight.value, density) }
        val landscape = dims.landscape

        DisposableEffect(landscape) {
            host.setFullScreen(landscape)
            onDispose { host.setFullScreen(false) }
        }

        // A soft glow in the corner, as a lit sign has.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(lineColor.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset.Zero,
                    radius = size.width * 0.62f,
                ),
            )
        }

        val page = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        Box(
            page.clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
        ) {
            WaitingPage(path, dims, dark, lineColor, display, source, time, cars, frameOf, { now.longValue }, feedProblem)
        }

        val markerAt = remember(dims) { floodOrigin(dims) }
        if (platform != null || floodProgress.value > 0f) {
            val shown = platform
            Box(
                Modifier.fillMaxSize()
                    .drawWithContent {
                        val p = floodProgress.value
                        val origin = Offset(size.width * markerAt.x, size.height * markerAt.y)
                        val reach = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                        val eased = FastOutSlowInEasing.transform(p)
                        val circle = Path().apply {
                            addOval(androidx.compose.ui.geometry.Rect(origin, eased * reach))
                        }
                        clipPath(circle) { this@drawWithContent.drawContent() }
                    }
                    .background(flood.background ?: lineColor),
            ) {
                FloodRipples(markerAt, flood) { now.longValue }
                if (shown != null) {
                    Box(page) {
                        ArrivedPage(path, dims, flood, Color(line.colorArgb), shown, source, time, platformCars, frameOf, { now.longValue }) { arrivedAt(frameOf()) }
                    }
                }
            }
        }

        // Controls stay out of the way of the board in landscape, and a tap brings them back.
        val alwaysShown = !landscape
        AnimatedVisibility(
            visible = controls || alwaysShown,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(if (landscape) Alignment.BottomCenter else Alignment.TopCenter),
        ) {
            ControlBar(
                dims = dims,
                station = station,
                line = line,
                paths = paths,
                directionIndex = directionIndex,
                soundOn = settings.soundOn,
                favorite = station.id in settings.favorites,
                onBack = onBack,
                onSound = { settings.updateSound(!settings.soundOn) },
                onFavorite = { settings.toggleFavorite(station.id) },
                onDirection = { directionIndex = (directionIndex + 1) % paths.size },
                onLine = { line = it },
                onWalk = { onWalkHere(station) },
                onRotate = { holdLandscape = !holdLandscape },
                floodActive = floodProgress.value > 0.5f,
                ink = if (floodProgress.value > 0.5f) flood.text else Palette.Text,
            )
        }
    }
}

/** A weekday evening, so a demonstration shows the congestion display doing something. */
private fun rushHour(): Long = dev.aetherx86.ferrite.core.epochOfKst(2026, 11, 2, 18, 0, 0)

private fun arrivedAt(frame: BoardFrame): Long = frame.atPlatform?.arrivalAtMs ?: 0L

/** The marker for this station is where the arrival color starts from. Roughly, since the layouts are fixed. */
private fun floodOrigin(dims: BoardDims): Offset =
    if (dims.landscape) Offset(0.74f, 0.74f) else Offset(0.66f, 0.68f)

@Composable
private fun FloodRipples(origin: Offset, scheme: BoardScheme, time: () -> Long) {
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * origin.x, size.height * origin.y)
        val t = time()
        val reach = size.maxDimension
        for (i in 0 until 3) {
            val phase = ((t / 4200f) + i / 3f) % 1f
            drawCircle(
                scheme.text.copy(alpha = (1f - phase) * 0.14f),
                radius = phase * reach * 0.8f,
                center = center,
                style = Stroke(1.6f.dp.toPx()),
            )
        }
    }
}

@Composable
private fun WaitingPage(
    path: BoardPath,
    dims: BoardDims,
    scheme: BoardScheme,
    lineColor: Color,
    display: BoardDisplay,
    source: FeedSource,
    time: dev.aetherx86.ferrite.core.KstTime,
    cars: List<CarLoad>,
    frame: () -> BoardFrame,
    clock: () -> Long,
    problem: String?,
) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    Column(
        Modifier.fillMaxSize().padding(horizontal = dims.d(dims.margin)).padding(
            top = dims.d(if (landscape) 18f else 44f),
            bottom = dims.d(if (landscape) 18f else 12f),
        ),
    ) {
        BoardHeader(path, dims, scheme, lineColor, time)
        Spacer(Modifier.height(dims.d(if (landscape) 16f else 10f)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.rule))
        Spacer(Modifier.height(dims.d(if (landscape) 30f else 14f)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(display.pill, dims, scheme)
            Spacer(Modifier.weight(1f))
            BasicText(display.cornerLabel, style = textStyle(fonts.sans, dims.t(if (landscape) 19f else 12.5f), FontWeight.Medium, scheme.dim), maxLines = 1)
        }
        if (landscape) {
            Spacer(Modifier.height(dims.d(14f)))
            Box(Modifier.fillMaxWidth().height(dims.d(170f))) {
                val width = dims.pageWidth - dims.margin * 2
                FitText(
                    display.destination,
                    maxSize = dims.t(128f),
                    color = scheme.text,
                    modifier = Modifier.width(dims.d(width * 0.46f)).fillMaxHeight170(dims),
                )
                HeroArrow(
                    lineColor, dims, display, clock,
                    Modifier.offset(x = dims.d(width * 0.475f)).align(Alignment.CenterStart).size(dims.d(122f), dims.d(70f)),
                )
                Countdown(display.value, display.unit, display.valueIsUrgent, dims, scheme, Modifier.align(Alignment.CenterEnd))
            }
        } else {
            Spacer(Modifier.height(dims.d(8f)))
            FitText(display.destination, maxSize = dims.t(68f), color = scheme.text, modifier = Modifier.fillMaxWidth().height(dims.d(76f)))
            Spacer(Modifier.height(dims.d(4f)))
            Box(Modifier.fillMaxWidth().height(dims.d(112f))) {
                HeroArrow(lineColor, dims, display, clock, Modifier.align(Alignment.CenterStart).size(dims.d(84f), dims.d(46f)))
                Countdown(display.value, display.unit, display.valueIsUrgent, dims, scheme, Modifier.align(Alignment.CenterEnd))
            }
        }
        Spacer(Modifier.height(dims.d(if (landscape) 6f else 4f)))
        Label(
            problem?.let { "실시간 연결 문제: $it" } ?: display.detail.replace(" • ", "  •  "),
            size = dims.t(if (landscape) 21f else 13f),
            weight = FontWeight.Medium,
            color = if (problem != null) Palette.Moderate else scheme.dim,
        )
        Spacer(Modifier.weight(1f))
        SectionLabel(source, dims, scheme)
        LineStrip(
            path, dims, scheme, lineColor, frame, clock,
            Modifier.fillMaxWidth().height(dims.d(if (landscape) 142f else 136f)),
        )
        CarsHeader(dims, scheme, onlyEstimate = true, roomy = null)
        Spacer(Modifier.height(dims.d(if (landscape) 10f else 6f)))
        CarRow(cars, dims, scheme, highlightRoomy = false)
    }
}

private fun Modifier.fillMaxHeight170(dims: BoardDims): Modifier = this.height(dims.d(170f))

@Composable
private fun HeroArrow(color: Color, dims: BoardDims, display: BoardDisplay, clock: () -> Long, modifier: Modifier) {
    val started = remember(display.trainKey) { clock() }
    val strokeDesign = if (dims.landscape) 9.5f else 7f
    Canvas(modifier) {
        val elapsed = clock() - started
        val drawn = (elapsed / 700f).coerceIn(0f, 1f)
        val nudge = sin(elapsed / 620.0).toFloat()
        drawHeroArrow(color, dims.px(strokeDesign), FastOutSlowInEasing.transform(drawn), nudge)
    }
}

@Composable
private fun ArrivedPage(
    path: BoardPath,
    dims: BoardDims,
    scheme: BoardScheme,
    lineColor: Color,
    shown: PlatformDisplay,
    source: FeedSource,
    time: dev.aetherx86.ferrite.core.KstTime,
    cars: List<CarLoad>,
    frame: () -> BoardFrame,
    clock: () -> Long,
    arrivedAtMs: () -> Long,
) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    val headline = if (shown.closing) "곧 출발" else "당역 도착"
    val notice = if (shown.closing) listOf("문이 닫힙니다", "무리하게 타지 마세요") else listOf("안전선 뒤로", "물러서 주세요")
    val roomy = cars.filter { it.level == LoadLevel.ROOMY }.map { it.car }.sorted()
    val background = lineColor
    Column(
        Modifier.fillMaxSize().padding(horizontal = dims.d(dims.margin)).padding(
            top = dims.d(if (landscape) 18f else 44f),
            bottom = dims.d(if (landscape) 18f else 12f),
        ),
    ) {
        BoardHeader(path, dims, scheme, lineColor, time)
        Spacer(Modifier.height(dims.d(if (landscape) 16f else 10f)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.rule))
        Spacer(Modifier.height(dims.d(if (landscape) 26f else 14f)))
        if (landscape) {
            Row(Modifier.fillMaxWidth().height(dims.d(330f))) {
                Column(Modifier.weight(1f)) {
                    TrainTitle(shown, dims, scheme)
                    Spacer(Modifier.height(dims.d(14f)))
                    FitText(headline, maxSize = dims.t(228f), color = scheme.text, modifier = Modifier.fillMaxWidth().height(dims.d(228f)))
                }
                Spacer(Modifier.width(dims.d(30f)))
                NoticeColumn(notice, shown, dims, scheme, background, arrivedAtMs, clock, Modifier.width(dims.d(310f)))
            }
        } else {
            TrainTitle(shown, dims, scheme)
            Spacer(Modifier.height(dims.d(6f)))
            // The headline takes whatever height is left, so the row of cars below always stays on screen.
            Column(Modifier.weight(1f).fillMaxWidth()) {
                headline.split(' ').forEach { word ->
                    FitText(word, maxSize = dims.t(142f), color = scheme.text, modifier = Modifier.weight(1f).fillMaxWidth(if (word.length > 1) 0.8f else 0.42f))
                }
            }
            Spacer(Modifier.height(dims.d(6f)))
            NoticeColumn(notice, shown, dims, scheme, background, arrivedAtMs, clock, Modifier.fillMaxWidth())
            Spacer(Modifier.height(dims.d(4f)))
        }
        if (landscape) Spacer(Modifier.weight(1f))
        LineStrip(
            path, dims, scheme, lineColor, frame, clock,
            Modifier.fillMaxWidth().height(dims.d(if (landscape) 142f else 136f)),
        )
        CarsHeader(dims, scheme, onlyEstimate = true, roomy = roomy)
        Spacer(Modifier.height(dims.d(if (landscape) 10f else 6f)))
        CarRow(cars, dims, scheme, highlightRoomy = true)
    }
}

@Composable
private fun TrainTitle(shown: PlatformDisplay, dims: BoardDims, scheme: BoardScheme) {
    val landscape = dims.landscape
    Row(verticalAlignment = Alignment.Bottom) {
        FitText(
            shown.destination,
            maxSize = dims.t(if (landscape) 66f else 34f),
            color = scheme.text,
            modifier = Modifier.weight(1f, fill = false).height(dims.d(if (landscape) 70f else 38f)),
        )
        shown.trainNumber?.let {
            Spacer(Modifier.width(dims.d(if (landscape) 18f else 10f)))
            Label("$it 열차", size = dims.t(if (landscape) 23f else 14f), weight = FontWeight.Bold, color = scheme.soft, modifier = Modifier.padding(bottom = dims.d(if (landscape) 8f else 4f)))
        }
    }
}

@Composable
private fun NoticeColumn(
    notice: List<String>,
    shown: PlatformDisplay,
    dims: BoardDims,
    scheme: BoardScheme,
    background: Color,
    arrivedAtMs: () -> Long,
    clock: () -> Long,
    modifier: Modifier,
) {
    val fonts = LocalFonts.current
    val landscape = dims.landscape
    Column(modifier, horizontalAlignment = Alignment.End) {
        val doorW = if (landscape) 72f else 40f
        val doorH = if (landscape) 76f else 42f
        Canvas(Modifier.size(dims.d(doorW), dims.d(doorH))) {
            val elapsed = clock() - arrivedAtMs()
            val untilClose = BoardEngine.DWELL_MS - elapsed
            val opening = ((elapsed - 900) / 1500f).coerceIn(0f, 1f)
            val closing = ((untilClose - 1000) / 3200f).coerceIn(0f, 1f)
            // Open doors stay partly in frame, so it still reads as a door.
            drawDoors(scheme.text, background, 0.62f * FastOutSlowInEasing.transform(minOf(opening, closing)), dims.px(1.5f))
        }
        Spacer(Modifier.height(dims.d(if (landscape) 22f else 10f)))
        Box(Modifier.fillMaxWidth().height(dims.d(if (landscape) 3f else 2f)).background(scheme.text))
        Spacer(Modifier.height(dims.d(if (landscape) 20f else 10f)))
        notice.forEach { line ->
            Label(
                line,
                size = dims.t(if (landscape) 34f else 25f),
                weight = FontWeight.Bold,
                color = scheme.text,
                align = TextAlign.End,
                modifier = Modifier.fillMaxWidth().height(dims.d(if (landscape) 44f else 31f)),
            )
        }
        if (shown.nextLine.isNotEmpty()) {
            Spacer(Modifier.height(dims.d(if (landscape) 12f else 6f)))
            Label(shown.nextLine, size = dims.t(if (landscape) 15f else 11.5f), weight = FontWeight.Bold, color = scheme.soft, align = TextAlign.End)
        }
    }
}

@Composable
private fun ControlBar(
    dims: BoardDims,
    station: Station,
    line: Line,
    paths: List<BoardPath>,
    directionIndex: Int,
    soundOn: Boolean,
    favorite: Boolean,
    onBack: () -> Unit,
    onSound: () -> Unit,
    onFavorite: () -> Unit,
    onDirection: () -> Unit,
    onLine: (Line) -> Unit,
    onWalk: () -> Unit,
    onRotate: () -> Unit,
    floodActive: Boolean,
    ink: Color,
) {
    val landscape = dims.landscape
    val glass = if (landscape) Modifier.background(Palette.PanelHigh.copy(alpha = 0.94f), RoundedCornerShape(50)).padding(horizontal = 6.dp) else Modifier
    val tint = if (landscape) Palette.Text else ink
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (landscape) Modifier.padding(bottom = 10.dp) else Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            .then(glass),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icon.BACK, tint, "뒤로", onBack)
        if (!landscape) Spacer(Modifier.weight(1f))
        val current = paths.getOrNull(directionIndex)?.next
        if (paths.size > 1 && current != null) {
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(tint.copy(alpha = 0.1f))
                    .clickable(onClick = onDirection)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Icon.SWAP, tint, size = 15.dp)
                Spacer(Modifier.width(5.dp))
                Label("${current.name} 방면", size = 12.sp, weight = FontWeight.Bold, color = tint, modifier = Modifier.width(if (current.name.length > 7) 120.dp else androidx.compose.ui.unit.Dp.Unspecified))
            }
        } else if (current == null) {
            Label("이 역이 종점입니다", size = 12.sp, weight = FontWeight.Medium, color = tint.copy(alpha = 0.7f))
        }
        Spacer(Modifier.width(4.dp))
        station.lines.filter { it.id != line.id }.take(3).forEach { other ->
            val color = Color(other.colorArgb)
            Box(
                Modifier.padding(horizontal = 2.dp).size(26.dp).clip(CircleShape).background(color).clickable { onLine(other) },
                contentAlignment = Alignment.Center,
            ) {
                FitText(other.short, maxSize = 12.sp, color = contrastOn(color), weight = FontWeight.Bold, alignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
        if (!landscape) Spacer(Modifier.weight(1f))
        BarButton(Icon.ROTATE, tint, "화면 돌리기", onRotate)
        BarButton(Icon.WALK, tint, "이 역까지 걷기", onWalk)
        BarButton(if (favorite) Icon.STAR_FILLED else Icon.STAR, tint, "즐겨찾기", onFavorite)
        BarButton(if (soundOn) Icon.SOUND_ON else Icon.SOUND_OFF, tint, if (soundOn) "소리 끄기" else "소리 켜기", onSound)
    }
}

@Composable
private fun BarButton(icon: Icon, tint: Color, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint, size = 20.dp)
    }
}

