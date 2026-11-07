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
import dev.aetherx86.ferrite.ui.world.NamedSpot

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
    val initialTab = remember {
        when {
            start.walkTo != null || (start.routeFrom != null && start.routeTo != null) || start.driveTo != null || start.trip != null || start.spotTo != null -> Tab.ROUTE
            start.around != null || start.focus != null -> Tab.MAP
            start.tab != null -> Tab.entries.firstOrNull { it.name.equals(start.tab, ignoreCase = true) } ?: Tab.HOME
            else -> Tab.HOME
        }
    }
    var tab by remember { mutableStateOf(initialTab) }
    val overlays = remember {
        mutableStateListOf<Overlay>().apply {
            val s = start.station?.let(network::named)
            if (s != null) add(Overlay.Board(s, start.line?.let(network::line), start.demo))
        }
    }
    var routeFrom by remember { mutableStateOf(start.routeFrom?.let(network::named)) }
    var routeTo by remember { mutableStateOf(start.routeTo?.let(network::named)) }
    var walkTo by remember { mutableStateOf(start.walkTo?.let(network::named)) }
    var walkFrom by remember { mutableStateOf(start.walkFrom?.let(network::named)) }
    var walkRequest by remember { mutableStateOf(0) }
    var driveFrom by remember { mutableStateOf(start.driveFrom?.let(network::named)) }
    var driveTo by remember { mutableStateOf(start.driveTo?.let(network::named)) }
    var tripStations by remember { mutableStateOf(start.trip?.split(',')?.mapNotNull { network.named(it.trim()) } ?: emptyList()) }
    var routeMode by remember {
        mutableStateOf<Int?>(
            when {
                start.spotTo != null -> when (start.spotMode) {
                    "walk" -> 1
                    "drive" -> 2
                    else -> 4
                }
                start.trip != null -> 3
                start.driveTo != null -> 2
                start.walkTo != null -> 1
                start.routeFrom != null && start.routeTo != null -> 0
                else -> null
            }
        )
    }
    var fromSpot by remember { mutableStateOf(NamedSpot.parse(start.spotFrom)) }
    var toSpot by remember { mutableStateOf(NamedSpot.parse(start.spotTo)) }
    var aroundSpot by remember { mutableStateOf(NamedSpot.parse(start.around)) }
    var highlight by remember { mutableStateOf<MapHighlight?>(null) }
    var mapFocus by remember { mutableStateOf(start.focus?.let(network::named)) }

    // Opened by a link, or by a script recording a demonstration.
    LaunchedEffect(start) {
        start.tab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { tab = it; overlays.clear() } }
        val station = start.station?.let(network::named)
        if (start.walkTo != null || start.routeFrom != null || start.focus != null) overlays.clear()
        if (start.walkTo != null) {
            walkTo = network.named(start.walkTo)
            walkFrom = start.walkFrom?.let(network::named)
            routeMode = 1
            walkRequest++
            tab = Tab.ROUTE
        }
        if (start.routeFrom != null && start.routeTo != null) {
            routeFrom = network.named(start.routeFrom)
            routeTo = network.named(start.routeTo)
            walkTo = null
            walkFrom = null
            routeMode = 0
            walkRequest++
            tab = Tab.ROUTE
        }
        if (start.driveTo != null) {
            overlays.clear()
            driveFrom = start.driveFrom?.let(network::named)
            driveTo = network.named(start.driveTo)
            routeMode = 2
            walkRequest++
            tab = Tab.ROUTE
        }
        if (start.trip != null) {
            overlays.clear()
            tripStations = start.trip.split(',').mapNotNull { network.named(it.trim()) }
            routeMode = 3
            walkRequest++
            tab = Tab.ROUTE
        }
        if (start.spotTo != null) {
            overlays.clear()
            fromSpot = NamedSpot.parse(start.spotFrom)
            toSpot = NamedSpot.parse(start.spotTo)
            routeMode = when (start.spotMode) {
                "walk" -> 1
                "drive" -> 2
                else -> 4
            }
            walkRequest++
            tab = Tab.ROUTE
        }
        if (start.around != null) {
            overlays.clear()
            aroundSpot = NamedSpot.parse(start.around)
            tab = Tab.MAP
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
                            onRouteFrom = { routeFrom = it; walkTo = null; walkFrom = null; tab = Tab.ROUTE },
                            onRouteTo = { routeTo = it; walkTo = null; walkFrom = null; tab = Tab.ROUTE },
                            onWalkTo = { walkTo = it; walkFrom = null; walkRequest++; tab = Tab.ROUTE },
                            focusStation = mapFocus,
                            highlight = highlight,
                            around = aroundSpot,
                        )
                        Tab.ROUTE -> RouteScreen(routeFrom, routeTo, walkTo, walkFrom, walkRequest, routeMode, driveFrom, driveTo, tripStations, fromSpot, toSpot, openBoard) { trip ->
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
                        walkFrom = null
                        walkRequest++
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
