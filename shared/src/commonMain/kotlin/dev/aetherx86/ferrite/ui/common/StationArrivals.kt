package dev.aetherx86.ferrite.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.core.nowMillis
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.SeoulArrivalDto
import dev.aetherx86.ferrite.live.SeoulArrivals
import dev.aetherx86.ferrite.live.ServiceEstimator
import dev.aetherx86.ferrite.live.countdownParts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The next train each way for each line at a station, in a small space. */
@Composable
fun StationArrivals(station: Station, maxLines: Int = 3, onLine: (Line) -> Unit) {
    val services = LocalServices.current
    val key = services.settings.seoulKey
    var rows by remember(station) { mutableStateOf<List<SeoulArrivalDto>?>(null) }
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = nowMillis()
            delay(1000)
        }
    }
    LaunchedEffect(station, key) {
        if (key.isBlank() || station.lines.none { it.liveId != null }) return@LaunchedEffect
        while (true) {
            try {
                rows = SeoulArrivals.fetch(key, station.name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                rows = null
            }
            delay(30_000)
        }
    }
    station.lines.take(maxLines).forEach { line ->
        val paths = remember(line, station) { BoardPath.allFrom(services.network, line, station) }
        paths.take(2).forEach { path ->
            val liveTrains = rows?.let { all -> line.liveId?.let { SeoulArrivals.approaches(all, it, path.next?.name, now) } }.orEmpty()
                .filter { it.arrivalAtMs >= now - 5000 }
            val live = liveTrains.isNotEmpty()
            val train = liveTrains.firstOrNull() ?: ServiceEstimator.next(path, now, 1).trains.firstOrNull()
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                LineMark(line, 18.dp)
                Spacer(Modifier.width(7.dp))
                Label(
                    if (path.next == null) "종착" else "${train?.destination ?: path.terminus.name}행",
                    size = 13.sp,
                    weight = FontWeight.Medium,
                    color = Palette.TextSoft,
                    modifier = Modifier.weight(1f),
                )
                if (train != null && path.next != null) {
                    val seconds = ((train.arrivalAtMs - now) / 1000).toInt().coerceAtLeast(0)
                    val (value, unit) = countdownParts(seconds)
                    Spacer(Modifier.width(6.dp))
                    Label(if (seconds == 0) "도착" else "$value$unit", size = 14.sp, weight = FontWeight.Bold, color = if (live) Palette.Text else Palette.TextSoft)
                    Spacer(Modifier.width(5.dp))
                    SourceTag(if (live) "실시간" else "추정", live)
                }
            }
        }
    }
    Spacer(Modifier.height(1.dp))
}
