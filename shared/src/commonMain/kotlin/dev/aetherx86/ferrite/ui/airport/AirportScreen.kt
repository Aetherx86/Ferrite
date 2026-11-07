package dev.aetherx86.ferrite.ui.airport

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.airport.Flight
import dev.aetherx86.ferrite.airport.Flights
import dev.aetherx86.ferrite.airport.Movement
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.SearchField
import dev.aetherx86.ferrite.ui.common.SourceTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val REFRESH_MS = 60_000L

/** The airport's flight board, on a phone. */
@Composable
fun AirportScreen(onBoard: (Station, Line?) -> Unit, onSettings: () -> Unit, demo: Boolean = false) {
    val services = LocalServices.current
    val settings = services.settings
    var movement by remember { mutableStateOf(Movement.DEPARTURE) }
    var terminal by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    var flights by remember { mutableStateOf<List<Flight>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showDemo by remember { mutableStateOf(demo || settings.demoMode) }
    var generation by remember { mutableStateOf(0) }
    var clock by remember { mutableLongStateOf(nowMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            clock = nowMillis()
            delay(1000)
        }
    }

    LaunchedEffect(movement, settings.dataKey, showDemo) {
        flights = null
        problem = null
        if (showDemo) {
            flights = Flights.demo(movement, nowMillis())
            generation++
            return@LaunchedEffect
        }
        if (settings.dataKey.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                flights = Flights.fetch(settings.dataKey, movement, nowMillis())
                problem = null
                generation++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: "운항 정보를 받지 못했습니다"
            }
            delay(REFRESH_MS)
        }
    }

    val time = kstOf(clock)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val room = maxWidth.value / fontScale
        val wide = room > 560f
        // With little room, or large text, the flight number moves under the city and the terminal column goes.
        val compact = room < 380f
        Column(Modifier.widthIn(max = 980.dp).fillMaxSize().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label("인천공항", size = 26.sp, weight = FontWeight.Black)
                    Label("Incheon International Airport", size = 11.sp, color = Palette.TextDim)
                }
                Label("${two(time.hour)}:${two(time.minute)}", size = 28.sp, weight = FontWeight.Black, tight = true)
                Spacer(Modifier.width(3.dp))
                Label(two(time.second), size = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(bottom = 12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Movement.entries.forEach { m -> Chip(m.label, movement == m, { movement = m }) }
                Spacer(Modifier.weight(1f))
                listOf("전체", "T1", "탑승동", "T2").forEach { t ->
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (terminal == t) Palette.PanelHigh else Color.Transparent).clickable { terminal = t }.padding(horizontal = 7.dp, vertical = 7.dp),
                    ) {
                        Label(t, size = 12.sp, weight = FontWeight.Bold, color = if (terminal == t) Palette.Text else Palette.TextDim)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SearchField(query, { query = it }, "편명, 도시, 항공사")
            Spacer(Modifier.height(8.dp))
            val list = flights
            when {
                !showDemo && settings.dataKey.isBlank() -> NoticeCard(
                    "운항 정보 키가 필요합니다",
                    "공항 운항 정보는 공공데이터포털에서 무료로 받는 키로 제공됩니다. 설정에 키를 넣으면 실제 전광판이 나옵니다.",
                    action = "키 넣기",
                    onAction = onSettings,
                    secondary = "시연 보기",
                    onSecondary = { showDemo = true },
                )
                problem != null -> NoticeCard("운항 정보를 받지 못했습니다", problem!!, action = "설정 확인", onAction = onSettings)
                list == null -> Label("불러오는 중", size = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(8.dp))
                else -> {
                    val filtered = list.filter { f ->
                        (terminal == "전체" || f.terminal == terminal) &&
                            (query.isBlank() || listOf(f.number, f.place, f.airline, f.placeCode).any { it.contains(query.trim(), ignoreCase = true) })
                    }
                    HeaderRow(movement, wide, compact, showDemo)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(filtered, key = { _, f -> f.number + f.scheduled }) { index, flight ->
                            FlightRow(flight, wide, compact, index, generation)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline.copy(alpha = 0.55f)))
                        }
                        item {
                            if (filtered.isEmpty()) Label("맞는 항공편이 없습니다", size = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(16.dp))
                            Spacer(Modifier.height(10.dp))
                            val rail = services.network.stations.firstOrNull { it.name == "인천공항1터미널" }
                            if (rail != null) {
                                GhostButton("공항철도 전광판", { onBoard(rail, services.network.line("AR")) }, Modifier.fillMaxWidth(), Icon.TRAIN)
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(movement: Movement, wide: Boolean, compact: Boolean, demo: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Heading("시각", Modifier.width(columnWidth(64f)))
        if (!compact) Heading("편명", Modifier.width(columnWidth(if (wide) 96f else 74f)))
        Heading(movement.placeLabel, Modifier.weight(1f))
        if (demo) {
            SourceTag("시연", false)
            Spacer(Modifier.width(8.dp))
        }
        if (wide) {
            Heading(if (movement == Movement.DEPARTURE) "체크인" else "수하물", Modifier.width(columnWidth(92f)))
            Heading(if (movement == Movement.DEPARTURE) "탑승구" else "출구", Modifier.width(columnWidth(60f)))
        } else {
            Heading(if (movement == Movement.DEPARTURE) "탑승구" else "출구", Modifier.width(columnWidth(46f)))
        }
        if (!compact) Heading("터미널", Modifier.width(columnWidth(50f)), TextAlign.Center)
        Heading("현황", Modifier.width(columnWidth(62f)), TextAlign.End)
    }
}

/** A column as wide as its text needs, at whatever text size is set. */
@Composable
private fun columnWidth(atNormalText: Float): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { atNormalText.sp.toDp() }

@Composable
private fun Heading(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Label(text, size = 11.sp, weight = FontWeight.Bold, color = Palette.TextFaint, modifier = modifier, align = align)
}

/** A row turns over like a split-flap sign whenever the board refreshes. */
@Composable
private fun FlightRow(flight: Flight, wide: Boolean, compact: Boolean, index: Int, generation: Int) {
    val flip = remember { Animatable(0f) }
    LaunchedEffect(generation) {
        delay(index * 45L)
        flip.snapTo(-82f)
        flip.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().graphicsLayer {
            rotationX = flip.value
            cameraDistance = 22f * density
            alpha = 1f - kotlin.math.abs(flip.value) / 140f
        }.clickable { expanded = !expanded }.padding(vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(columnWidth(64f))) {
                val changed = flight.isDelayed
                Label(formatTime(flight.estimated.ifBlank { flight.scheduled }), size = 15.sp, weight = FontWeight.Bold, mono = true, color = if (changed) Palette.Moderate else Palette.Text)
                if (changed) Label(formatTime(flight.scheduled), size = 10.sp, mono = true, color = Palette.TextFaint)
            }
            if (!compact) Label(flight.number, size = 13.sp, weight = FontWeight.Bold, mono = true, color = Palette.TextSoft, modifier = Modifier.width(columnWidth(if (wide) 96f else 74f)))
            Column(Modifier.weight(1f)) {
                Label(flight.place, size = 15.sp, weight = FontWeight.Bold)
                Label(if (compact) "${flight.number} \u00B7 ${flight.airline}" else flight.airline, size = 11.sp, color = Palette.TextDim)
            }
            if (wide) {
                Label(flight.counter.ifBlank { "-" }, size = 13.sp, mono = true, color = Palette.TextSoft, modifier = Modifier.width(columnWidth(92f)))
                Label(flight.gate.ifBlank { flight.exit }.ifBlank { "-" }, size = 14.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.width(columnWidth(60f)))
            } else {
                val shown = if (flight.movement == Movement.DEPARTURE) flight.gate else flight.exit.ifBlank { flight.gate }
                Label(shown.ifBlank { "-" }, size = 14.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.width(columnWidth(46f)))
            }
            if (!compact) Label(flight.terminal, size = 11.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.width(columnWidth(50f)), align = TextAlign.Center)
            StatusText(flight.status, Modifier.width(columnWidth(62f)))
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            val details = buildList {
                if (flight.placeCode.isNotBlank()) add("공항 코드 ${flight.placeCode}")
                if (flight.counter.isNotBlank()) add((if (flight.movement == Movement.DEPARTURE) "체크인 " else "수하물 수취대 ") + flight.counter)
                if (flight.gate.isNotBlank()) add("탑승구 ${flight.gate}")
                if (flight.exit.isNotBlank()) add("출구 ${flight.exit}")
                if (flight.isDelayed) add("변경 전 ${formatTime(flight.scheduled)}")
            }
            Label(details.joinToString(" · "), size = 12.sp, color = Palette.TextSoft, maxLines = 3, modifier = Modifier.padding(start = columnWidth(64f)))
        }
    }
}

@Composable
private fun StatusText(status: String, modifier: Modifier) {
    val color = when {
        "결항" in status || "회항" in status -> Palette.Crowded
        "지연" in status -> Palette.Moderate
        "탑승" in status || "도착" in status || "착륙" in status || "출발" in status -> Palette.Roomy
        else -> Palette.TextDim
    }
    Label(status.ifBlank { "정시" }, size = 12.sp, weight = FontWeight.Bold, color = color, modifier = modifier, align = TextAlign.End)
}

private fun formatTime(hhmm: String): String = if (hhmm.length >= 4) hhmm.substring(0, 2) + ":" + hhmm.substring(2, 4) else hhmm

