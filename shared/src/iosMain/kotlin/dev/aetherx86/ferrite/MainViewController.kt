package dev.omakasekim.ferrite

import androidx.compose.ui.window.ComposeUIViewController
import dev.omakasekim.ferrite.platform.IosHost
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val host = IosHost()
    return ComposeUIViewController { App(host) }
}
