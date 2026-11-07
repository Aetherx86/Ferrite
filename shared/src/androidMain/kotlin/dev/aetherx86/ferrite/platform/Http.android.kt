package dev.aetherx86.ferrite.platform

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun httpRequest(
    url: String,
    method: String,
    body: String?,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpResult = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.instanceFollowRedirects = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        HttpResult(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
    } finally {
        connection.disconnect()
    }
}

actual suspend fun httpBytes(url: String, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.setRequestProperty("User-Agent", "Ferrite/0.1")
        if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}
