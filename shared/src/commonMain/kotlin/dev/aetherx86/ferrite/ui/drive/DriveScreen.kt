package dev.aetherx86.ferrite.ui.drive

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.isInKorea
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.drive.BatteryPlan
import dev.aetherx86.ferrite.drive.DriveOptions
import dev.aetherx86.ferrite.drive.DriveRoute
import dev.aetherx86.ferrite.drive.DriveService
import dev.aetherx86.ferrite.drive.TaxiFare
import dev.aetherx86.ferrite.drive.Vehicle
import dev.aetherx86.ferrite.map.MapPoi
import dev.aetherx86.ferrite.map.MapPois
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.Chip
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.NoticeCard
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.PlacePicker
import dev.aetherx86.ferrite.ui.walk.WalkMap
import kotlinx.coroutines.CancellationException

private class Spot(val name: String, val position: LatLng)

private fun won(value: Int): String {
    val text = value.toString()
    return if (text.length > 3) text.dropLast(3) + "," + text.takeLast(3) else text
}

/** Road directions for the vehicle you are in, with what that vehicle needs to know. */
@Composable
fun DriveScreen(fromStation: Station? = null, toStation: Station? = null, fromSpot: dev.aetherx86.ferrite.ui.world.NamedSpot? = null, toSpot: dev.aetherx86.ferrite.ui.world.NamedSpot? = null) {
    val host = LocalHost.current
    var from by remember { mutableStateOf(fromSpot?.let { Spot(it.name, it.position) } ?: fromStation?.let { Spot("${it.name}역", it.location) }) }
    var to by remember { mutableStateOf(toSpot?.let { Spot(it.name, it.position) } ?: toStation?.let { Spot("${it.name}역", it.location) }) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var vehicle by remember { mutableStateOf(Vehicle.CAR) }
    var avoidTolls by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(3.5) }
    var weight by remember { mutableStateOf(10.0) }
    var battery by remember { mutableStateOf(60) }
    var result by remember { mutableStateOf<DriveRoute?>(null) }
    var charger by remember { mutableStateOf<MapPoi?>(null) }
    var parkings by remember { mutableStateOf<List<MapPoi>>(emptyList()) }
    var parkingTarget by remember { mutableStateOf<MapPoi?>(null) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val plan = BatteryPlan(battery, capacityKwh = 64.0, kwhPerKm = 0.17)

    LaunchedEffect(Unit) {
        if (from == null && host.locationAllowed()) host.currentLocation()?.let { from = Spot("내 위치", it) }
    }

    LaunchedEffect(to) {
        parkingTarget = null
        parkings = emptyList()
        val target = to ?: return@LaunchedEffect
        parkings = runCatching { MapPois.near(target.position, setOf("parking"), 700, 5) }.getOrDefault(emptyList())
    }

    LaunchedEffect(from, to, vehicle, avoidTolls, height, weight, parkingTarget, battery) {
        result = null
        charger = null
        problem = null
        val a = from ?: return@LaunchedEffect
        val b = to ?: return@LaunchedEffect
        val end = parkingTarget?.position ?: b.position
        val options = DriveOptions(vehicle, avoidTolls, height, weight)
        loading = true
        try {
            var route = DriveService.route(listOf(a.position, end), options)
            if (vehicle == Vehicle.EV && plan.percentAfter(route.route.meters) < RESERVE_PERCENT) {
                // Stops for a charge before the reserve runs out, near where the route will be by then.
                val reach = plan.chargeBeforeMeters(RESERVE_PERCENT)
                val point = pointAlong(route.route.points, reach * 0.8)
                val found = MapPois.near(point, setOf("charging_station"), 8000, 1).firstOrNull()
                if (found != null) {
                    charger = found
                    route = DriveService.route(listOf(a.position, found.position, end), options)
                }
            }
            result = route
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "경로를 찾지 못했습니다. 연결을 확인하고 다시 시도해 주세요."
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            EndRow("출발", from?.name, editing == 0) { editing = if (editing == 0) null else 0 }
            EndRow("도착", to?.name, editing == 1) { editing = if (editing == 1) null else 1 }
        }
        val editingNow = editing
        if (editingNow != null) {
            PlacePicker(allowHere = editingNow == 0, modifier = Modifier.padding(horizontal = 14.dp).fillMaxSize()) { name, at ->
                if (editingNow == 0) from = Spot(name, at) else to = Spot(name, at)
                editing = null
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Vehicle.entries.forEach { v -> Chip(v.label, vehicle == v, { vehicle = v }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vehicle != Vehicle.BICYCLE) Chip("통행료 피하기", avoidTolls, { avoidTolls = !avoidTolls })
            if (vehicle == Vehicle.TRUCK) {
                Stepper("높이", "${(height * 10).toInt() / 10.0}m", { height = (height - 0.5).coerceAtLeast(2.0) }, { height = (height + 0.5).coerceAtMost(4.5) })
                Stepper("무게", "${weight.toInt()}t", { weight = (weight - 5).coerceAtLeast(5.0) }, { weight = (weight + 5).coerceAtMost(40.0) })
            }
            if (vehicle == Vehicle.EV) {
                Stepper("배터리", "$battery%", { battery = (battery - 10).coerceAtLeast(10) }, { battery = (battery + 10).coerceAtMost(100) })
            }
        }
        val current = result
        when {
            from == null || to == null -> Box(Modifier.padding(14.dp)) {
                NoticeCard("어디로 갈까요", "출발지와 도착지를 고르면 차종에 맞는 길을 찾습니다. 실시간 교통은 반영하지 않습니다.")
            }
            problem != null -> Box(Modifier.padding(14.dp)) { NoticeCard("길을 찾지 못했습니다", problem!!) }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label(if (loading) "길을 찾는 중" else "", size = 14.sp, weight = FontWeight.Bold, color = Palette.TextSoft)
            }
            else -> {
                WalkMap(null, current.route, Modifier.fillMaxWidth().weight(1f))
                Summary(current, vehicle, plan, charger)
                LazyColumn(Modifier.fillMaxWidth().weight(0.8f).padding(horizontal = 14.dp)) {
                    if (parkings.isNotEmpty() && vehicle != Vehicle.BICYCLE) {
                        item {
                            Label("도착지 주변 주차장", size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.padding(vertical = 5.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                parkings.forEach { p ->
                                    Chip("${p.name.ifEmpty { "주차장" }} ${p.meters}m", parkingTarget == p, { parkingTarget = if (parkingTarget == p) null else p })
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    items(current.route.steps) { step ->
                        Label(step.text, size = 14.sp, weight = FontWeight.Medium, maxLines = 2, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    item {
                        Label("실시간 교통 미반영 · 요금과 배터리는 참고용 추정치", size = 10.sp, color = Palette.TextFaint, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

private const val RESERVE_PERCENT = 12

private fun pointAlong(points: List<LatLng>, meters: Double): LatLng {
    var left = meters
    for ((a, b) in points.zipWithNext()) {
        val d = dev.aetherx86.ferrite.core.distanceMeters(a, b)
        if (left <= d) return b
        left -= d
    }
    return points.last()
}

@Composable
private fun Summary(current: DriveRoute, vehicle: Vehicle, plan: BatteryPlan, charger: MapPoi?) {
    val route = current.route
    val minutes = (route.seconds + 30) / 60
    val arrive = kstOf(nowMillis() + route.seconds * 1000L)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (minutes >= 60) {
                Label("${minutes / 60}", size = 32.sp, weight = FontWeight.Black, tight = true)
                Label("시간 ", size = 15.sp, weight = FontWeight.Bold, color = Palette.Accent)
            }
            Label("${minutes % 60}", size = 32.sp, weight = FontWeight.Black, tight = true)
            Label("분", size = 15.sp, weight = FontWeight.Bold, color = Palette.Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label("${(route.meters / 100).toInt() / 10.0}km · ${two(arrive.hour)}:${two(arrive.minute)} 도착", size = 14.sp, weight = FontWeight.Bold)
                val notes = buildList {
                    if (current.hasToll) add(if (vehicle == Vehicle.LIGHT) "통행료 구간, 경차 50% 할인 대상" else "통행료 구간 포함")
                    if (current.hasHighway) add("고속도로 포함")
                    if (vehicle == Vehicle.MOTORBIKE) add("자동차 전용도로 제외")
                }
                if (notes.isNotEmpty()) Label(notes.joinToString(" · "), size = 11.sp, color = Palette.TextDim, maxLines = 2)
            }
        }
        if ((vehicle == Vehicle.CAR || vehicle == Vehicle.LIGHT) && route.points.first().isInKorea()) {
            val hour = kstOf(nowMillis()).hour
            Spacer(Modifier.height(4.dp))
            Label("같은 길 택시 약 ${won(TaxiFare.estimate(route.meters, hour))}원 (서울 요금 기준)", size = 12.sp, color = Palette.TextSoft)
        }
        if (vehicle == Vehicle.EV) {
            Spacer(Modifier.height(4.dp))
            val after = plan.percentAfter(route.meters)
            val text = when {
                charger != null -> "${charger.name.ifEmpty { "충전소" }} 경유 · 도착 전에 충전이 필요합니다"
                after < 0 -> "배터리가 부족한데 경로 근처에서 충전소를 찾지 못했습니다"
                else -> "도착 시 배터리 약 $after%"
            }
            Label(text, size = 12.sp, weight = FontWeight.Bold, color = if (charger != null || after < RESERVE_PERCENT) Palette.Moderate else Palette.Roomy, maxLines = 2)
        }
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

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("-", onMinus)
        Label("$label $value", size = 12.sp, weight = FontWeight.Bold)
        StepButton("+", onPlus)
    }
}

@Composable
internal fun StepButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Label(text, size = 16.sp, weight = FontWeight.Bold, color = Palette.Accent)
    }
}

