package dev.aetherx86.ferrite.ui.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.route.Leg
import dev.aetherx86.ferrite.route.RoutePlanner
import dev.aetherx86.ferrite.route.Trip
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.IconButton
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LineMark
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.PrimaryButton
import dev.aetherx86.ferrite.ui.common.SearchField
import dev.aetherx86.ferrite.ui.common.visibleOnDark
import dev.aetherx86.ferrite.ui.home.StationRow
import dev.aetherx86.ferrite.ui.walk.WalkScreen
import kotlinx.coroutines.delay

/** Going somewhere: by rail with changes and fares, or on foot. */
@Composable
fun RouteScreen(
    initialFrom: Station?,
    initialTo: Station?,
    walkTo: Station?,
    walkFrom: Station?,
    onBoard: (Station, Line?) -> Unit,
    onShowOnMap: (Trip) -> Unit,
) {
    var mode by remember(walkTo) { mutableStateOf(if (walkTo != null) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("지하철", mode == 0, { mode = 0 })
            Chip("걷기", mode == 1, { mode = 1 })
        }
        if (mode == 0) {
            RailPlanner(initialFrom, initialTo, onBoard, onShowOnMap)
        } else {
            key(walkTo, walkFrom) { WalkScreen(toStation = walkTo, fromStation = walkFrom) }
        }
    }
}

@Composable
private fun RailPlanner(initialFrom: Station?, initialTo: Station?, onBoard: (Station, Line?) -> Unit, onShowOnMap: (Trip) -> Unit) {
    val services = LocalServices.current
    val host = LocalHost.current
    val network = services.network
    var from by remember(initialFrom) { mutableStateOf(initialFrom) }
    var to by remember(initialTo) { mutableStateOf(initialTo) }
    var editing by remember(initialFrom, initialTo) { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    val planner = remember(network) { RoutePlanner(network) }
    val options = remember(from, to) {
        val a = from
        val b = to
        if (a == null || b == null) emptyList() else planner.options(a, b)
    }
    var choice by remember(options) { mutableStateOf(0) }
    var tracking by remember(options) { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EndpointRow("출발", from, editing == 0) { editing = if (editing == 0) null else 0; query = "" }
                EndpointRow("도착", to, editing == 1) { editing = if (editing == 1) null else 1; query = "" }
            }
            IconButton(Icon.SWAP, "출발 도착 바꾸기", { val t = from; from = to; to = t })
        }
        val editingNow = editing
        if (editingNow != null) {
            Spacer(Modifier.height(8.dp))
            SearchField(query, { query = it }, "역 이름 또는 초성")
            val results = remember(query) { if (query.isBlank()) emptyList() else network.search(query) }
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.id }) { station ->
                    StationRow(station) {
                        if (editingNow == 0) from = station else to = station
                        editing = null
                        query = ""
                    }
                }
                if (query.isBlank()) {
                    val recents = services.settings.recents.mapNotNull(network::station)
                    items(recents, key = { "r" + it.id }) { station ->
                        StationRow(station, "최근") {
                            if (editingNow == 0) from = station else to = station
                            editing = null
                        }
                    }
                }
            }
            return@Column
        }
        if (from == null || to == null) {
            Spacer(Modifier.height(14.dp))
            NoticeCard("어디로 갈까요", "출발역과 도착역을 고르면 전국 도시철도 안에서 빠른 길과 환승이 적은 길을 찾습니다. 인터넷 연결 없이도 됩니다.")
            return@Column
        }
        if (options.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            NoticeCard(
                if (from?.id == to?.id) "같은 역입니다" else "철도로 이어지지 않습니다",
                if (from?.id == to?.id) "출발역과 도착역을 다르게 골라 주세요." else "서로 다른 도시의 도시철도망은 연결되어 있지 않습니다.",
            )
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, (preference, _) -> Chip(preference.label, index == choice, { choice = index }) }
        }
        val trip = options[choice.coerceIn(0, options.lastIndex)].second
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            item { TripSummary(trip) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryButton(if (tracking == null) "하차 알림" else "알림 끄기", { tracking = if (tracking == null) nowMillis() else null }, Modifier.weight(1.3f), Icon.BELL)
                    GhostButton("지도", { onShowOnMap(trip) }, Modifier.weight(0.8f), Icon.MAP)
                    GhostButton("공유", { host.share(shareText(trip)) }, Modifier.weight(0.8f), Icon.SHARE)
                }
            }
            tracking?.let { started ->
                item { Spacer(Modifier.height(8.dp)); TripTracker(trip, started) }
            }
            item { Spacer(Modifier.height(8.dp)) }
            trip.legs.forEachIndexed { index, leg ->
                item { LegCard(leg) { onBoard(leg.from, leg.line) } }
                trip.transfers.getOrNull(index)?.let { transfer ->
                    item {
                        Row(Modifier.padding(start = 22.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(Icon.WALK, Palette.TextDim, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Label("${transfer.toLine.name}(으)로 환승, 걷는 시간 ${formatDuration(transfer.seconds)}", size = 13.sp, color = Palette.TextSoft, maxLines = 2)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes < 60) "${minutes.coerceAtLeast(1)}분" else "${minutes / 60}시간 ${minutes % 60}분"
}

private fun shareText(trip: Trip): String {
    val arrive = kstOf(nowMillis() + trip.seconds * 1000L)
    val path = trip.legs.joinToString(" > ") { "${it.line.name} ${it.from.name}-${it.to.name}" }
    return "${trip.legs.first().from.name}역에서 ${trip.legs.last().to.name}역까지 ${formatDuration(trip.seconds)}, ${two(arrive.hour)}:${two(arrive.minute)} 도착 예상\n$path"
}

@Composable
private fun EndpointRow(label: String, station: Station?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, if (active) Palette.Accent else Palette.Hairline, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.width(34.dp))
        if (station != null) {
            station.lines.take(3).forEach { LineMark(it, 18.dp); Spacer(Modifier.width(3.dp)) }
            Spacer(Modifier.width(4.dp))
        }
        Label(station?.name ?: "역 선택", size = 15.sp, weight = FontWeight.Bold, color = if (station == null) Palette.TextFaint else Palette.Text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TripSummary(trip: Trip) {
    val arrive = kstOf(nowMillis() + trip.seconds * 1000L)
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            val minutes = (trip.seconds + 30) / 60
            if (minutes >= 60) {
                Label("${minutes / 60}", size = 38.sp, weight = FontWeight.Black, tight = true)
                Label("시간 ", size = 17.sp, weight = FontWeight.Bold, color = Palette.Accent)
                Label("${minutes % 60}", size = 38.sp, weight = FontWeight.Black, tight = true)
            } else {
                Label("$minutes", size = 38.sp, weight = FontWeight.Black, tight = true)
            }
            Label("분", size = 17.sp, weight = FontWeight.Bold, color = Palette.Accent)
            Spacer(Modifier.weight(1f))
            Label("${two(arrive.hour)}:${two(arrive.minute)} 도착 예상", size = 13.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        val details = listOfNotNull(
            "환승 ${trip.transferCount}회",
            "${(trip.meters / 100).toInt() / 10.0}km",
            trip.fare?.let { "약 ${formatWon(it)}원" },
        ).joinToString(" \u00B7 ")
        Label(details, size = 12.sp, color = Palette.TextDim, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        TripBar(trip)
    }
}

private fun formatWon(value: Int): String {
    val text = value.toString()
    return if (text.length > 3) text.dropLast(3) + "," + text.takeLast(3) else text
}

/** The trip as one bar, with each line as long as the time on it. */
@Composable
private fun TripBar(trip: Trip) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val total = trip.legs.sumOf { it.seconds }.toFloat().coerceAtLeast(1f)
        var x = 0f
        val gap = 3.dp.toPx()
        val available = size.width - gap * (trip.legs.size - 1)
        trip.legs.forEach { leg ->
            val w = available * leg.seconds / total
            drawLine(visibleOnDark(Color(leg.line.colorArgb)), Offset(x + size.height / 2, size.height / 2), Offset((x + w - size.height / 2).coerceAtLeast(x + size.height / 2), size.height / 2), size.height, StrokeCap.Round)
            x += w + gap
        }
    }
}

@Composable
private fun LegCard(leg: Leg, onBoard: () -> Unit) {
    val color = visibleOnDark(Color(leg.line.colorArgb))
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineMark(leg.line, 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Label(leg.line.name, size = 14.sp, weight = FontWeight.Bold, color = color)
                Label("${leg.stops}개 역 · ${formatDuration(leg.seconds)}", size = 12.sp, color = Palette.TextDim)
            }
            GhostButton("전광판", onBoard)
        }
        Spacer(Modifier.height(10.dp))
        StopLine(leg.from.name, color, true)
        if (leg.stations.size > 2) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.width(3.dp).height(20.dp).background(color)) }
                Spacer(Modifier.width(10.dp))
                Label(if (expanded) "접기" else "거쳐 가는 역 ${leg.stations.size - 2}개", size = 12.sp, color = Palette.TextDim)
            }
            if (expanded) {
                leg.stations.drop(1).dropLast(1).forEach { StopLine(it.name, color, false) }
            }
        }
        StopLine(leg.to.name, color, true)
    }
}

@Composable
private fun StopLine(name: String, color: Color, major: Boolean) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (major) 12.dp else 7.dp).background(Palette.Panel, CircleShape).border(if (major) 3.dp else 2.dp, color, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Label(name, size = if (major) 15.sp else 13.sp, weight = if (major) FontWeight.Bold else FontWeight.Normal, color = if (major) Palette.Text else Palette.TextSoft)
    }
}

/**
 * Counts down a trip and chimes before each stop to get off at. Tunnels hide GPS, so this goes by the clock, from
 * the moment you start it, and says so.
 */
@Composable
private fun TripTracker(trip: Trip, startedAt: Long) {
    val host = LocalHost.current
    var now by remember { mutableLongStateOf(nowMillis()) }
    val played = remember(startedAt) { HashSet<Int>() }
    LaunchedEffect(startedAt) {
        while (true) {
            now = nowMillis()
            var cursor = 0
            trip.legs.forEachIndexed { index, leg ->
                cursor += leg.seconds
                val elapsed = ((now - startedAt) / 1000).toInt()
                if (elapsed >= cursor - WARN_SECONDS && played.add(index)) host.play(BoardSound.APPROACH)
                cursor += trip.transfers.getOrNull(index)?.seconds ?: 0
            }
            delay(1000)
        }
    }
    val elapsed = ((now - startedAt) / 1000).toInt()
    var cursor = 0
    var message = "도착했습니다"
    for ((index, leg) in trip.legs.withIndex()) {
        val end = cursor + leg.seconds
        if (elapsed < end) {
            val remain = end - elapsed
            message = "${leg.to.name}에서 내립니다 · 약 ${formatDuration(remain)} 후"
            break
        }
        cursor = end + (trip.transfers.getOrNull(index)?.seconds ?: 0)
        if (elapsed < cursor) {
            message = "${trip.transfers[index].toLine.name}(으)로 갈아타는 중"
            break
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icon.BELL, Palette.Accent, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Label(message, size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
        }
        Spacer(Modifier.height(4.dp))
        Label("지하에서는 위치를 알 수 없어 시간으로 계산합니다. 내리기 약 1분 전에 소리로 알려 드리며, 앱을 켜 두어야 합니다.", size = 11.sp, color = Palette.TextDim, maxLines = 3)
    }
}

private const val WARN_SECONDS = 70

