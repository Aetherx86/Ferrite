package dev.aetherx86.ferrite.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ShapeDto(val name: String = "", val code: String = "", val rings: List<List<Int>>)

@Serializable
private data class GeoDto(
    val provinces: List<ShapeDto>,
    val municipalities: List<ShapeDto>,
    val tracks: Map<String, List<List<Int>>>,
    val rail: List<List<Int>>,
)

/** A named area and its outlines, as alternating longitude and latitude values. */
class MapShape(val name: String, val code: String, val rings: List<FloatArray>)

/** The national base map. Everything is stored as longitude then latitude, in degrees. */
class GeoMapData(
    val provinces: List<MapShape>,
    val municipalities: List<MapShape>,
    val tracks: Map<String, List<FloatArray>>,
    val rail: List<FloatArray>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GeoMapData {
            val dto = json.decodeFromString(GeoDto.serializer(), text)
            fun shapes(list: List<ShapeDto>) = list.map { s -> MapShape(s.name, s.code, s.rings.map { decode(it, 10_000f) }) }
            return GeoMapData(
                provinces = shapes(dto.provinces),
                municipalities = shapes(dto.municipalities),
                tracks = dto.tracks.mapValues { (_, runs) -> runs.map { decode(it, 100_000f) } },
                rail = dto.rail.map { decode(it, 100_000f) },
            )
        }

        /** Coordinates are stored as a first point then differences, which keeps the file small. */
        private fun decode(deltas: List<Int>, factor: Float): FloatArray {
            val out = FloatArray(deltas.size)
            var x = 0
            var y = 0
            var i = 0
            while (i + 1 < deltas.size) {
                x += deltas[i]
                y += deltas[i + 1]
                out[i] = x / factor
                out[i + 1] = y / factor
                i += 2
            }
            return out
        }
    }
}
