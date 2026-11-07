package dev.aetherx86.ferrite.ui.trip

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.drive.DriveRoute
import dev.aetherx86.ferrite.drive.DriveService
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.trip.TripDay
import dev.aetherx86.ferrite.trip.TripStop
import dev.aetherx86.ferrite.trip.TripStore
import dev.aetherx86.ferrite.trip.reorder
import dev.aetherx86.ferrite.trip.timeline
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.PlacePicker
import dev.aetherx86.ferrite.ui.common.PrimaryButton
import dev.aetherx86.ferrite.ui.common.textStyle
import dev.aetherx86.ferrite.ui.drive.StepButton
import dev.aetherx86.ferrite.ui.walk.WalkMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun clock(minute: Int) = "${two(minute / 60 % 24)}:${two(minute % 60)}"

private fun won(value: Int): String {
    val text = value.toString()
    return if (text.length > 3) text.dropLast(3) + "," + text.takeLast(3) else text
}

/** A trip by days: places to go, the best order to see them in, when you get there, and what it costs. */
@Composable
fun TripScreen(requested: List<Station> = emptyList()) {
    val host = LocalHost.current
    val store = remember { TripStore(host.store) }
    var plan by remember { mutableStateOf(store.load()) }
    var dayIndex by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<DriveRoute?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var optimizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val day = plan.days[dayIndex.coerceIn(0, plan.days.lastIndex)]

    fun update(next: TripDay) {
        plan = plan.copy(days = plan.days.mapIndexed { i, d -> if (i == dayIndex) next else d })
        store.save(plan)
    }

    // A link can hand over a list of stations to plan around.
    LaunchedEffect(requested) {
        if (requested.isNotEmpty()) {
            update(day.copy(stops = requested.map { TripStop("${it.name}역", it.location.lat, it.location.lon) }))
        }
    }

    LaunchedEffect(day.stops.map { it.lat to it.lon }, plan.walking) {
        route = null
        problem = null
        if (day.stops.size < 2) return@LaunchedEffect
        try {
            route = DriveService.walkingOrDriving(day.stops.map { it.position }, plan.walking)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "이동 시간을 계산하지 못했습니다. 연결을 확인해 주세요."
        }
    }

    if (adding) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("들를 곳 추가", size = 16.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                GhostButton("닫기", { adding = false })
            }
            Spacer(Modifier.height(8.dp))
            PlacePicker(allowHere = day.stops.isEmpty(), modifier = Modifier.fillMaxSize()) { name, at ->
                update(day.copy(stops = day.stops + TripStop(name, at.lat, at.lon)))
                adding = false
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            plan.days.forEachIndexed { i, _ -> Chip("${i + 1}일차", i == dayIndex, { dayIndex = i }) }
            Chip("+", false, {
                plan = plan.copy(days = plan.days + TripDay())
                store.save(plan)
                dayIndex = plan.days.lastIndex
            })
            Spacer(Modifier.width(8.dp))
            Chip(if (plan.walking) "걸어서" else "차로", false, {
                plan = plan.copy(walking = !plan.walking)
                store.save(plan)
            })
        }
        val current = route
        if (current != null) WalkMap(null, current.route, Modifier.fillMaxWidth().weight(0.8f))
        val legSeconds = current?.legs?.map { it.first }.orEmpty()
        val timed = timeline(day, legSeconds)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp)) {
            if (day.stops.isEmpty()) {
                item {
                    NoticeCard("어디를 들를까요", "가고 싶은 곳을 순서 상관없이 넣으면 가장 효율적인 순서로 정렬하고, 도착 시각과 비용을 정리합니다. 영업시간은 반영하지 않습니다.")
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(timed) { index, item ->
                if (item.travelMinutes > 0) {
                    Label("이동 ${item.travelMinutes}분", size = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 44.dp, top = 3.dp, bottom = 3.dp))
                }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).background(Palette.Accent, CircleShape), contentAlignment = Alignment.Center) {
                            Label("${index + 1}", size = 13.sp, weight = FontWeight.Black, color = Palette.Ink)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Label(item.stop.name, size = 15.sp, weight = FontWeight.Bold)
                            Label("${clock(item.arriveMinute)} 도착 · ${clock(item.leaveMinute)} 출발", size = 12.sp, color = Palette.TextDim)
                        }
                        Label("삭제", size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.clickable { update(day.copy(stops = day.stops.filterIndexed { i, _ -> i != index })) }.padding(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Adjust("머무름 ${item.stop.stayMinutes}분", { change(day, index) { it.copy(stayMinutes = (it.stayMinutes - 30).coerceAtLeast(0)) }.let(::update) }, { change(day, index) { it.copy(stayMinutes = it.stayMinutes + 30) }.let(::update) })
                        Spacer(Modifier.width(6.dp))
                        Adjust("비용 ${won(item.stop.cost)}원", { change(day, index) { it.copy(cost = (it.cost - 5000).coerceAtLeast(0)) }.let(::update) }, { change(day, index) { it.copy(cost = it.cost + 5000) }.let(::update) })
                    }
                    Spacer(Modifier.height(6.dp))
                    MemoField(item.stop.memo) { text -> change(day, index) { it.copy(memo = text) }.let(::update) }
                }
                Spacer(Modifier.height(4.dp))
            }
            item {
                problem?.let { Label(it, size = 13.sp, color = Palette.Moderate, maxLines = 2) }
                Spacer(Modifier.height(6.dp))
                val total = day.stops.sumOf { it.cost }
                if (day.stops.isNotEmpty()) {
                    val end = timed.lastOrNull()?.leaveMinute ?: day.startMinute
                    Label("${clock(day.startMinute)} 시작 · ${clock(end)} 끝 · 합계 ${won(total)}원", size = 14.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryButton("들를 곳 추가", { adding = true }, Modifier.weight(1f))
                    if (day.stops.size >= 3) {
                        GhostButton(if (optimizing) "계산 중" else "동선 최적화", {
                            optimizing = true
                            scope.launch {
                                try {
                                    val order = DriveService.bestOrder(day.stops.map { it.position }, plan.walking)
                                    update(day.copy(stops = reorder(day.stops, order)))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    problem = "순서를 계산하지 못했습니다."
                                }
                                optimizing = false
                            }
                        }, Modifier.weight(1f), Icon.ROUTE)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (day.stops.size >= 3) Label("첫 곳과 마지막 곳은 그대로 두고 그 사이의 순서를 정리합니다.", size = 11.sp, color = Palette.TextDim, maxLines = 2)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun change(day: TripDay, index: Int, edit: (TripStop) -> TripStop): TripDay =
    day.copy(stops = day.stops.mapIndexed { i, s -> if (i == index) edit(s) else s })

@Composable
private fun Adjust(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.PanelHigh), verticalAlignment = Alignment.CenterVertically) {
        StepButton("-", onMinus)
        Label(label, size = 12.sp, weight = FontWeight.Bold)
        StepButton("+", onPlus)
    }
}

@Composable
private fun MemoField(value: String, onChange: (String) -> Unit) {
    val family = LocalFonts.current.sans
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.InkRaised).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Label("메모", size = 13.sp, color = Palette.TextFaint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = textStyle(family, 13.sp, FontWeight.Normal, Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
