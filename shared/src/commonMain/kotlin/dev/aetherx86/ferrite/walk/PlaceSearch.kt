package dev.aetherx86.ferrite.walk

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class Place(val name: String, val detail: String, val position: LatLng)

@Serializable
private data class PlaceDto(
    val lat: String,
    val lon: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
)

/** Looks up places by name in the open map data. Only runs when someone asks, as the service's rules require. */
object PlaceSearch {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<Place> {
        val q = query.encodeToByteArray().joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) c.toChar().toString()
            else "%" + c.toString(16).uppercase().padStart(2, '0')
        }
        val result = httpRequest(
            url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=10&accept-language=ko,en",
            headers = mapOf("User-Agent" to "Ferrite/0.1"),
        )
        if (result.status != 200) throw RuntimeException("검색 서버 응답 ${result.status}")
        return parse(result.body)
    }

    fun parse(text: String): List<Place> =
        json.decodeFromString<List<PlaceDto>>(text).mapNotNull { dto ->
            val lat = dto.lat.toDoubleOrNull() ?: return@mapNotNull null
            val lon = dto.lon.toDoubleOrNull() ?: return@mapNotNull null
            val parts = dto.displayName.split(", ")
            Place(
                name = dto.name.ifBlank { parts.firstOrNull().orEmpty() },
                detail = parts.drop(1).reversed().filterNot { it == "대한민국" || it.all(Char::isDigit) }.joinToString(" "),
                position = LatLng(lat, lon),
            )
        }
}
