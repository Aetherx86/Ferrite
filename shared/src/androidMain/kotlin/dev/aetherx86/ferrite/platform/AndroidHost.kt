package dev.aetherx86.ferrite.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.audio.SoundSynth
import dev.aetherx86.ferrite.core.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidHost(private val activity: ComponentActivity) : Host {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracks = HashMap<BoardSound, AudioTrack>()
    private var permissionResult: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionResult?.complete(result.values.any { it })
        permissionResult = null
    }

    override val store: KeyValueStore = object : KeyValueStore {
        private val prefs = activity.getSharedPreferences("ferrite", Context.MODE_PRIVATE)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    init {
        // Rendering takes a moment, so it happens ahead of the first sound.
        scope.launch { BoardSound.entries.forEach { track(it) } }
    }

    @Synchronized
    private fun track(sound: BoardSound): AudioTrack = tracks.getOrPut(sound) {
        val samples = SoundSynth.render(sound)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SoundSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }

    override fun play(sound: BoardSound) {
        scope.launch {
            val track = track(sound)
            runCatching {
                track.stop()
                track.reloadStaticData()
                track.play()
            }
        }
    }

    override fun stopSounds() {
        scope.launch {
            synchronized(this@AndroidHost) { tracks.values.toList() }.forEach { runCatching { it.pause(); it.flush() } }
        }
    }

    override fun keepScreenOn(on: Boolean) {
        activity.runOnUiThread {
            if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun setFullScreen(on: Boolean) {
        activity.runOnUiThread {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (on) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun holdLandscape(on: Boolean) {
        activity.runOnUiThread {
            activity.requestedOrientation =
                if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    override fun locationAllowed(): Boolean = hasLocationPermission()

    private fun hasLocationPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): LatLng? {
        if (!hasLocationPermission()) {
            val waiting = CompletableDeferred<Boolean>()
            permissionResult = waiting
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            if (!waiting.await()) return null
        }
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val fresh = cached?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
        if (fresh != null) return fresh.toLatLng()
        val provider = providers.firstOrNull() ?: return cached?.toLatLng()
        val live = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.getCurrentLocation(provider, null, activity.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, { location -> if (continuation.isActive) continuation.resume(location) }, activity.mainLooper)
                }
            }
        }
        return (live ?: cached)?.toLatLng()
    }

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    override fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    override fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun release() {
        synchronized(this) {
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
        }
    }
}
