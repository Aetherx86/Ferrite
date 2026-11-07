package dev.aetherx86.ferrite.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.map.BaseMap
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.IconButton
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LineMark
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.PrimaryButton
import dev.aetherx86.ferrite.ui.common.SearchField
import dev.aetherx86.ferrite.ui.common.StationArrivals
import dev.aetherx86.ferrite.ui.common.consumeTaps
import dev.aetherx86.ferrite.ui.common.textStyle
import dev.aetherx86.ferrite.ui.common.visibleOnDark
import dev.aetherx86.ferrite.ui.home.StationRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max

private const val COUNTRY = "country"
private const val LABEL_MARGIN = 1.04f
private const val STREET_SCALE = 14f

/** A trip to draw on the schematic, as the stations it uses on each line. */
class MapHighlight(val region: String, val lines: Set<String>, val stations: Set<Int>)

@Composable
fun MapScreen(
    onBoard: (Station, Line?) -> Unit,
    onRouteFrom: (Station) -> Unit,
    onRouteTo: (Station) -> Unit,
    onWalkTo: (Station) -> Unit,
    focusStation: Station? = null,
    highlight: MapHighlight? = null,
) {
    val services = LocalServices.current
    val network = services.network
    var region by remember { mutableStateOf(focusStation?.region ?: highlight?.region ?: "capital") }
    var selected by remember { mutableStateOf(focusStation) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pendingFocus by remember { mutableStateOf(focusStation) }
    var focusLine by remember { mutableStateOf<Line?>(null) }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (region == COUNTRY) {
            CountryMap(selected, pendingFocus, { pendingFocus = null }) { selected = it }
        } else {
            SchematicMap(region, selected, pendingFocus, { pendingFocus = null }, focusLine, highlight.takeIf { it?.region == region }) { selected = it }
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Palette.Panel).border(1.dp, Palette.Hairline, CircleShape).clickable { searching = !searching },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(if (searching) Icon.CLOSE else Icon.SEARCH, Palette.Text, size = 18.dp)
                }
                Chip("전국", region == COUNTRY, { region = COUNTRY; focusLine = null })
                network.regions.forEach { r ->
                    Chip(r.name, region == r.id, { region = r.id; focusLine = null; selected = null })
                }
            }
            if (searching) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 520.dp)) {
                    SearchField(query, { query = it }, "역 이름 또는 초성")
                    val results = remember(query) { if (query.isBlank()) emptyList() else network.search(query, 12) }
                    if (results.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            Modifier.heightIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Panel).padding(horizontal = 10.dp),
                        ) {
                            items(results, key = { it.id }) { station ->
                                StationRow(station) {
                                    if (region != COUNTRY) region = station.region
                                    selected = station
                                    pendingFocus = station
                                    searching = false
                                    query = ""
                                }
                            }
                        }
                    }
                }
            } else if (region != COUNTRY) {
                val lines = remember(region) { network.lines.filter { it.region == region } }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    lines.forEach { line ->
                        val on = focusLine?.id == line.id
                        Box(
                            Modifier.clip(CircleShape).then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .clickable { focusLine = if (on) null else line },
                        ) {
                            LineMark(line, 28.dp)
                        }
                    }
                }
            }
        }

        val shown = selected
        if (shown != null) {
            StationSheet(
                shown,
                Modifier.align(Alignment.BottomCenter),
                onClose = { selected = null },
                onBoard = { onBoard(shown, it) },
                onFrom = { onRouteFrom(shown) },
                onTo = { onRouteTo(shown) },
                onWalk = { onWalkTo(shown) },
            )
        }
    }
}

@Composable
private fun StationSheet(
    station: Station,
    modifier: Modifier,
    onClose: () -> Unit,
    onBoard: (Line?) -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onWalk: () -> Unit,
) {
    val settings = LocalServices.current.settings
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.padding(10.dp).widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(Palette.PanelHigh).border(1.dp, Palette.Hairline, shape).consumeTaps().padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            station.lines.take(5).forEach { LineMark(it, 24.dp); Spacer(Modifier.width(3.dp)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Label(station.name, size = 19.sp, weight = FontWeight.Black)
                Label(station.nameEn, size = 12.sp, color = Palette.TextDim)
            }
            IconButton(
                if (station.id in settings.favorites) Icon.STAR_FILLED else Icon.STAR, "즐겨찾기",
                { settings.toggleFavorite(station.id) }, modifier = Modifier.size(38.dp),
            )
            IconButton(Icon.CLOSE, "닫기", onClose, Palette.TextDim, Modifier.size(38.dp))
        }
        Spacer(Modifier.height(6.dp))
        StationArrivals(station, maxLines = 2) { onBoard(it) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton("전광판", { onBoard(null) }, Modifier.weight(1.2f), Icon.TRAIN)
            GhostButton("출발", onFrom, Modifier.weight(1f))
            GhostButton("도착", onTo, Modifier.weight(1f))
            GhostButton("걷기", onWalk, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SchematicMap(
    regionId: String,
    selected: Station?,
    focus: Station?,
    onFocused: () -> Unit,
    focusLine: Line?,
    highlight: MapHighlight?,
    onSelect: (Station) -> Unit,
) {
    val services = LocalServices.current
    val density = LocalDensity.current
    val labelPx = with(density) { 11.5.sp.toPx() }
    val scene by produceState<SchematicScene?>(null, regionId) {
        value = null
        value = withContext(Dispatchers.Default) { SchematicScene.build(services.network, regionId, labelPx, density.density) }
    }
    val loaded = scene ?: return
    val camera = remember(loaded) {
        MapCamera(loaded.width, loaded.height, max(density.density * 42f, loaded.maxLabelScale.coerceAtMost(density.density * 60f) * 1.05f))
    }
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val labelCache = remember(loaded) { HashMap<Int, TextLayoutResult>() }
    val minTapPx = with(density) { 26.dp.toPx() }

    LaunchedEffect(focus, loaded) {
        val target = focus?.takeIf { it.region == regionId }
        if (target == null) {
            // The whole capital area is too much for a phone, so it opens on the middle of Seoul.
            if (regionId == "capital" && camera.scale <= camera.minScale * 1.01f) {
                loaded.stations.firstOrNull { it.station.name == "서울역" }?.let { camera.jumpTo(it.x + 4f, it.y + 3f, density.density * 7.5f) }
            }
            return@LaunchedEffect
        }
        val mark = loaded.stations.firstOrNull { it.station.id == target.id } ?: return@LaunchedEffect
        camera.flyTo(mark.x, mark.y, density.density * 16f)
        onFocused()
    }

    Canvas(
        Modifier.fillMaxSize().background(Palette.Ink).mapGestures(camera) { at ->
            val hit = loaded.stations.minByOrNull { hypot(camera.x(it.x) - at.x, camera.y(it.y) - at.y) }
            if (hit != null && hypot(camera.x(hit.x) - at.x, camera.y(hit.y) - at.y) <= minTapPx) onSelect(hit.station)
        },
    ) {
        camera.attach(size)
        val s = camera.scale
        val strokeWorld = max(0.30f, 1.7f * density.density / s)
        val active = focusLine?.id
        withTransform({
            translate(camera.offsetX, camera.offsetY)
            scale(s, s, Offset.Zero)
        }) {
            val stroke = Stroke(strokeWorld, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (line in loaded.lines) {
                val dimmed = (active != null && line.line.id != active) || (highlight != null && line.line.id !in highlight.lines)
                val color = visibleOnDark(line.color).let { if (dimmed) it.copy(alpha = 0.16f) else it }
                for (path in line.paths) drawPath(path, color, style = stroke)
            }
        }
        val left = camera.worldLeft() - 2
        val right = camera.worldRight() + 2
        val top = camera.worldTop() - 2
        val bottom = camera.worldBottom() + 2
        val markScale = (s / (density.density * 12f)).coerceIn(0.5f, 1.5f)
        for (mark in loaded.stations) {
            if (mark.x < left || mark.x > right || mark.y < top || mark.y > bottom) continue
            val dimmed = (active != null && mark.station.lines.none { it.id == active }) || (highlight != null && mark.station.id !in highlight.stations)
            val center = Offset(camera.x(mark.x), camera.y(mark.y))
            val alpha = if (dimmed) 0.25f else 1f
            if (mark.interchange) {
                val r = 4.6f * density.density * markScale
                drawCircle(Color.White.copy(alpha = alpha), r, center)
                drawCircle(Palette.Ink.copy(alpha = alpha), r, center, style = Stroke(1.4f * density.density * markScale))
            } else {
                val r = 2.4f * density.density * markScale
                drawCircle(Palette.Ink.copy(alpha = alpha), r, center)
                drawCircle(Color.White.copy(alpha = alpha * 0.92f), r, center, style = Stroke(1.2f * density.density * markScale))
            }
            if (selected?.id == mark.station.id) {
                drawCircle(Palette.Accent, 12f * density.density, center, style = Stroke(2.4f * density.density))
            }
            val wanted = s >= mark.label.minScale * LABEL_MARGIN || selected?.id == mark.station.id || (highlight != null && mark.station.id in highlight.stations && s >= mark.label.minScale * 0.6f)
            if (wanted && !dimmed) {
                val layout = labelCache.getOrPut(mark.station.id) {
                    measurer.measure(
                        mark.station.name,
                        textStyle(
                            fonts.sans,
                            with(density) { labelPx.toSp() },
                            if (mark.interchange) FontWeight.Bold else FontWeight.Medium,
                            if (mark.interchange || mark.terminus) Palette.Text else Palette.TextSoft,
                        ),
                        softWrap = false,
                        maxLines = 1,
                    )
                }
                drawText(layout, topLeft = Offset(center.x + mark.label.left, center.y + mark.label.top))
            }
        }
    }
}

@Composable
private fun CountryMap(selected: Station?, focus: Station?, onFocused: () -> Unit, onSelect: (Station) -> Unit) {
    val services = LocalServices.current
    val host = LocalHost.current
    val density = LocalDensity.current
    val geo = services.geo ?: return
    val labelPx = with(density) { 11.sp.toPx() }
    val scene by produceState<GeoScene?>(null) {
        value = withContext(Dispatchers.Default) { GeoScene.build(geo, services.network, labelPx) }
    }
    val loaded = scene ?: return
    val camera = remember(loaded) { MapCamera(loaded.width, loaded.height, density.density * 260f) }
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val cache = remember(loaded) { HashMap<String, TextLayoutResult>() }
    var here by remember { mutableStateOf<LatLng?>(null) }
    val scope = rememberCoroutineScope()
    val tapPx = with(density) { 24.dp.toPx() }
    val base = remember(loaded) { BaseMap(scope, unitsPerKm = 1f) { lon, lat -> loaded.projectPoint(lon, lat) } }

    LaunchedEffect(focus, loaded) {
        val target = focus ?: return@LaunchedEffect
        val (x, y) = loaded.positionOf(target.location)
        camera.flyTo(x, y, density.density * 60f)
        onFocused()
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().background(Color(0xFF05070B)).mapGestures(camera) { at ->
                if (camera.scale < density.density * 7f) {
                    // Zoomed out, a tap on a city goes there rather than picking out one of its stations.
                    scope.launch {
                        camera.flyTo((at.x - camera.offsetX) / camera.scale, (at.y - camera.offsetY) / camera.scale, density.density * 16f)
                    }
                } else {
                    val hit = loaded.stations.minByOrNull { hypot(camera.x(it.x) - at.x, camera.y(it.y) - at.y) }
                    if (hit != null && hypot(camera.x(hit.x) - at.x, camera.y(hit.y) - at.y) <= tapPx) onSelect(hit.station)
                }
            },
        ) {
            camera.attach(size)
            val s = camera.scale
            val pixel = 1f / s
            val d = density.density
            val streets = s > d * STREET_SCALE
            if (streets) {
                base.draw(
                    this,
                    west = loaded.lonAt(camera.worldLeft()),
                    north = loaded.latAt(camera.worldTop()),
                    east = loaded.lonAt(camera.worldRight()),
                    south = loaded.latAt(camera.worldBottom()),
                    pixelsPerUnit = s,
                    transform = { block ->
                        withTransform({
                            translate(camera.offsetX, camera.offsetY)
                            scale(s, s, Offset.Zero)
                        }) { block() }
                    },
                    toScreen = { x, y -> Offset(camera.x(x), camera.y(y)) },
                    measurer = measurer,
                    family = fonts.sans,
                )
            }
            withTransform({
                translate(camera.offsetX, camera.offsetY)
                scale(s, s, Offset.Zero)
            }) {
                if (!streets) drawPath(loaded.land, Palette.Panel)
                if (!streets && s > d * 2.2f) drawPath(loaded.municipalBorders, Palette.Hairline.copy(alpha = ((s / d - 2.2f) / 3f).coerceIn(0f, 1f)), style = Stroke(0.8f * d * pixel))
                if (!streets) drawPath(loaded.provinceBorders, Color(0xFF2E394A), style = Stroke(1f * d * pixel))
                drawPath(loaded.intercity, Color(0xFF3B4658), style = Stroke(1f * d * pixel, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val width = (if (s > d * 16f) 2.6f else 1.7f) * d * pixel
                for ((_, color, path) in loaded.tracks) {
                    drawPath(path, visibleOnDark(color), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            val left = camera.worldLeft()
            val right = camera.worldRight()
            val top = camera.worldTop()
            val bottom = camera.worldBottom()
            if (s > d * 5f) {
                val r = (s / (d * 10f)).coerceIn(0.8f, 3.4f) * d
                for (mark in loaded.stations) {
                    if (mark.x < left || mark.x > right || mark.y < top || mark.y > bottom) continue
                    val center = Offset(camera.x(mark.x), camera.y(mark.y))
                    drawCircle(Color.White, r, center)
                    if (selected?.id == mark.station.id) drawCircle(Palette.Accent, 11f * d, center, style = Stroke(2.2f * d))
                }
            }
            for (label in loaded.labels) {
                if (s < label.minScale) continue
                if (streets && label.level != 2) continue
                if (label.x < left || label.x > right || label.y < top || label.y > bottom) continue
                val layout = cache.getOrPut("${label.level}${label.text}") {
                    val (size, weight, color) = when (label.level) {
                        0 -> Triple(1.15f, FontWeight.Bold, Palette.TextSoft)
                        1 -> Triple(0.95f, FontWeight.Medium, Palette.TextDim)
                        else -> Triple(1f, FontWeight.Medium, Palette.Text)
                    }
                    measurer.measure(label.text, textStyle(fonts.sans, with(density) { (labelPx * size).toSp() }, weight, color), softWrap = false, maxLines = 1)
                }
                val shift = if (label.level == 2) 4f * d + layout.size.width / 2f else 0f
                drawText(layout, topLeft = Offset(camera.x(label.x) - layout.size.width / 2f + shift, camera.y(label.y) - layout.size.height / 2f))
            }
            here?.let { point ->
                val (x, y) = loaded.positionOf(point)
                val center = Offset(camera.x(x), camera.y(y))
                drawCircle(Palette.Accent.copy(alpha = 0.25f), 14f * d, center)
                drawCircle(Color.White, 6f * d, center)
                drawCircle(Palette.Accent, 4.5f * d, center)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(14.dp).size(46.dp).clip(CircleShape).background(Palette.PanelHigh).border(1.dp, Palette.Hairline, CircleShape).clickable {
                scope.launch {
                    host.currentLocation()?.let { point ->
                        here = point
                        val (x, y) = loaded.positionOf(point)
                        camera.flyTo(x, y, density.density * 40f)
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Icon.TARGET, Palette.Text, size = 22.dp)
        }
        Label(
            "경계: 통계청 통계지리정보서비스 · 철도: © OpenStreetMap 기여자",
            size = 9.sp,
            color = Palette.TextFaint,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 6.dp, end = 70.dp),
        )
    }
}

