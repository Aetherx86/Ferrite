package dev.aetherx86.ferrite.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CongestionDto(
    val slots: List<String>,
    val weekday: Map<String, List<Int>>,
    val saturday: Map<String, List<Int>>,
    val sunday: Map<String, List<Int>>,
    val codes: Map<String, Int>,
)

/**
 * Average train load by half hour, for each station and direction, from the operator's published statistics.
 *
 * The figures are whole-train averages over a quarter. They only exist for the lines that operator runs, and
 * nothing here is measured on the train in front of you.
 */
class CongestionTable(private val dto: CongestionDto) {
    /**
     * Load in percent, where 100 means every seat and handhold is taken.
     *
     * @param up the direction towards lower station codes, or the outer loop on the circle line
     * @param weekday Monday is 0
     * @param minute minutes since midnight of the service day, which can go past 1440
     */
    fun load(lineId: String, stationId: Int, up: Boolean, weekday: Int, minute: Int): Int? {
        val table = when (weekday) {
            5 -> dto.saturday
            6 -> dto.sunday
            else -> dto.weekday
        }
        val values = table["$lineId|$stationId|${if (up) "U" else "D"}"] ?: return null
        val index = ((minute - FIRST_SLOT_MINUTE) / SLOT_MINUTES).coerceIn(0, values.size - 1)
        return values[index]
    }

    /** Which way round a hop runs, going by station codes. */
    fun isUp(lineId: String, from: Int, to: Int): Boolean? {
        val a = dto.codes["$lineId|$from"] ?: return null
        val b = dto.codes["$lineId|$to"] ?: return null
        if (a == b) return null
        // Line 1's codes grow northward, towards what its operator calls up. Everywhere else they grow away from it.
        return if (lineId == "S1") b > a else b < a
    }

    companion object {
        private const val FIRST_SLOT_MINUTE = 5 * 60 + 30
        private const val SLOT_MINUTES = 30
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CongestionTable = CongestionTable(json.decodeFromString(CongestionDto.serializer(), text))
    }
}
