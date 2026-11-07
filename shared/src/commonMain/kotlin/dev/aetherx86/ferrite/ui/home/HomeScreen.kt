package dev.aetherx86.ferrite.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.IconButton
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LineMark
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.SearchField
import dev.aetherx86.ferrite.ui.common.SectionTitle
import dev.aetherx86.ferrite.ui.common.StationArrivals
import kotlinx.coroutines.launch

@Composable
fun StationRow(station: Station, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row { station.lines.take(4).forEach { line -> LineMark(line, 22.dp); Spacer(Modifier.width(3.dp)) } }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Label(station.name, size = 16.sp, weight = FontWeight.Bold)
            val network = LocalServices.current.network
            val line2 = detail ?: listOf(station.nameEn, if (station.region != "capital") network.regionName(station.region) else "").filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
            if (line2.isNotEmpty()) Label(line2, size = 12.sp, color = Palette.TextDim)
        }
        AppIcon(Icon.CHEVRON, Palette.TextFaint, size = 16.dp)
    }
}

@Composable
fun HomeScreen(onStation: (Station, Line?) -> Unit, onSettings: () -> Unit) {
    val services = LocalServices.current
    val host = LocalHost.current
    val settings = services.settings
    val network = services.network
    var query by remember { mutableStateOf("") }
    var here by remember { mutableStateOf<LatLng?>(null) }
    var locating by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val results = remember(query) { if (query.isBlank()) emptyList() else network.search(query) }
    val nearby = remember(here) { here?.let { network.nearest(it, 3) }.orEmpty() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Nobody should have to ask twice once they have said yes.
        if (host.locationAllowed()) here = host.currentLocation()
    }

    fun locate() {
        locating = true
        scope.launch {
            here = host.currentLocation()
            located = true
            locating = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 620.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("다음 열차", size = 26.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                    IconButton(Icon.SETTINGS, "설정", onSettings)
                }
                Spacer(Modifier.height(10.dp))
                SearchField(query, { query = it }, "역 이름, 초성으로도 검색 (ㅅㄱㅈ)")
            }
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        Label("찾는 역이 없습니다", size = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                items(results, key = { it.id }) { station ->
                    StationRow(station) { settings.noteRecent(station.id); onStation(station, null) }
                }
            } else {
                item { SectionTitle("가까운 역") }
                if (nearby.isEmpty()) {
                    item {
                        Panel {
                            Label(if (located) "위치를 알 수 없습니다" else "내 주변 역과 다음 열차를 볼 수 있습니다", size = 14.sp, color = Palette.TextSoft, maxLines = 2)
                            Spacer(Modifier.height(10.dp))
                            GhostButton(if (locating) "찾는 중" else "내 위치 찾기", { locate() }, icon = Icon.TARGET, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    items(nearby, key = { "near" + it.first.id }) { (station, meters) ->
                        ArrivalCard(station, "도보 ${(meters / 72).toInt().coerceAtLeast(1)}분 · ${meters.toInt()}m", onStation)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                val favorites = settings.favorites.mapNotNull(network::station)
                if (favorites.isNotEmpty()) {
                    item { SectionTitle("즐겨찾기") }
                    items(favorites, key = { "fav" + it.id }) { station ->
                        ArrivalCard(station, null, onStation)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                val recents = settings.recents.filter { it !in settings.favorites }.mapNotNull(network::station)
                if (recents.isNotEmpty()) {
                    item { SectionTitle("최근") }
                    items(recents, key = { "recent" + it.id }) { station ->
                        StationRow(station) { settings.noteRecent(station.id); onStation(station, null) }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ArrivalCard(station: Station, detail: String?, onStation: (Station, Line?) -> Unit) {
    val settings = LocalServices.current.settings
    Panel(onClick = { settings.noteRecent(station.id); onStation(station, null) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(station.name, size = 18.sp, weight = FontWeight.Black)
                Label(detail ?: station.nameEn, size = 12.sp, color = Palette.TextDim)
            }
            AppIcon(Icon.CHEVRON, Palette.TextFaint, size = 16.dp)
        }
        Spacer(Modifier.height(8.dp))
        StationArrivals(station) { onStation(station, it) }
    }
}

