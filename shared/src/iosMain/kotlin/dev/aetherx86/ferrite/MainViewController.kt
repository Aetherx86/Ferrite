package dev.aetherx86.ferrite

import androidx.compose.ui.window.ComposeUIViewController
import dev.aetherx86.ferrite.platform.IosHost
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val host = IosHost()
    return ComposeUIViewController { App(host) }
}
