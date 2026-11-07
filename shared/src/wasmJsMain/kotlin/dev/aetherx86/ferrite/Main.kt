package dev.aetherx86.ferrite

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.aetherx86.ferrite.platform.WebHost

private fun queryParameter(name: String): String? = js("new URLSearchParams(window.location.search).get(name)")

/** The web edition. It opens on demonstration data, and a link can point it at any screen. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val start = StartRequest(
        station = queryParameter("station"),
        line = queryParameter("line"),
        demo = true,
        tab = queryParameter("tab"),
        walkTo = queryParameter("walkTo"),
        walkFrom = queryParameter("walkFrom"),
        routeFrom = queryParameter("routeFrom"),
        routeTo = queryParameter("routeTo"),
        focus = queryParameter("focus"),
        driveFrom = queryParameter("driveFrom"),
        driveTo = queryParameter("driveTo"),
        trip = queryParameter("trip"),
        spotFrom = queryParameter("spotFrom"),
        spotTo = queryParameter("spotTo"),
        spotMode = queryParameter("spotMode"),
        around = queryParameter("around"),
    )
    ComposeViewport("app") {
        App(WebHost(), start)
    }
}
