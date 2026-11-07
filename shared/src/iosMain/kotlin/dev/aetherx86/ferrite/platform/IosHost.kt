package dev.aetherx86.ferrite.platform

import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.audio.SoundSynth
import dev.aetherx86.ferrite.core.LatLng
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import kotlinx.cinterop.useContents
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume

class IosHost : Host {
    override val store: KeyValueStore = object : KeyValueStore {
        private val defaults = NSUserDefaults.standardUserDefaults
        override fun getString(key: String): String? = defaults.stringForKey(key)
        override fun putString(key: String, value: String?) {
            if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
        }
    }

    private val players = HashMap<BoardSound, AVAudioPlayer>()

    @OptIn(ExperimentalForeignApi::class)
    private fun player(sound: BoardSound): AVAudioPlayer? {
        players[sound]?.let { return it }
        val created = AVAudioPlayer(data = SoundSynth.toWav(SoundSynth.render(sound)).toNSData(), error = null)
        created.prepareToPlay()
        players[sound] = created
        return created
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun play(sound: BoardSound) {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        val p = player(sound) ?: return
        p.currentTime = 0.0
        p.play()
    }

    override fun stopSounds() {
        players.values.forEach { it.stop() }
    }

    override fun keepScreenOn(on: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = on
    }

    // Status bar visibility belongs to the view controller on this platform, and the board already fills the screen.
    override fun setFullScreen(on: Boolean) = Unit

    // Rotation follows the device here, and the board adapts to it.
    override fun holdLandscape(on: Boolean) = Unit

    private val manager = CLLocationManager()
    private var locationDelegate: NSObject? = null

    override fun locationAllowed(): Boolean = manager.authorizationStatus.let {
        it == kCLAuthorizationStatusAuthorizedAlways || it == kCLAuthorizationStatusAuthorizedWhenInUse
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun currentLocation(): LatLng? {
        val status = manager.authorizationStatus
        if (status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted) return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                        val location = didUpdateLocations.lastOrNull() as? CLLocation
                        manager.stopUpdatingLocation()
                        if (continuation.isActive) {
                            continuation.resume(location?.let { loc ->
                                loc.coordinate.useContents { LatLng(latitude, longitude) }
                            })
                        }
                    }

                    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                        when (manager.authorizationStatus) {
                            kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> manager.startUpdatingLocation()
                            kCLAuthorizationStatusNotDetermined -> Unit
                            else -> if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                locationDelegate = delegate
                manager.delegate = delegate
                if (status == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization() else manager.startUpdatingLocation()
            }
        }
    }

    override fun share(text: String) {
        val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(controller, animated = true, completion = null)
    }

    override fun openUrl(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
