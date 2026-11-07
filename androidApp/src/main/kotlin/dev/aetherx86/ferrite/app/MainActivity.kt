package dev.aetherx86.ferrite.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.aetherx86.ferrite.App
import dev.aetherx86.ferrite.StartRequest
import dev.aetherx86.ferrite.platform.AndroidHost

class MainActivity : ComponentActivity() {
    private lateinit var host: AndroidHost
    private val start = mutableStateOf(StartRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        host = AndroidHost(this)
        start.value = requestOf(intent)
        setContent { App(host, start.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start.value = requestOf(intent)
    }

    override fun onDestroy() {
        host.release()
        super.onDestroy()
    }

    private fun requestOf(intent: Intent) = StartRequest(
        station = intent.getStringExtra("station"),
        line = intent.getStringExtra("line"),
        demo = intent.getBooleanExtra("demo", false),
        tab = intent.getStringExtra("tab"),
        walkTo = intent.getStringExtra("walkTo"),
        walkFrom = intent.getStringExtra("walkFrom"),
        routeFrom = intent.getStringExtra("routeFrom"),
        routeTo = intent.getStringExtra("routeTo"),
        focus = intent.getStringExtra("focus"),
    )
}
