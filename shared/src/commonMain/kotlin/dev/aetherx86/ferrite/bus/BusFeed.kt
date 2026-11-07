package dev.aetherx86.ferrite.bus

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.platform.httpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class BusStop(val id: String, val code: String, val name: String, val position: LatLng, val meters: Int, val cityCode: String?)

data class BusArrival(val route: String, val toward: String, val seconds: Int?, val stopsAway: Int?, val message: String, val lowFloor: Boolean)

class BusFeedException(message: String) : Exception(message)

/**
 * Bus arrivals. Seoul runs its own service, and the national transit center covers the other cities, so which one is
 * asked depends on where you are. Both accept a key from the national open data portal.
 */
object BusFeed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val NATIONAL = "https://apis.data.go.kr/1613000"
    private const val SEOUL = "http://ws.bus.go.kr/api/rest"

    private fun encodeKey(key: String): String =
        if ('%' in key) key else key.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")

    fun isSeoul(point: LatLng): Boolean = point.lat in 37.42..37.72 && point.lon in 126.76..127.19

    suspend fun nearbyStops(key: String, point: LatLng): List<BusStop> {
        val stops = if (isSeoul(point)) {
            val url = "$SEOUL/stationinfo/getStationByPos?serviceKey=${encodeKey(key)}&tmX=${point.lon}&tmY=${point.lat}&radius=500&resultType=json"
            parseSeoulStops(get(url))
        } else {
            val url = "$NATIONAL/BusSttnInfoInqireService/getCrdntPrxmtSttnList?serviceKey=${encodeKey(key)}&gpsLati=${point.lat}&gpsLong=${point.lon}&numOfRows=30&pageNo=1&_type=json"
            parseNationalStops(get(url), point)
        }
        return stops.sortedBy { it.meters }
    }

    suspend fun arrivals(key: String, stop: BusStop): List<BusArrival> {
        val list = if (stop.cityCode == null) {
            parseSeoulArrivals(get("$SEOUL/stationinfo/getStationByUid?serviceKey=${encodeKey(key)}&arsId=${stop.code}&resultType=json"))
        } else {
            parseNationalArrivals(get("$NATIONAL/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList?serviceKey=${encodeKey(key)}&cityCode=${stop.cityCode}&nodeId=${stop.id}&numOfRows=60&pageNo=1&_type=json"))
        }
        return list.sortedBy { it.seconds ?: Int.MAX_VALUE }
    }

    private suspend fun get(url: String): String {
        val result = httpRequest(url)
        if (result.status != 200) throw BusFeedException("서버 응답 ${result.status}")
        if (result.body.trimStart().startsWith("<")) {
            throw BusFeedException(Regex("<returnAuthMsg>(.*?)</returnAuthMsg>").find(result.body)?.groupValues?.get(1) ?: "서비스 키를 확인해 주세요")
        }
        return result.body
    }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.content.orEmpty()

    private fun list(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(element)
        else -> emptyList()
    }

    private fun nationalItems(text: String): List<JsonObject> {
        val body = json.parseToJsonElement(text).jsonObject["response"]?.jsonObject?.get("body") as? JsonObject ?: return emptyList()
        val items = body["items"] as? JsonObject ?: return emptyList()
        return list(items["item"])
    }

    private fun seoulItems(text: String): List<JsonObject> {
        val root = json.parseToJsonElement(text).jsonObject
        val header = root["msgHeader"] as? JsonObject
        val code = header?.get("headerCd").text()
        if (code.isNotEmpty() && code != "0" && code != "4") throw BusFeedException(header?.get("headerMsg").text().ifEmpty { code })
        return list((root["msgBody"] as? JsonObject)?.get("itemList"))
    }

    fun parseNationalStops(text: String, near: LatLng): List<BusStop> = nationalItems(text).mapNotNull { item ->
        val lat = item["gpslati"].text().toDoubleOrNull() ?: return@mapNotNull null
        val lon = item["gpslong"].text().toDoubleOrNull() ?: return@mapNotNull null
        val position = LatLng(lat, lon)
        BusStop(item["nodeid"].text(), item["nodeno"].text(), item["nodenm"].text(), position, distanceMeters(near, position).toInt(), item["citycode"].text())
    }

    fun parseNationalArrivals(text: String): List<BusArrival> = nationalItems(text).map { item ->
        val seconds = item["arrtime"].text().toIntOrNull()
        val stops = item["arrprevstationcnt"].text().toIntOrNull()
        BusArrival(
            route = item["routeno"].text(),
            toward = "",
            seconds = seconds,
            stopsAway = stops,
            message = stops?.let { "$it 정거장 전" }.orEmpty(),
            lowFloor = item["vehicletp"].text() == "저상버스",
        )
    }

    fun parseSeoulStops(text: String): List<BusStop> = seoulItems(text).mapNotNull { item ->
        val lat = item["gpsY"].text().toDoubleOrNull() ?: return@mapNotNull null
        val lon = item["gpsX"].text().toDoubleOrNull() ?: return@mapNotNull null
        BusStop(item["stationId"].text(), item["arsId"].text(), item["stationNm"].text(), LatLng(lat, lon), item["dist"].text().toDoubleOrNull()?.toInt() ?: 0, null)
    }

    /** Seoul reports each arrival as text, such as "3분12초후[2번째 전]" or "곧 도착". */
    fun parseSeoulArrivals(text: String): List<BusArrival> = seoulItems(text).map { item ->
        val message = item["arrmsg1"].text()
        val minutes = Regex("""(\d+)분""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val rest = Regex("""(\d+)초""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val seconds = when {
            minutes != null -> minutes * 60 + (rest ?: 0)
            rest != null -> rest
            "곧" in message -> 30
            else -> null
        }
        BusArrival(
            route = item["rtNm"].text(),
            toward = item["adirection"].text(),
            seconds = seconds,
            stopsAway = Regex("""(\d+)번째""").find(message)?.groupValues?.get(1)?.toIntOrNull(),
            message = message,
            lowFloor = item["busType1"].text() == "1",
        )
    }

    /** Made-up stops for the demonstration mode. */
    fun demoStops(near: LatLng): List<BusStop> = listOf(
        BusStop("demo1", "03101", "삼각지역", LatLng(near.lat + 0.0006, near.lon + 0.0003), 70, null),
        BusStop("demo2", "03102", "전쟁기념관 앞", LatLng(near.lat - 0.0012, near.lon + 0.0018), 210, null),
        BusStop("demo3", "03215", "용산우체국", LatLng(near.lat + 0.0021, near.lon - 0.0014), 280, null),
    )

    fun demoArrivals(): List<BusArrival> = listOf(
        BusArrival("110A", "고려대", 95, 1, "1 정거장 전", true),
        BusArrival("421", "염곡동", 260, 4, "4 정거장 전", false),
        BusArrival("740", "덕은동", 480, 7, "7 정거장 전", true),
        BusArrival("N62", "면목동", null, null, "운행 대기", false),
    )
}
