package dev.aetherx86.ferrite.live

import dev.aetherx86.ferrite.core.epochOfKst
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SeoulArrivalDto(
    val subwayId: String = "",
    val updnLine: String = "",
    val trainLineNm: String = "",
    val statnNm: String = "",
    val btrainSttus: String? = null,
    val barvlDt: String = "0",
    val btrainNo: String = "",
    val bstatnNm: String = "",
    val recptnDt: String = "",
    val arvlMsg2: String = "",
    val arvlMsg3: String = "",
    val arvlCd: String = "",
    val lstcarAt: String = "0",
)

@Serializable
data class SeoulArrivalResponse(
    val realtimeArrivalList: List<SeoulArrivalDto> = emptyList(),
    val errorMessage: SeoulError? = null,
    val status: Int? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class SeoulError(val status: Int = 0, val code: String = "", val message: String = "", @SerialName("total") val total: Int = 0)

/** A failure worth telling the user about, such as a rejected key. */
class FeedException(message: String) : Exception(message)

/**
 * The capital area's live arrival feed. It only answers over plain HTTP, which the app allows for this one host.
 */
object SeoulArrivals {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private const val MOVING_FROM_FAR_SECONDS_PER_STOP = 130

    suspend fun fetch(key: String, stationName: String): List<SeoulArrivalDto> {
        val url = "http://swopenapi.seoul.go.kr/api/subway/${encode(key)}/json/realtimeStationArrival/0/40/${encode(apiName(stationName))}"
        val result = httpRequest(url)
        if (result.status != 200 && result.body.isBlank()) throw FeedException("서버 응답 ${result.status}")
        return parse(result.body)
    }

    fun parse(text: String): List<SeoulArrivalDto> {
        val response = json.decodeFromString(SeoulArrivalResponse.serializer(), text)
        val code = response.errorMessage?.code ?: response.code
        val message = response.errorMessage?.message ?: response.message
        if (response.realtimeArrivalList.isEmpty() && code != null) {
            // No trains near this station right now is an answer, not a failure.
            if (code == "INFO-200") return emptyList()
            if (code != "INFO-000") throw FeedException(message ?: code)
        }
        return response.realtimeArrivalList
    }

    /** The feed knows a few stations by another name. */
    private fun apiName(name: String): String = when (name) {
        "서울역" -> "서울"
        else -> name
    }

    /**
     * The trains in a report that will stop at this platform, going this way.
     *
     * The report's direction text reads like "불암산행 - 숙대입구방면", and naming the next station settles direction
     * on loops and branches, where up and down would not.
     */
    fun approaches(rows: List<SeoulArrivalDto>, lineLiveId: Int, nextStationName: String?, nowMs: Long): List<Approach> =
        rows.asSequence()
            .filter { it.subwayId == lineLiveId.toString() }
            .filter { row -> nextStationName == null || row.trainLineNm.substringAfter(" - ", "").contains(nextStationName.removeSuffix("역")) }
            .map { toApproach(it, nowMs) }
            .sortedBy { it.arrivalAtMs }
            .toList()

    fun toApproach(row: SeoulArrivalDto, nowMs: Long): Approach {
        val reportedAt = parseKst(row.recptnDt)?.takeIf { it <= nowMs + 60_000 && it > nowMs - 600_000 } ?: nowMs
        val seconds = row.barvlDt.toIntOrNull() ?: 0
        val arrival = when {
            seconds > 0 -> reportedAt + seconds * 1000L
            // Reports for a train standing at a station can be minutes old. It is still there, so it stays there.
            row.arvlCd == "1" -> maxOf(reportedAt, nowMs - BoardEngine.DWELL_MS / 2)
            else -> reportedAt + secondsFromStatus(row) * 1000L
        }
        return Approach(
            trainNumber = row.btrainNo.takeIf { it.isNotBlank() },
            destination = row.bstatnNm.ifBlank { row.trainLineNm.substringBefore("행") }.substringBefore("(").trim(),
            arrivalAtMs = arrival,
            express = row.btrainSttus != null && row.btrainSttus != "일반",
            lastTrain = row.lstcarAt == "1",
        )
    }

    /** Many reports give no seconds, only where the train is. */
    private fun secondsFromStatus(row: SeoulArrivalDto): Int = when (row.arvlCd) {
        "0" -> 15 // Entering this station.
        "1" -> 0 // At this station.
        "2" -> -BoardEngine.DWELL_MS.toInt() / 1000 // Leaving this station.
        "3" -> 90 // Left the one before.
        "4" -> 130 // Entering the one before.
        "5" -> 120 // At the one before.
        else -> {
            val stops = Regex("""\[(\d+)]번째""").find(row.arvlMsg2)?.groupValues?.get(1)?.toIntOrNull()
            val minutes = Regex("""(\d+)분""").find(row.arvlMsg2)?.groupValues?.get(1)?.toIntOrNull()
            val rest = Regex("""(\d+)초""").find(row.arvlMsg2)?.groupValues?.get(1)?.toIntOrNull()
            when {
                minutes != null -> minutes * 60 + (rest ?: 0)
                rest != null -> rest
                stops != null -> stops * MOVING_FROM_FAR_SECONDS_PER_STOP
                else -> 180
            }
        }
    }

    /** "2026-11-07 08:00:13" or the same with fractions, always Korean time. */
    fun parseKst(text: String): Long? {
        val parts = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(text)?.groupValues ?: return null
        return epochOfKst(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt(), parts[6].toInt())
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.~") append(c.toChar())
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
