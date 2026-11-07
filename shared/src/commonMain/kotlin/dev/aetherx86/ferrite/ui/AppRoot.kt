package dev.aetherx86.ferrite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.StartRequest
import dev.aetherx86.ferrite.data.Line
import dev.aetherx86.ferrite.data.Station
import dev.aetherx86.ferrite.ui.airport.AirportScreen
import dev.aetherx86.ferrite.ui.board.BoardScreen
import dev.aetherx86.ferrite.ui.bus.BusScreen
import dev.aetherx86.ferrite.ui.common.AppIcon
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.home.HomeScreen
import dev.aetherx86.ferrite.ui.map.MapHighlight
import dev.aetherx86.ferrite.ui.map.MapScreen
import dev.aetherx86.ferrite.ui.route.RouteScreen
import dev.aetherx86.ferrite.ui.settings.LicensesScreen
import dev.aetherx86.ferrite.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: Icon) {
    HOME("도착", Icon.TRAIN),
    MAP("노선도", Icon.MAP),
    ROUTE("길찾기", Icon.ROUTE),
    BUS("버스", Icon.BUS),
    AIRPORT("공항", Icon.PLANE),
}

private sealed interface Overlay {
    data class Board(val station: Station, val line: Line?, val demo: Boolean) : Overlay
    data object Settings : Overlay
    data object Licenses : Overlay
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(start: StartRequest) {
    val services = LocalServices.current
    val network = services.network
    var tab by remember { mutableStateOf(Tab.HOME) }
    val overlays = remember { mutableStateListOf<Overlay>() }
    var routeFrom by remember { mutableStateOf<Station?>(null) }
    var routeTo by remember { mutableStateOf<Station?>(null) }
    var walkTo by remember { mutableStateOf<Station?>(null) }
    var walkFrom by remember { mutableStateOf<Station?>(null) }
    var highlight by remember { mutableStateOf<MapHighlight?>(null) }
    var mapFocus by remember { mutableStateOf<Station?>(null) }

    // Opened by a link, or by a script recording a demonstration.
    LaunchedEffect(start) {
        start.tab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { tab = it; overlays.clear() } }
        val station = start.station?.let(network::named)
        if (start.walkTo != null || start.routeFrom != null || start.focus != null) overlays.clear()
        if (start.walkTo != null) {
            walkTo = network.named(start.walkTo)
            walkFrom = start.walkFrom?.let(network::named)
            tab = Tab.ROUTE
        }
        if (start.routeFrom != null && start.routeTo != null) {
            routeFrom = network.named(start.routeFrom)
            routeTo = network.named(start.routeTo)
            tab = Tab.ROUTE
        }
        if (start.focus != null) {
            mapFocus = network.named(start.focus)
            tab = Tab.MAP
        }
        if (start.demo) services.settings.startDemoSession()
        if (station != null) {
            overlays.clear()
            overlays.add(Overlay.Board(station, start.line?.let(network::line), start.demo))
        }
    }

    val openBoard: (Station, Line?) -> Unit = { station, line ->
        services.settings.noteRecent(station.id)
        overlays.add(Overlay.Board(station, line, false))
    }

    BackHandler(enabled = overlays.isNotEmpty() || tab != Tab.HOME) {
        if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex) else tab = Tab.HOME
    }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(110)) },
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(openBoard) { overlays.add(Overlay.Settings) }
                        Tab.MAP -> MapScreen(
                            onBoard = openBoard,
                            onRouteFrom = { routeFrom = it; walkTo = null; tab = Tab.ROUTE },
                            onRouteTo = { routeTo = it; walkTo = null; tab = Tab.ROUTE },
                            onWalkTo = { walkTo = it; tab = Tab.ROUTE },
                            focusStation = mapFocus,
                            highlight = highlight,
                        )
                        Tab.ROUTE -> RouteScreen(routeFrom, routeTo, walkTo, walkFrom, openBoard) { trip ->
                            highlight = MapHighlight(
                                trip.legs.first().line.region,
                                trip.legs.map { it.line.id }.toSet(),
                                trip.legs.flatMap { leg -> leg.stations.map { it.id } }.toSet(),
                            )
                            tab = Tab.MAP
                        }
                        Tab.BUS -> BusScreen({ overlays.add(Overlay.Settings) })
                        Tab.AIRPORT -> AirportScreen(openBoard, { overlays.add(Overlay.Settings) })
                    }
                }
            }
            TabBar(tab) {
                if (it == tab && it == Tab.MAP) highlight = null
                tab = it
            }
        }
        overlays.lastOrNull()?.let { overlay ->
            when (overlay) {
                is Overlay.Board -> BoardScreen(
                    station = overlay.station,
                    initialLine = overlay.line,
                    demo = overlay.demo,
                    onBack = { overlays.removeAt(overlays.lastIndex) },
                    onWalkHere = {
                        walkTo = it
                        overlays.removeAt(overlays.lastIndex)
                        tab = Tab.ROUTE
                    },
                )
                Overlay.Settings -> Box(Modifier.fillMaxSize().background(Palette.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    SettingsScreen(onBack = { overlays.removeAt(overlays.lastIndex) }, onLicenses = { overlays.add(Overlay.Licenses) })
                }
                Overlay.Licenses -> Box(Modifier.fillMaxSize().background(Palette.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    LicensesScreen(onBack = { overlays.removeAt(overlays.lastIndex) })
                }
            }
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Row(Modifier.fillMaxWidth().background(Palette.InkRaised)) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.weight(1f).clickable { onSelect(tab) }.padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(tab.icon, if (on) Palette.Accent else Palette.TextDim, size = 22.dp)
                    Spacer(Modifier.height(2.dp))
                    Label(tab.label, size = 10.5.sp, weight = FontWeight.Bold, color = if (on) Palette.Text else Palette.TextDim)
                }
            }
        }
    }
}
