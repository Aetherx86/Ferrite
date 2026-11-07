package dev.aetherx86.ferrite.airport

import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.two
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class Movement(val label: String, val placeLabel: String) { DEPARTURE("출발", "목적지"), ARRIVAL("도착", "출발지") }

data class Flight(
    val movement: Movement,
    val airline: String,
    val number: String,
    val place: String,
    val placeCode: String,
    /** HHmm. */
    val scheduled: String,
    val estimated: String,
    val status: String,
    val terminal: String,
    val gate: String,
    /** Check-in counters when leaving, the baggage belt when arriving. */
    val counter: String,
    val exit: String,
) {
    val isDelayed: Boolean get() = estimated.isNotBlank() && scheduled != estimated
}

@Serializable
private data class FlightDto(
    val airline: String = "",
    val flightId: String = "",
    val scheduleDateTime: String = "",
    val estimatedDateTime: String = "",
    val airport: String = "",
    val airportCode: String = "",
    val gatenumber: String = "",
    val chkinrange: String = "",
    val carousel: String = "",
    val exitnumber: String = "",
    val remark: String = "",
    val terminalId: String = "",
)

class FlightFeedException(message: String) : Exception(message)

/**
 * The airport operator's flight board service. It needs a free key from the national open data portal.
 */
object Flights {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private const val BASE = "https://apis.data.go.kr/B551177/StatusOfPassengerFlightsOdp"

    suspend fun fetch(key: String, movement: Movement, nowMs: Long): List<Flight> {
        val time = kstOf(nowMs - 3_600_000)
        val from = two(time.hour) + two(time.minute)
        val operation = if (movement == Movement.DEPARTURE) "getPassengerDeparturesOdp" else "getPassengerArrivalsOdp"
        val url = "$BASE/$operation?serviceKey=${encodeKey(key)}&from_time=$from&to_time=2400&lang=K&type=json"
        val result = httpRequest(url)
        if (result.status != 200) throw FlightFeedException("서버 응답 ${result.status}")
        return parse(result.body, movement)
    }

    /** A key copied from the portal may already be URL encoded, and must not be encoded twice. */
    private fun encodeKey(key: String): String =
        if ('%' in key) key else key.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")

    fun parse(text: String, movement: Movement): List<Flight> {
        if (text.trimStart().startsWith("<")) throw FlightFeedException(errorInXml(text))
        val root = json.parseToJsonElement(text).jsonObject
        val response = root["response"]?.jsonObject ?: throw FlightFeedException("응답 형식을 알 수 없습니다")
        val header = response["header"]?.jsonObject
        val code = header?.get("resultCode")?.toString()?.trim('"')
        if (code != null && code != "00") throw FlightFeedException(header["resultMsg"]?.toString()?.trim('"') ?: code)
        val items = response["body"]?.jsonObject?.get("items") ?: return emptyList()
        return listOf(items).flatMap(::rows).map { dto ->
            Flight(
                movement = movement,
                airline = dto.airline,
                number = dto.flightId,
                place = dto.airport,
                placeCode = dto.airportCode,
                scheduled = dto.scheduleDateTime,
                estimated = dto.estimatedDateTime,
                status = dto.remark,
                terminal = terminalName(dto.terminalId),
                gate = dto.gatenumber,
                counter = dto.chkinrange.ifBlank { dto.carousel },
                exit = dto.exitnumber,
            )
        }.sortedBy { it.estimated.ifBlank { it.scheduled } }
    }

    private fun rows(element: JsonElement): List<FlightDto> = when (element) {
        is JsonArray -> element.map { json.decodeFromJsonElement(FlightDto.serializer(), it) }
        is JsonObject -> element["item"]?.let(::rows) ?: listOf(json.decodeFromJsonElement(FlightDto.serializer(), element))
        else -> emptyList()
    }

    private fun errorInXml(text: String): String =
        Regex("<returnAuthMsg>(.*?)</returnAuthMsg>").find(text)?.groupValues?.get(1)
            ?: Regex("<errMsg>(.*?)</errMsg>").find(text)?.groupValues?.get(1)
            ?: "서비스 키를 확인해 주세요"

    fun terminalName(id: String): String = when (id) {
        "P01" -> "T1"
        "P02" -> "탑승동"
        "P03" -> "T2"
        else -> id
    }

    /** Made-up flights for the demonstration mode. The carrier names are fictional, and the screen says so. */
    fun demo(movement: Movement, nowMs: Long): List<Flight> {
        val base = kstOf(nowMs)
        val rows = listOf(
            listOf("오로라항공", "AU 701", "도쿄/나리타", "NRT", "T1", "12", "D", "탑승중", 12),
            listOf("하늘길에어", "SK 213", "오사카/간사이", "KIX", "T1", "27", "F", "탑승준비", 24),
            listOf("블루윙", "BW 881", "방콕/수완나품", "BKK", "T2", "251", "H", "", 41),
            listOf("오로라항공", "AU 37", "로스앤젤레스", "LAX", "T2", "233", "E", "지연", 58),
            listOf("퍼시픽원", "PO 512", "싱가포르", "SIN", "탑승동", "118", "K", "", 72),
            listOf("하늘길에어", "SK 95", "파리/샤를드골", "CDG", "T2", "247", "C", "", 89),
            listOf("블루윙", "BW 302", "호치민", "SGN", "T1", "33", "J", "결항", 104),
            listOf("퍼시픽원", "PO 18", "시드니", "SYD", "탑승동", "122", "M", "", 121),
            listOf("오로라항공", "AU 613", "홍콩", "HKG", "T1", "45", "B", "", 138),
            listOf("하늘길에어", "SK 460", "타이베이/타오위안", "TPE", "T2", "262", "G", "", 156),
            listOf("블루윙", "BW 77", "뉴욕/존 F. 케네디", "JFK", "T2", "230", "A", "", 175),
            listOf("퍼시픽원", "PO 240", "하노이", "HAN", "T1", "16", "L", "", 190),
        )
        return rows.map { r ->
            val offset = r[8] as Int
            val minutes = base.minuteOfDay + offset
            val scheduled = "${two(minutes / 60 % 24)}${two(minutes % 60)}"
            val status = r[7] as String
            val delayed = status == "지연"
            val shown = if (delayed) (minutes + 35).let { "${two(it / 60 % 24)}${two(it % 60)}" } else scheduled
            Flight(
                movement = movement,
                airline = r[0] as String,
                number = r[1] as String,
                place = r[2] as String,
                placeCode = r[3] as String,
                scheduled = scheduled,
                estimated = shown,
                status = if (movement == Movement.ARRIVAL && status.startsWith("탑승")) "착륙" else status,
                terminal = r[4] as String,
                gate = r[5] as String,
                counter = if (movement == Movement.DEPARTURE) "${r[6]}01-${r[6]}18" else (offset % 20 + 1).toString(),
                exit = if (movement == Movement.ARRIVAL) "${'A' + offset % 6}" else "",
            )
        }
    }
}
