package dev.aetherx86.ferrite.map

/** One feature from a map tile: its shape in tile coordinates and the few tags the app uses. */
class TileFeature(
    /** 1 point, 2 line, 3 polygon. */
    val type: Int,
    /** Parts of the shape, each as alternating x and y. */
    val parts: List<IntArray>,
    val tags: Map<String, String>,
)

class TileLayer(val name: String, val extent: Int, val features: List<TileFeature>)

/** Reads the standard binary vector tile format, keeping only what the map draws. */
object VectorTileReader {
    private val WANTED_TAGS = setOf("class", "subclass", "name", "name:ko", "rank", "brunnel")

    fun read(bytes: ByteArray, wanted: Set<String>): List<TileLayer> {
        val layers = ArrayList<TileLayer>()
        val reader = Reader(bytes, 0, bytes.size)
        while (reader.more()) {
            val tag = reader.varint().toInt()
            if (tag shr 3 == 3 && tag and 7 == 2) {
                readLayer(reader.nested(), wanted)?.let(layers::add)
            } else {
                reader.skip(tag and 7)
            }
        }
        return layers
    }

    private fun readLayer(reader: Reader, wanted: Set<String>): TileLayer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val rawFeatures = ArrayList<Reader>()
        while (reader.more()) {
            val tag = reader.varint().toInt()
            when (tag shr 3) {
                1 -> {
                    name = reader.string()
                    if (name !in wanted) return null
                }
                2 -> rawFeatures += reader.nested()
                3 -> keys += reader.string()
                4 -> values += readValue(reader.nested())
                5 -> extent = reader.varint().toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return TileLayer(name, extent, rawFeatures.map { readFeature(it, keys, values) })
    }

    private fun readValue(reader: Reader): String {
        var out = ""
        while (reader.more()) {
            val tag = reader.varint().toInt()
            when {
                tag shr 3 == 1 -> out = reader.string()
                tag and 7 == 0 -> out = reader.varint().toString()
                else -> reader.skip(tag and 7)
            }
        }
        return out
    }

    private fun readFeature(reader: Reader, keys: List<String>, values: List<String>): TileFeature {
        var type = 0
        val tags = HashMap<String, String>()
        var geometry = IntArray(0)
        while (reader.more()) {
            val tag = reader.varint().toInt()
            when (tag shr 3) {
                2 -> {
                    val packed = reader.nested()
                    while (packed.more()) {
                        val key = keys.getOrNull(packed.varint().toInt())
                        val value = values.getOrNull(packed.varint().toInt())
                        if (key != null && value != null && key in WANTED_TAGS) tags[key] = value
                    }
                }
                3 -> type = reader.varint().toInt()
                4 -> {
                    val packed = reader.nested()
                    val list = ArrayList<Int>()
                    while (packed.more()) list += packed.varint().toInt()
                    geometry = list.toIntArray()
                }
                else -> reader.skip(tag and 7)
            }
        }
        return TileFeature(type, decodeGeometry(geometry), tags)
    }

    /** Move and line commands, with coordinates as zigzag deltas. */
    fun decodeGeometry(commands: IntArray): List<IntArray> {
        val parts = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        while (i < commands.size) {
            val command = commands[i++]
            val id = command and 7
            val count = command ushr 3
            when (id) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= commands.size) return@repeat
                    if (id == 1 && current.isNotEmpty()) {
                        parts += current.toIntArray()
                        current = ArrayList()
                    }
                    x += zigzag(commands[i++])
                    y += zigzag(commands[i++])
                    current += x
                    current += y
                }
                7 -> if (current.size >= 2) {
                    current += current[0]
                    current += current[1]
                }
            }
        }
        if (current.isNotEmpty()) parts += current.toIntArray()
        return parts
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private class Reader(private val bytes: ByteArray, private var at: Int, private val end: Int) {
        fun more() = at < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (at < end) {
                val b = bytes[at++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b >= 0) break
                shift += 7
            }
            return result
        }

        fun nested(): Reader {
            val length = varint().toInt()
            val reader = Reader(bytes, at, minOf(at + length, end))
            at = minOf(at + length, end)
            return reader
        }

        fun string(): String {
            val length = varint().toInt()
            val text = bytes.decodeToString(at, minOf(at + length, end))
            at = minOf(at + length, end)
            return text
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> at += 8
                2 -> at += varint().toInt()
                5 -> at += 4
            }
        }
    }
}
