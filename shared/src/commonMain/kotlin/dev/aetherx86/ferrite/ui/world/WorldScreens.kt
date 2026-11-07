package dev.aetherx86.ferrite.ui.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.LocalProjection
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.data.parseColor
import dev.aetherx86.ferrite.map.BaseMap
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.transit.WorldLeg
import dev.aetherx86.ferrite.transit.WorldTransit
import dev.aetherx86.ferrite.transit.WorldTrip
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.PlacePicker
import dev.aetherx86.ferrite.ui.common.SourceTag
import dev.aetherx86.ferrite.ui.common.contrastOn
import dev.aetherx86.ferrite.ui.walk.WalkMap
import dev.aetherx86.ferrite.walk.StepKind
import dev.aetherx86.ferrite.walk.WalkProfile
import dev.aetherx86.ferrite.walk.WalkRoute
import dev.aetherx86.ferrite.walk.WalkStep
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/** A named place, with where it is. */
data class NamedSpot(val name: String, val position: LatLng) {
    companion object {
        /** "lat,lon,name", as links write it. */
        fun parse(text: String?): NamedSpot? {
            val parts = text?.split(',', limit = 3) ?: return null
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            return NamedSpot(parts.getOrNull(2)?.trim().orEmpty().ifEmpty { "지정한 곳" }, LatLng(lat, lon))
        }
    }
}

private fun minutes(seconds: Int): String {
    val m = (seconds + 30) / 60
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m.coerceAtLeast(1)}분"
}

/** Public transport outside the country's own rail data, anywhere that publishes timetables. */
@Composable
fun WorldTransitScreen(initialFrom: NamedSpot? = null, initialTo: NamedSpot? = null) {
    val host = LocalHost.current
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var trips by remember { mutableStateOf<List<WorldTrip>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (from == null && host.locationAllowed()) host.currentLocation()?.let { from = NamedSpot("내 위치", it) }
    }
    LaunchedEffect(from, to) {
        trips = null
        problem = null
        choice = 0
        val a = from ?: return@LaunchedEffect
        val b = to ?: return@LaunchedEffect
        try {
            val iso = Instant.fromEpochMilliseconds(nowMillis()).toString()
            trips = WorldTransit.plan(a.position, b.position, iso)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "대중교통 경로를 받지 못했습니다. 연결을 확인하고 다시 시도해 주세요."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            EndRow("출발", from?.name, editing == 0) { editing = if (editing == 0) null else 0 }
            EndRow("도착", to?.name, editing == 1) { editing = if (editing == 1) null else 1 }
        }
        val editingNow = editing
        if (editingNow != null) {
            PlacePicker(allowHere = editingNow == 0, modifier = Modifier.padding(horizontal = 14.dp).fillMaxSize()) { name, at ->
                if (editingNow == 0) from = NamedSpot(name, at) else to = NamedSpot(name, at)
                editing = null
            }
            return@Column
        }
        val list = trips
        when {
            from == null || to == null -> Box(Modifier.padding(14.dp)) {
                NoticeCard("어느 도시든 찾아보세요", "시간표를 공개한 도시라면 버스, 지하철, 열차 경로를 찾습니다. 장소 이름은 영어로 검색해도 됩니다.")
            }
            problem != null -> Box(Modifier.padding(14.dp)) { NoticeCard("경로를 찾지 못했습니다", problem!!) }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("경로를 찾는 중", size = 14.sp, weight = FontWeight.Bold, color = Palette.TextSoft)
            }
            list.isEmpty() -> Box(Modifier.padding(14.dp)) {
                NoticeCard("대중교통 경로가 없습니다", "이 구간의 시간표가 공개되어 있지 않거나 지금은 운행하지 않습니다. 운전이나 걷기로 찾아보세요.")
            }
            else -> {
                val trip = list[choice.coerceIn(0, list.lastIndex)]
                val route = remember(trip) { routeOf(trip) }
                if (route.points.size >= 2) WalkMap(null, route, Modifier.fillMaxWidth().weight(0.9f))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.take(5).forEachIndexed { i, t ->
                        val wait = ((t.startMs - nowMillis()) / 60000).toInt().coerceAtLeast(0)
                        Chip("${minutes(t.seconds)} · ${wait}분 후", i == choice, { choice = i })
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp)) {
                    item {
                        Label("환승 ${trip.transfers}회 · 총 ${minutes(trip.seconds)}", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    items(trip.legs) { leg -> LegRow(leg) }
                    item {
                        Label("공개 시간표 기준 · 지도 © OpenStreetMap 기여자", size = 10.sp, color = Palette.TextFaint, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

private fun routeOf(trip: WorldTrip): WalkRoute {
    val points = trip.legs.flatMap { it.points }
    return WalkRoute(
        profile = WalkProfile.FASTEST,
        points = points,
        meters = 0.0,
        seconds = trip.seconds,
        stairs = 0,
        crossings = 0,
        steps = listOfNotNull(points.lastOrNull()?.let { WalkStep(StepKind.ARRIVE, "도착", 0.0, it) }),
    )
}

@Composable
private fun LegRow(leg: WorldLeg) {
    val color = leg.colorHex?.let { Color(parseColor(it)) } ?: if (leg.isWalk) Palette.PanelHigh else Palette.Accent
    Panel(Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(listOfNotNull(leg.label, leg.route).joinToString(" "), size = 12.sp, weight = FontWeight.Bold, color = contrastOn(color))
            }
            Spacer(Modifier.width(8.dp))
            Label(minutes(leg.seconds), size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (leg.live) SourceTag("실시간", true)
        }
        Spacer(Modifier.height(5.dp))
        if (!leg.isWalk && !leg.headsign.isNullOrBlank()) Label("${leg.headsign} 방면 · ${leg.stops}개 정류장", size = 12.sp, color = Palette.TextSoft, maxLines = 2)
        val fromName = leg.from.takeUnless { it == "START" } ?: "출발지"
        val toName = leg.to.takeUnless { it == "END" } ?: "도착지"
        Label("$fromName → $toName", size = 13.sp, color = Palette.TextSoft, maxLines = 2)
    }
}

@Composable
private fun EndRow(label: String, value: String?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, if (active) Palette.Accent else Palette.Hairline, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.width(34.dp))
        Label(value ?: "선택해 주세요", size = 15.sp, weight = FontWeight.Bold, color = if (value == null) Palette.TextFaint else Palette.Text, modifier = Modifier.weight(1f))
    }
}

/** A street map of anywhere: around you, or wherever you search for. */
@Composable
fun AroundMap(initial: NamedSpot? = null) {
    val host = LocalHost.current
    var center by remember { mutableStateOf(initial) }
    var searching by remember { mutableStateOf(initial == null) }
    LaunchedEffect(Unit) {
        if (center == null && host.locationAllowed()) host.currentLocation()?.let {
            center = NamedSpot("내 위치", it)
            searching = false
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 54.dp)) {
        val spot = center
        if (spot != null) AroundCanvas(spot)
        Box(
            Modifier.align(Alignment.TopStart).padding(10.dp).size(40.dp).clip(CircleShape).background(Palette.PanelHigh).clickable { searching = !searching },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(if (searching) Icon.CLOSE else Icon.SEARCH, Palette.Text, size = 18.dp)
        }
        if (searching) {
            Column(Modifier.padding(top = 56.dp, start = 10.dp, end = 10.dp, bottom = 10.dp).widthIn(max = 520.dp).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.Ink).padding(10.dp)) {
                Label("세계 어디든 지도로 보기", size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                PlacePicker(allowHere = true, modifier = Modifier.fillMaxSize()) { name, at ->
                    center = NamedSpot(name, at)
                    searching = false
                }
            }
        }
    }
}

@Composable
private fun AroundCanvas(spot: NamedSpot) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val projection = remember(spot) { LocalProjection(spot.position) }
    val base = remember(projection) { BaseMap(scope, unitsPerKm = 1000f) { lon, lat -> projection.x(lon).toFloat() to projection.y(lat).toFloat() } }
    var zoom by remember(spot) { mutableStateOf(0.34f * density) }
    var pan by remember(spot) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(spot) {
                detectTransformGestures { centroid, p, z, _ ->
                    val next = (zoom * z).coerceIn(0.00008f * density, 4f * density)
                    val applied = next / zoom
                    val c = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan + c - centroid) * applied + centroid - c + p
                    zoom = next
                }
            },
        ) {
            val center = Offset(size.width / 2 + pan.x, size.height / 2 + pan.y)
            val s = zoom
            base.draw(
                this,
                west = projection.lon(((-center.x) / s).toDouble()),
                north = projection.lat(((-center.y) / s).toDouble()),
                east = projection.lon(((size.width - center.x) / s).toDouble()),
                south = projection.lat(((size.height - center.y) / s).toDouble()),
                pixelsPerUnit = s,
                transform = { block ->
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.translate(center.x, center.y)
                    canvas.scale(s, s)
                    block()
                    canvas.restore()
                },
                toScreen = { x, y -> Offset(center.x + x * s, center.y + y * s) },
                measurer = measurer,
                family = fonts.sans,
            )
            drawCircle(Palette.Accent.copy(alpha = 0.25f), 16f * density, center)
            drawCircle(Color.White, 8f * density, center)
            drawCircle(Palette.Accent, 5.5f * density, center)
        }
        Label(
            "© OpenFreeMap © OpenMapTiles © OpenStreetMap 기여자",
            size = 9.sp,
            color = Palette.TextDim,
            modifier = Modifier.align(Alignment.BottomStart).background(Palette.Ink.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
