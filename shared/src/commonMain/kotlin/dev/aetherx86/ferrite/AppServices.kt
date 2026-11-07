package dev.aetherx86.ferrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.aetherx86.ferrite.data.CongestionTable
import dev.aetherx86.ferrite.data.GeoMapData
import dev.aetherx86.ferrite.data.TransitNetwork
import dev.aetherx86.ferrite.platform.KeyValueStore

/** Settings and saved places. Keys for public data stay on the device and go only to the service they are for. */
class AppSettings(private val store: KeyValueStore) {
    var seoulKey by mutableStateOf(store.getString(SEOUL_KEY).orEmpty())
        private set
    var dataKey by mutableStateOf(store.getString(DATA_KEY).orEmpty())
        private set
    var soundOn by mutableStateOf(store.getString(SOUND) != "off")
        private set
    private var demoStored by mutableStateOf(store.getString(DEMO) == "on")
    private var demoSession by mutableStateOf(false)

    /** Scripted data everywhere, either by choice or just for this run. */
    val demoMode: Boolean get() = demoStored || demoSession
    val favorites = mutableStateListOf<Int>().apply { addAll(readIds(FAVORITES)) }
    val recents = mutableStateListOf<Int>().apply { addAll(readIds(RECENTS)) }

    private fun readIds(key: String): List<Int> =
        store.getString(key)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun updateSeoulKey(value: String) {
        seoulKey = value.trim()
        store.putString(SEOUL_KEY, seoulKey.ifEmpty { null })
    }

    fun updateDataKey(value: String) {
        dataKey = value.trim()
        store.putString(DATA_KEY, dataKey.ifEmpty { null })
    }

    fun updateSound(on: Boolean) {
        soundOn = on
        store.putString(SOUND, if (on) "on" else "off")
    }

    fun updateDemo(on: Boolean) {
        demoStored = on
        demoSession = false
        store.putString(DEMO, if (on) "on" else null)
    }

    /** Turns demonstration data on until the app closes. */
    fun startDemoSession() {
        demoSession = true
    }

    fun toggleFavorite(id: Int) {
        if (id in favorites) favorites.remove(id) else favorites.add(0, id)
        store.putString(FAVORITES, favorites.joinToString(","))
    }

    fun noteRecent(id: Int) {
        recents.remove(id)
        recents.add(0, id)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        store.putString(RECENTS, recents.joinToString(","))
    }

    private companion object {
        const val SEOUL_KEY = "key.seoul"
        const val DATA_KEY = "key.data"
        const val SOUND = "sound"
        const val DEMO = "demo"
        const val FAVORITES = "favorites"
        const val RECENTS = "recents"
        const val MAX_RECENTS = 8
    }
}

/** What every screen shares, loaded once. */
class AppServices(
    val network: TransitNetwork,
    val congestion: CongestionTable?,
    val geo: GeoMapData?,
    val settings: AppSettings,
)

val LocalServices = staticCompositionLocalOf<AppServices> { error("No services") }
