package dev.aetherx86.ferrite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.aetherx86.ferrite.data.CongestionTable
import dev.aetherx86.ferrite.data.GeoMapData
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.platform.Host
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.resources.Res
import dev.aetherx86.ferrite.ui.AppRoot
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.rememberAppFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where to open, for links and for recording a demonstration. */
data class StartRequest(
    val station: String? = null,
    val line: String? = null,
    val demo: Boolean = false,
    val tab: String? = null,
    val walkTo: String? = null,
    val walkFrom: String? = null,
    val routeFrom: String? = null,
    val routeTo: String? = null,
    val focus: String? = null,
)

@Composable
fun App(host: Host, start: StartRequest = StartRequest()) {
    val fonts = rememberAppFonts()
    val services by produceState<AppServices?>(null) {
        value = withContext(Dispatchers.Default) { loadServices(host) }
    }
    CompositionLocalProvider(LocalHost provides host, LocalFonts provides fonts) {
        val loaded = services
        if (loaded == null) {
            Box(Modifier.fillMaxSize().background(Palette.Ink))
        } else {
            CompositionLocalProvider(LocalServices provides loaded) {
                AppRoot(start)
            }
        }
    }
}

private suspend fun loadServices(host: Host): AppServices {
    val network = TransitNetwork.parse(Res.readBytes("files/network.json").decodeToString())
    val congestion = runCatching { CongestionTable.parse(Res.readBytes("files/congestion.json").decodeToString()) }.getOrNull()
    val geo = runCatching { GeoMapData.parse(Res.readBytes("files/geo.json").decodeToString()) }.getOrNull()
    return AppServices(network, congestion, geo, AppSettings(host.store))
}
