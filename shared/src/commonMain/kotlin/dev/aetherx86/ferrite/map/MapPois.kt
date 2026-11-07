package dev.aetherx86.ferrite.map

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.platform.httpBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

data class MapPoi(val name: String, val kind: String, val position: LatLng, val meters: Int)

/** Places marked on the map tiles, such as car parks and chargers. Tiles are quick, so asking costs little. */
object MapPois {
    private const val ZOOM = 14

    suspend fun near(point: LatLng, kinds: Set<String>, radiusMeters: Int, limit: Int = 12): List<MapPoi> {
        val n = 1 shl ZOOM
        val cx = floor((point.lon + 180.0) / 360.0 * n).toInt()
        val cy = floor((1.0 - asinh(tan(point.lat * PI / 180.0)) / PI) / 2.0 * n).toInt()
        val pairs = (-1..1).flatMap { dx -> (-1..1).map { dy -> (cx + dx) to (cy + dy) } }
        val found = coroutineScope {
            pairs.map { (x, y) ->
                async {
                    runCatching { read(httpBytes(BaseMap.tileUrl(ZOOM, x, y)), x, y, n, point, kinds) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        return found.filter { it.meters <= radiusMeters }.distinctBy { it.name to it.position.lat }.sortedBy { it.meters }.take(limit)
    }

    fun read(bytes: ByteArray, tx: Int, ty: Int, n: Int, from: LatLng, kinds: Set<String>): List<MapPoi> {
        val layer = VectorTileReader.read(bytes, setOf("poi")).firstOrNull() ?: return emptyList()
        val e = layer.extent.toDouble()
        return layer.features.mapNotNull { feature ->
            val kind = feature.tags["subclass"]?.takeIf { it in kinds } ?: feature.tags["class"]?.takeIf { it in kinds } ?: return@mapNotNull null
            val part = feature.parts.firstOrNull() ?: return@mapNotNull null
            val lon = (tx + part[0] / e) / n * 360.0 - 180.0
            val lat = atan(sinh(PI * (1 - 2 * (ty + part[1] / e) / n))) * 180.0 / PI
            val at = LatLng(lat, lon)
            MapPoi(feature.tags["name:ko"] ?: feature.tags["name"] ?: "", kind, at, distanceMeters(from, at).toInt())
        }
    }
}
