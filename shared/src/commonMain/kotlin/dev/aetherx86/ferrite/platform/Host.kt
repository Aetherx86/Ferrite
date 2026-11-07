package dev.aetherx86.ferrite.platform

import androidx.compose.runtime.staticCompositionLocalOf
import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.core.LatLng

/** What the shared code needs from the operating system. */
interface Host {
    val store: KeyValueStore

    /** A browser cannot reach the public feeds, so the web edition runs on demonstration data. */
    val isWebPreview: Boolean get() = false
    fun play(sound: BoardSound)
    fun stopSounds()

    /** Keeps the screen awake while a board is up. */
    fun keepScreenOn(on: Boolean)

    /** Hides system bars, for a full screen board. */
    fun setFullScreen(on: Boolean)

    /** Holds the screen sideways, for when rotation is locked. False goes back to following the device. */
    fun holdLandscape(on: Boolean)

    /** Whether location can be read without asking. */
    fun locationAllowed(): Boolean

    /** Where the device is, asking permission if needed. Null if unknown or refused. */
    suspend fun currentLocation(): LatLng?
    fun share(text: String)
    fun openUrl(url: String)
}

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

val LocalHost = staticCompositionLocalOf<Host> { error("No host") }

class HttpResult(val status: Int, val body: String)

/** Fetches text. Throws on network failure. */
expect suspend fun httpRequest(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Long = 20_000,
): HttpResult

/** Fetches a file. Throws on network failure or an error status. */
expect suspend fun httpBytes(url: String, timeoutMs: Long = 15_000): ByteArray
