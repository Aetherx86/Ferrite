package dev.aetherx86.ferrite.platform

import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.audio.SoundSynth
import dev.aetherx86.ferrite.core.LatLng
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.suspendCancellableCoroutine

private fun storeGet(key: String): String? = js("window.localStorage.getItem(key)")
private fun storeSet(key: String, value: String) { js("window.localStorage.setItem(key, value)") }
private fun storeRemove(key: String) { js("window.localStorage.removeItem(key)") }
private fun audioPlay(id: String, url: String) { js("""{
    window.__ferriteAudio = window.__ferriteAudio || {};
    var a = window.__ferriteAudio[id] || (window.__ferriteAudio[id] = new Audio(url));
    a.currentTime = 0;
    var p = a.play();
    if (p && p.catch) p.catch(function () {});
}""") }
private fun audioStop() { js("""{
    var all = window.__ferriteAudio || {};
    for (var k in all) { all[k].pause(); }
}""") }
private fun geolocate(ok: (Double, Double) -> Unit, fail: () -> Unit) { js("""{
    if (!navigator.geolocation) { fail(); return; }
    navigator.geolocation.getCurrentPosition(function (p) { ok(p.coords.latitude, p.coords.longitude); }, function () { fail(); }, { timeout: 8000, maximumAge: 60000 });
}""") }
private fun openWindow(url: String) { js("window.open(url, '_blank', 'noopener')") }
private fun shareText(text: String) { js("""{
    if (navigator.share) { navigator.share({ text: text }).catch(function () {}); }
    else if (navigator.clipboard) { navigator.clipboard.writeText(text); }
}""") }
private fun fetchText(url: String, method: String, body: String?, contentType: String?, timeoutMs: Int, ok: (Int, String) -> Unit, fail: (String) -> Unit) { js("""{
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, timeoutMs);
    var init = { method: method, signal: controller.signal };
    if (body !== null) { init.body = body; }
    if (contentType !== null) { init.headers = { 'Content-Type': contentType }; }
    fetch(url, init).then(function (r) { return r.text().then(function (t) { clearTimeout(timer); ok(r.status, t); }); })
        .catch(function (e) { clearTimeout(timer); fail(String(e)); });
}""") }

private fun fetchBase64(url: String, timeoutMs: Int, ok: (String) -> Unit, fail: (String) -> Unit) { js("""{
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, timeoutMs);
    fetch(url, { signal: controller.signal }).then(function (r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        return r.arrayBuffer();
    }).then(function (buffer) {
        clearTimeout(timer);
        var bytes = new Uint8Array(buffer);
        var text = '';
        for (var i = 0; i < bytes.length; i += 32768) { text += String.fromCharCode.apply(null, bytes.subarray(i, i + 32768)); }
        ok(btoa(text));
    }).catch(function (e) { clearTimeout(timer); fail(String(e)); });
}""") }

@OptIn(ExperimentalEncodingApi::class)
actual suspend fun httpBytes(url: String, timeoutMs: Long): ByteArray = suspendCancellableCoroutine { continuation ->
    fetchBase64(
        url, timeoutMs.toInt(),
        { text -> if (continuation.isActive) continuation.resume(Base64.decode(text)) },
        { message -> if (continuation.isActive) continuation.resumeWithException(RuntimeException(message)) },
    )
}

actual suspend fun httpRequest(
    url: String,
    method: String,
    body: String?,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpResult = suspendCancellableCoroutine { continuation ->
    // Browsers manage most headers themselves, and asking for others would trigger a preflight some servers refuse.
    fetchText(
        url, method, body, headers["Content-Type"], timeoutMs.toInt(),
        { status, text -> if (continuation.isActive) continuation.resume(HttpResult(status, text)) },
        { message -> if (continuation.isActive) continuation.resumeWithException(RuntimeException(message)) },
    )
}

class WebHost : Host {
    override val isWebPreview: Boolean get() = true

    override val store: KeyValueStore = object : KeyValueStore {
        override fun getString(key: String): String? = storeGet("ferrite.$key")
        override fun putString(key: String, value: String?) {
            if (value == null) storeRemove("ferrite.$key") else storeSet("ferrite.$key", value)
        }
    }

    private val urls = HashMap<BoardSound, String>()

    @OptIn(ExperimentalEncodingApi::class)
    private fun urlOf(sound: BoardSound): String = urls.getOrPut(sound) {
        "data:audio/wav;base64," + Base64.encode(SoundSynth.toWav(SoundSynth.render(sound)))
    }

    override fun play(sound: BoardSound) = audioPlay(sound.name, urlOf(sound))
    override fun stopSounds() = audioStop()
    override fun keepScreenOn(on: Boolean) = Unit
    override fun setFullScreen(on: Boolean) = Unit
    override fun holdLandscape(on: Boolean) = Unit
    override fun locationAllowed(): Boolean = false

    override suspend fun currentLocation(): LatLng? = suspendCancellableCoroutine { continuation ->
        geolocate(
            { lat, lon -> if (continuation.isActive) continuation.resume(LatLng(lat, lon)) },
            { if (continuation.isActive) continuation.resume(null) },
        )
    }

    override fun share(text: String) = shareText(text)
    override fun openUrl(url: String) = openWindow(url)
}
