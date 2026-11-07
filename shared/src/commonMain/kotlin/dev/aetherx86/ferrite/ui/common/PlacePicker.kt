package dev.aetherx86.ferrite.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.walk.Place
import dev.aetherx86.ferrite.walk.PlaceSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Picks a place from where you are, the stations, or the open map's place names. */
@Composable
fun PlacePicker(allowHere: Boolean, modifier: Modifier = Modifier, onPick: (String, LatLng) -> Unit) {
    val network = LocalServices.current.network
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun search() {
        searching = true
        problem = null
        scope.launch {
            try {
                places = PlaceSearch.search(query)
                if (places.isEmpty()) problem = "찾는 장소가 없습니다"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "장소를 찾지 못했습니다. 연결을 확인해 주세요."
            }
            searching = false
        }
    }
    Column(modifier) {
        SearchField(query, { query = it }, "역, 건물, 장소", onSubmit = { search() })
        Spacer(Modifier.height(6.dp))
        val stations = remember(query) { if (query.isBlank()) emptyList() else network.search(query, 6) }
        LazyColumn(Modifier.weight(1f)) {
            if (allowHere) {
                item {
                    Row0(Icon.TARGET, "내 위치", "지금 있는 곳") {
                        scope.launch { host.currentLocation()?.let { onPick("내 위치", it) } }
                    }
                }
            }
            items(stations, key = { "s" + it.id }) { station ->
                Row0(Icon.TRAIN, "${station.name}역", station.lines.joinToString(" · ") { it.name }) { onPick("${station.name}역", station.location) }
            }
            if (query.isNotBlank()) {
                item { Row0(Icon.SEARCH, if (searching) "찾는 중" else "\"$query\" 장소 찾기", "지도 데이터에서 검색") { search() } }
            }
            problem?.let { message -> item { Label(message, size = 13.sp, color = Palette.Moderate, modifier = Modifier.padding(8.dp), maxLines = 2) } }
            items(places, key = { "p" + it.name + it.position.lat }) { place ->
                Row0(Icon.FLAG, place.name, place.detail) { onPick(place.name, place.position) }
            }
        }
    }
}

@Composable
private fun Row0(icon: Icon, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, Palette.TextDim, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, size = 15.sp, weight = FontWeight.Bold)
            if (detail.isNotEmpty()) Label(detail, size = 12.sp, color = Palette.TextDim)
        }
    }
}
