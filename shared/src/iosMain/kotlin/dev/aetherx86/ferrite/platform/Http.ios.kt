package dev.aetherx86.ferrite.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataTaskWithURL
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPBody
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun httpRequest(
    url: String,
    method: String,
    body: String?,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpResult = suspendCancellableCoroutine { continuation ->
    val target = NSURL.URLWithString(url)
    if (target == null) {
        continuation.resumeWithException(IllegalArgumentException("Bad URL"))
        return@suspendCancellableCoroutine
    }
    val request = NSMutableURLRequest.requestWithURL(target)
    request.setHTTPMethod(method)
    request.setTimeoutInterval(timeoutMs / 1000.0)
    headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
    if (body != null) {
        request.setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
    }
    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response, error: NSError? ->
        if (error != null) {
            if (continuation.isActive) continuation.resumeWithException(RuntimeException(error.localizedDescription))
            return@dataTaskWithRequest
        }
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
        if (continuation.isActive) continuation.resume(HttpResult(status, text))
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun httpBytes(url: String, timeoutMs: Long): ByteArray = suspendCancellableCoroutine { continuation ->
    val target = NSURL.URLWithString(url)
    if (target == null) {
        continuation.resumeWithException(IllegalArgumentException("Bad URL"))
        return@suspendCancellableCoroutine
    }
    val task = NSURLSession.sharedSession.dataTaskWithURL(target) { data: NSData?, response, error: NSError? ->
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        if (error != null || data == null || status !in 200..299) {
            if (continuation.isActive) continuation.resumeWithException(RuntimeException(error?.localizedDescription ?: "HTTP $status"))
            return@dataTaskWithURL
        }
        val size = data.length.toInt()
        val out = ByteArray(size)
        if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        if (continuation.isActive) continuation.resume(out)
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}
