package dev.aetherx86.ferrite.ui.bus

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.bus.BusArrival
import dev.aetherx86.ferrite.bus.BusFeed
import dev.aetherx86.ferrite.bus.BusStop
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.live.countdownParts
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.IconButton
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.SourceTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Stops near you and when the next buses come. */
@Composable
fun BusScreen(onSettings: () -> Unit, demo: Boolean = false) {
    val services = LocalServices.current
    val host = LocalHost.current
    val settings = services.settings
    var showDemo by remember { mutableStateOf(demo || settings.demoMode) }
    var here by remember { mutableStateOf<LatLng?>(null) }
    var stops by remember { mutableStateOf<List<BusStop>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<BusStop?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(settings.dataKey, showDemo, tick) {
        if (!showDemo && settings.dataKey.isBlank()) return@LaunchedEffect
        problem = null
        val point = host.currentLocation() ?: if (showDemo) LatLng(37.5347, 126.9729) else null
        if (point == null) {
            problem = "위치를 알 수 없어 주변 정류장을 찾지 못했습니다"
            return@LaunchedEffect
        }
        here = point
        try {
            stops = if (showDemo) BusFeed.demoStops(point) else BusFeed.nearbyStops(settings.dataKey, point)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "정류장 정보를 받지 못했습니다"
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("버스", size = 26.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (showDemo) SourceTag("시연", false)
                IconButton(Icon.ROTATE, "새로고침", { tick++ })
            }
            Spacer(Modifier.height(6.dp))
            val list = stops
            when {
                !showDemo && settings.dataKey.isBlank() -> NoticeCard(
                    "버스 도착 키가 필요합니다",
                    "버스 도착 정보는 공공데이터포털의 무료 키로 제공됩니다. 서울은 서울시 버스 정보, 그 밖의 도시는 국가대중교통정보센터 서비스를 신청해 설정에 넣어 주세요.",
                    action = "키 넣기",
                    onAction = onSettings,
                    secondary = "시연 보기",
                    onSecondary = { showDemo = true },
                )
                problem != null -> NoticeCard("정보를 받지 못했습니다", problem!!, action = "다시 시도", onAction = { tick++ })
                list == null -> Label("주변 정류장을 찾는 중", size = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(4.dp))
                list.isEmpty() -> NoticeCard("주변에 정류장이 없습니다", "500m 안에서 찾지 못했습니다.")
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.id }) { stop ->
                        StopCard(stop, selected?.id == stop.id, showDemo) { selected = if (selected?.id == stop.id) null else stop }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StopCard(stop: BusStop, open: Boolean, demo: Boolean, onClick: () -> Unit) {
    val settings = LocalServices.current.settings
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icon.BUS, Palette.TextSoft, size = 20.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Label(stop.name, size = 16.sp, weight = FontWeight.Bold)
                Label(listOf(stop.code, "${stop.meters}m").filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp, color = Palette.TextDim)
            }
        }
        if (open) {
            var arrivals by remember(stop) { mutableStateOf<List<BusArrival>?>(null) }
            var problem by remember(stop) { mutableStateOf<String?>(null) }
            LaunchedEffect(stop) {
                while (true) {
                    try {
                        arrivals = if (demo) BusFeed.demoArrivals() else BusFeed.arrivals(settings.dataKey, stop)
                        problem = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem = e.message ?: "도착 정보를 받지 못했습니다"
                    }
                    delay(30_000)
                }
            }
            Spacer(Modifier.height(8.dp))
            problem?.let { Label(it, size = 13.sp, color = Palette.Moderate, maxLines = 2) }
            arrivals?.let { list ->
                if (list.isEmpty()) Label("곧 올 버스가 없습니다", size = 13.sp, color = Palette.TextDim)
                list.forEach { arrival -> ArrivalRow(arrival) }
            }
        }
    }
}

@Composable
private fun ArrivalRow(arrival: BusArrival) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(arrival.route, size = 15.sp, weight = FontWeight.Black, modifier = Modifier.width(70.dp))
        Label(
            listOf(arrival.toward.takeIf { it.isNotBlank() }?.let { "$it 방면" }, if (arrival.lowFloor) "저상" else null).filterNotNull().joinToString(" · "),
            size = 12.sp,
            color = Palette.TextDim,
            modifier = Modifier.weight(1f),
        )
        val seconds = arrival.seconds
        if (seconds != null) {
            val (value, unit) = countdownParts(seconds)
            Label("$value$unit", size = 15.sp, weight = FontWeight.Bold)
        } else {
            Label(arrival.message.ifBlank { "-" }, size = 12.sp, color = Palette.TextDim)
        }
    }
}

