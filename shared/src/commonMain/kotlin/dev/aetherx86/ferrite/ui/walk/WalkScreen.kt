package dev.aetherx86.ferrite.ui.walk

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.LocalProjection
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.map.BaseMap
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.SearchField
import dev.aetherx86.ferrite.walk.PathKind
import dev.aetherx86.ferrite.walk.Place
import dev.aetherx86.ferrite.walk.PlaceSearch
import dev.aetherx86.ferrite.walk.StationExit
import dev.aetherx86.ferrite.walk.StepKind
import dev.aetherx86.ferrite.walk.WalkGraph
import dev.aetherx86.ferrite.walk.WalkProfile
import dev.aetherx86.ferrite.walk.WalkRoute
import dev.aetherx86.ferrite.walk.WalkRouter
import dev.aetherx86.ferrite.walk.WalkService
import dev.aetherx86.ferrite.walk.WalkStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Spot {
    val title: String
    val position: LatLng

    data class Here(override val position: LatLng) : Spot {
        override val title get() = "내 위치"
    }

    data class AtStation(val station: Station) : Spot {
        override val title get() = "${station.name}역"
        override val position get() = station.location
    }

    data class AtPlace(val place: Place) : Spot {
        override val title get() = place.name
        override val position get() = place.position
    }
}

/**
 * Walking directions worked out on the phone from open map data, including which way has stairs and where to cross.
 */
@Composable
fun WalkScreen(toStation: Station? = null, fromStation: Station? = null) {
    val services = LocalServices.current
    val host = LocalHost.current
    val network = services.network
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf<Spot?>(fromStation?.let { Spot.AtStation(it) }) }
    var to by remember { mutableStateOf<Spot?>(toStation?.let { Spot.AtStation(it) }) }
    var editing by remember { mutableStateOf<Int?>(if (toStation == null) 1 else null) }
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchProblem by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf(WalkProfile.FASTEST) }
    var graph by remember { mutableStateOf<WalkGraph?>(null) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (from == null) host.currentLocation()?.let { from = Spot.Here(it) }
    }

    var routes by remember { mutableStateOf<Map<WalkProfile, WalkRoute?>>(emptyMap()) }

    LaunchedEffect(from, to) {
        graph = null
        routes = emptyMap()
        problem = null
        val a = from ?: return@LaunchedEffect
        val b = to ?: return@LaunchedEffect
        val span = distanceMeters(a.position, b.position)
        if (span > WalkGraph.MAX_SPAN_METERS) {
            problem = "걷기에는 먼 거리입니다 (${(span / 1000).toInt()}km). 길찾기의 지하철 경로를 이용해 보세요."
            return@LaunchedEffect
        }
        loading = true
        routes = withContext(Dispatchers.Default) { WalkService.routes(a.position, b.position) }
        val answered = routes.values.any { it != null }
        loading = !answered
        if (answered) {
            // Only the exits are still worth asking the slower server for, and the route does not wait for them.
            val station = (b as? Spot.AtStation)?.station
            if (station != null) {
                val exits = runCatching { withContext(Dispatchers.Default) { WalkGraph.fetchExits(station.location) } }.getOrDefault(emptyList())
                graph = WalkGraph(emptyList(), emptyList(), exits)
            }
            return@LaunchedEffect
        }
        // The fast service could not be reached, so the route is worked out here from the streets themselves.
        loading = true
        try {
            val streets = withContext(Dispatchers.Default) { WalkGraph.fetch(a.position, b.position) }
            graph = streets
            routes = withContext(Dispatchers.Default) {
                WalkProfile.entries.associateWith { WalkRouter.route(streets, a.position, b.position, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "길찾기 서버와 지도 서버에 모두 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요."
        }
        loading = false
    }

    val route = routes[profile]

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            SpotRow("출발", from?.title, editing == 0) { editing = if (editing == 0) null else 0; query = ""; places = emptyList() }
            Spacer(Modifier.height(5.dp))
            SpotRow("도착", to?.title, editing == 1) { editing = if (editing == 1) null else 1; query = ""; places = emptyList() }
        }
        val editingNow = editing
        if (editingNow != null) {
            Column(Modifier.padding(horizontal = 14.dp).fillMaxSize()) {
                SearchField(
                    query, { query = it }, "역, 건물, 장소",
                    onSubmit = {
                        searching = true
                        searchProblem = null
                        scope.launch {
                            try {
                                places = withContext(Dispatchers.Default) { PlaceSearch.search(query) }
                                if (places.isEmpty()) searchProblem = "찾는 장소가 없습니다"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                searchProblem = "장소를 찾지 못했습니다. 연결을 확인해 주세요."
                            }
                            searching = false
                        }
                    },
                )
                val stations = remember(query) { if (query.isBlank()) emptyList() else network.search(query, 6) }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    if (editingNow == 0) {
                        item {
                            PickRow(Icon.TARGET, "내 위치", "지금 있는 곳에서 출발") {
                                scope.launch {
                                    host.currentLocation()?.let { from = Spot.Here(it); editing = null }
                                }
                            }
                        }
                    }
                    items(stations, key = { "s" + it.id }) { station ->
                        PickRow(Icon.TRAIN, "${station.name}역", station.lines.joinToString(" · ") { it.name }) {
                            if (editingNow == 0) from = Spot.AtStation(station) else to = Spot.AtStation(station)
                            editing = null
                        }
                    }
                    if (query.isNotBlank()) {
                        item {
                            PickRow(Icon.SEARCH, if (searching) "찾는 중" else "\"$query\" 장소 찾기", "지도 데이터에서 검색") {
                                searching = true
                                searchProblem = null
                                scope.launch {
                                    try {
                                        places = withContext(Dispatchers.Default) { PlaceSearch.search(query) }
                                        if (places.isEmpty()) searchProblem = "찾는 장소가 없습니다"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        searchProblem = "장소를 찾지 못했습니다. 연결을 확인해 주세요."
                                    }
                                    searching = false
                                }
                            }
                        }
                    }
                    searchProblem?.let { message ->
                        item { Label(message, size = 13.sp, color = Palette.Moderate, modifier = Modifier.padding(8.dp), maxLines = 2) }
                    }
                    items(places, key = { "p" + it.name + it.position.lat }) { place ->
                        PickRow(Icon.FLAG, place.name, place.detail) {
                            if (editingNow == 0) from = Spot.AtPlace(place) else to = Spot.AtPlace(place)
                            editing = null
                        }
                    }
                }
            }
            return@Column
        }
        when {
            from == null -> Box(Modifier.padding(14.dp)) {
                NoticeCard("출발 위치가 필요합니다", "위치 권한을 허용하거나 출발지를 직접 골라 주세요.", action = "출발지 고르기", onAction = { editing = 0 })
            }
            to == null -> Box(Modifier.padding(14.dp)) {
                NoticeCard("어디로 걸어갈까요", "역이나 장소를 고르면 계단과 횡단보도까지 따져서 길을 찾습니다.", action = "도착지 고르기", onAction = { editing = 1 })
            }
            route != null -> {
                ProfileChips(profile, routes) { profile = it }
                WalkMap(graph, route, Modifier.fillMaxWidth().weight(1f))
                WalkSummary(route, graph?.let { nearestExit(it, from, to) })
                LazyColumn(Modifier.fillMaxWidth().weight(0.72f).padding(horizontal = 14.dp)) {
                    items(route.steps) { step -> StepRow(step) }
                    item {
                        Label(
                            "지도 데이터 \u00A9 OpenStreetMap 기여자",
                            size = 10.sp,
                            color = Palette.TextFaint,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("길을 찾는 중", size = 14.sp, weight = FontWeight.Bold, color = Palette.TextSoft)
            }
            problem != null -> Box(Modifier.padding(14.dp)) { NoticeCard("길을 찾지 못했습니다", problem!!) }
            routes.isNotEmpty() && routes[profile] == null -> {
                ProfileChips(profile, routes) { profile = it }
                Box(Modifier.padding(14.dp)) {
                    NoticeCard(
                        if (profile == WalkProfile.STEP_FREE) "계단 없는 길이 없습니다" else "이 조건의 길이 없습니다",
                        "기록된 지도 정보로는 이 조건에 맞는 길을 찾지 못했습니다. 다른 길을 골라 보세요.",
                    )
                }
            }
            else -> Box(Modifier.padding(14.dp)) { NoticeCard("길을 찾지 못했습니다", "두 지점을 잇는 걷는 길이 지도 데이터에 없습니다.") }
        }
    }
}

/** When a walk ends at a station, which exit is closest to where it comes from. */
private fun nearestExit(graph: WalkGraph, from: Spot?, to: Spot?): StationExit? {
    val origin = from?.position ?: return null
    if (to !is Spot.AtStation) return null
    return graph.exits.filter { distanceMeters(it.position, to.position) < 400 }.minByOrNull { distanceMeters(it.position, origin) }
}

@Composable
private fun SpotRow(label: String, value: String?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, if (active) Palette.Accent else Palette.Hairline, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.width(34.dp))
        Label(value ?: "선택해 주세요", size = 15.sp, weight = FontWeight.Bold, color = if (value == null) Palette.TextFaint else Palette.Text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PickRow(icon: Icon, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, Palette.TextDim, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, size = 15.sp, weight = FontWeight.Bold)
            if (detail.isNotEmpty()) Label(detail, size = 12.sp, color = Palette.TextDim)
        }
    }
}

@Composable
private fun ProfileChips(selected: WalkProfile, routes: Map<WalkProfile, WalkRoute?>, onSelect: (WalkProfile) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WalkProfile.entries.forEach { profile ->
            val minutes = routes[profile]?.let { (it.seconds / 60).coerceAtLeast(1) }
            Chip(minutes?.let { "${profile.label} ${it}분" } ?: profile.label, profile == selected, { onSelect(profile) })
        }
    }
}

@Composable
private fun WalkSummary(route: WalkRoute, exit: StationExit?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("${(route.seconds / 60).coerceAtLeast(1)}", size = 34.sp, weight = FontWeight.Black, tight = true)
        Spacer(Modifier.width(2.dp))
        Label("분", size = 16.sp, weight = FontWeight.Bold, color = Palette.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val meters = route.meters.toInt()
            Label(if (meters >= 1000) "${(meters / 100) / 10.0}km" else "${meters}m", size = 14.sp, weight = FontWeight.Bold)
            Label(if (route.shortOfGoalMeters > 0) "남은 ${route.shortOfGoalMeters}m는 계단" else route.profile.hint, size = 11.sp, color = Palette.TextDim)
        }
        Metric(Icon.STAIRS, route.stairs.toString())
        Spacer(Modifier.width(10.dp))
        Metric(Icon.CROSSING, route.crossings.toString())
        exit?.ref?.let {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Label("${it}번", size = 14.sp, weight = FontWeight.Bold)
                Label("가까운 출구", size = 10.sp, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun Metric(icon: Icon, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, Palette.TextDim, size = 18.dp)
        Spacer(Modifier.width(3.dp))
        Label(value, size = 14.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun StepRow(step: WalkStep) {
    val icon = when (step.kind) {
        StepKind.STAIRS -> Icon.STAIRS
        StepKind.CROSSING -> Icon.CROSSING
        StepKind.ARRIVE -> Icon.FLAG
        StepKind.START -> Icon.WALK
        StepKind.UNDERPASS, StepKind.OVERPASS -> Icon.ROUTE
        else -> Icon.CHEVRON
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Palette.PanelHigh, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(icon, if (step.kind == StepKind.STAIRS) Palette.Moderate else Palette.TextSoft, size = 16.dp)
        }
        Spacer(Modifier.width(10.dp))
        Label(step.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun WalkMap(graph: WalkGraph?, route: WalkRoute, modifier: Modifier) {
    val density = LocalDensity.current.density
    val network = LocalServices.current.network
    val fonts = dev.aetherx86.ferrite.ui.common.LocalFonts.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val now = remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now.longValue = nowMillis() } }
    val south = route.points.minOf { it.lat }
    val north = route.points.maxOf { it.lat }
    val west = route.points.minOf { it.lon }
    val east = route.points.maxOf { it.lon }
    val projection = remember(route) { LocalProjection(LatLng((south + north) / 2, (west + east) / 2)) }
    val routePath = remember(route, projection) {
        Path().apply {
            route.points.forEachIndexed { i, p ->
                val x = projection.x(p.lon).toFloat()
                val y = projection.y(p.lat).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val base = remember(projection) {
        BaseMap(scope, unitsPerKm = 1000f) { lon, lat -> projection.x(lon).toFloat() to projection.y(lat).toFloat() }
    }
    var userZoom by remember(route) { mutableStateOf(1f) }
    var pan by remember(route) { mutableStateOf(Offset.Zero) }
    val spanX = (projection.x(east) - projection.x(west)).toFloat().coerceAtLeast(60f)
    val spanY = (projection.y(south) - projection.y(north)).toFloat().coerceAtLeast(60f)
    val midX = ((projection.x(east) + projection.x(west)) / 2).toFloat()
    val midY = ((projection.y(south) + projection.y(north)) / 2).toFloat()

    Box(
        modifier.clipToBounds().pointerInput(route) {
            detectTransformGestures { centroid, p, zoom, _ ->
                val next = (userZoom * zoom).coerceIn(0.5f, 12f)
                val applied = next / userZoom
                // Zoom around the fingers, not around the middle of the map.
                val c = Offset(size.width / 2f, size.height / 2f)
                pan = (pan + c - centroid) * applied + centroid - c + p
                userZoom = next
            }
        },
    ) {
        // The map only redraws when it moves or a tile arrives. The route above it animates every frame.
        Canvas(Modifier.fillMaxSize()) {
            val fit = minOf(size.width * 0.8f / spanX, size.height * 0.78f / spanY)
            val s = fit * userZoom
            val center = Offset(size.width / 2 + pan.x, size.height / 2 + pan.y)
            val leftWorld = midX - center.x / s
            val rightWorld = midX + (size.width - center.x) / s
            val topWorld = midY - center.y / s
            val bottomWorld = midY + (size.height - center.y) / s
            base.draw(
                this,
                west = projection.lon(leftWorld.toDouble()),
                north = projection.lat(topWorld.toDouble()),
                east = projection.lon(rightWorld.toDouble()),
                south = projection.lat(bottomWorld.toDouble()),
                pixelsPerUnit = s,
                transform = { block -> withTransformTo(center, s, midX, midY) { block() } },
                toScreen = { x, y -> Offset(center.x + (x - midX) * s, center.y + (y - midY) * s) },
                measurer = measurer,
                family = fonts.sans,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val fit = minOf(size.width * 0.8f / spanX, size.height * 0.78f / spanY)
            val s = fit * userZoom
            val center = Offset(size.width / 2 + pan.x, size.height / 2 + pan.y)
            val px = 1f / s
            fun at(p: LatLng) = Offset(center.x + (projection.x(p.lon).toFloat() - midX) * s, center.y + (projection.y(p.lat).toFloat() - midY) * s)
            withTransformTo(center, s, midX, midY) {
                drawPath(routePath, Palette.Accent.copy(alpha = 0.3f), style = Stroke(12f * density * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(routePath, Palette.Accent, style = Stroke(5.5f * density * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val gap = 22f * density * px
                val phase = -((now.longValue % 900L) / 900f) * gap
                drawPath(
                    routePath, Color.White.copy(alpha = 0.9f),
                    style = Stroke(1.7f * density * px, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(gap * 0.28f, gap * 0.72f), phase)),
                )
            }
            for (step in route.steps) {
                if (step.kind == StepKind.STAIRS) {
                    val c = at(step.position)
                    drawCircle(Palette.Ink, 6f * density, c)
                    drawCircle(Palette.Moderate, 6f * density, c, style = Stroke(2f * density))
                }
            }
            for (exit in graph?.exits.orEmpty()) {
                val c = at(exit.position)
                if (c.x in 0f..size.width && c.y in 0f..size.height) drawCircle(Color(0xFFB9C7DB), 3f * density, c)
            }
            // Stations are the landmarks everyone knows.
            val labelSize = (12f * density).toSp()
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            for (station in network.stations) {
                val c = at(station.location)
                if (c.x < 0f || c.y < 0f || c.x > size.width || c.y > size.height) continue
                drawCircle(Color.White, 4.6f * density, c)
                drawCircle(Color(station.lines.first().colorArgb), 3.2f * density, c)
                val text = measurer.measure(station.name, dev.aetherx86.ferrite.ui.common.textStyle(fonts.sans, labelSize, FontWeight.Black, Color.White), softWrap = false, maxLines = 1)
                val left = (c.x + 8f * density).coerceAtMost(size.width - text.size.width - 2f)
                val box = androidx.compose.ui.geometry.Rect(left, c.y - text.size.height / 2f, left + text.size.width, c.y + text.size.height / 2f)
                if (placed.none { it.overlaps(box) }) {
                    placed += box
                    drawRoundRect(Palette.Ink.copy(alpha = 0.72f), box.topLeft - Offset(4f, 1f), androidx.compose.ui.geometry.Size(box.width + 8f, box.height + 2f), androidx.compose.ui.geometry.CornerRadius(6f))
                    drawText(text, topLeft = box.topLeft)
                }
            }
            val start = at(route.points.first())
            drawCircle(Color.White, 8f * density, start)
            drawCircle(Palette.Accent, 5.5f * density, start)
            val end = at(route.points.last())
            drawCircle(Palette.Accent.copy(alpha = 0.3f), 15f * density, end)
            drawCircle(Color.White, 8f * density, end)
            drawCircle(Palette.Ink, 3.5f * density, end)
        }
        Label(
            "\u00A9 OpenFreeMap \u00A9 OpenMapTiles \u00A9 OpenStreetMap 기여자",
            size = 9.sp,
            color = Palette.TextDim,
            modifier = Modifier.align(Alignment.BottomStart).background(Palette.Ink.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.withTransformTo(center: Offset, s: Float, midX: Float, midY: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.save()
    canvas.translate(center.x, center.y)
    canvas.scale(s, s)
    canvas.translate(-midX, -midY)
    block()
    canvas.restore()
}

