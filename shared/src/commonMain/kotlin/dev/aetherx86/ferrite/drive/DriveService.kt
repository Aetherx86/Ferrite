package dev.aetherx86.ferrite.drive

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.platform.httpRequest
import dev.aetherx86.ferrite.walk.StepKind
import dev.aetherx86.ferrite.walk.WalkProfile
import dev.aetherx86.ferrite.walk.WalkRoute
import dev.aetherx86.ferrite.walk.WalkService
import dev.aetherx86.ferrite.walk.WalkStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class Vehicle(val label: String) {
    CAR("승용차"), LIGHT("경차"), EV("전기차"), TRUCK("트럭"), MOTORBIKE("이륜차"), BICYCLE("자전거"),
}

data class DriveOptions(
    val vehicle: Vehicle = Vehicle.CAR,
    val avoidTolls: Boolean = false,
    val truckHeightMeters: Double = 3.5,
    val truckWeightTons: Double = 10.0,
)

class DriveRoute(
    val route: WalkRoute,
    val hasToll: Boolean,
    val hasHighway: Boolean,
    /** Time and length of each part, for routes with stops along the way. */
    val legs: List<Pair<Int, Double>>,
)

/**
 * Road routes by kind of vehicle, from the same open routing service as walking.
 *
 * Trucks avoid roads they are too tall or heavy for, as far as limits are mapped. Motorbikes keep off roads reserved
 * for cars. Times assume normal traffic, because no free source reports live traffic, and the screen says so.
 */
object DriveService {
    private val json = Json { ignoreUnknownKeys = true }
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de"

    suspend fun route(points: List<LatLng>, options: DriveOptions): DriveRoute {
        val body = request(points, costingOf(options.vehicle), optionsJson(options))
        val result = httpRequest(
            url = "$ENDPOINT/route?json=" + WalkService.percentEncode(body.toString()),
            headers = mapOf("User-Agent" to "Ferrite/0.1"),
            timeoutMs = 20_000,
        )
        if (result.status != 200) throw RuntimeException("경로 서버 응답 ${result.status}")
        return parse(result.body)
    }

    /** The best order to visit places in, with the first and last kept where they are. */
    suspend fun bestOrder(points: List<LatLng>, walking: Boolean): List<Int> {
        val body = request(points, if (walking) "pedestrian" else "auto", null)
        val result = httpRequest(
            url = "$ENDPOINT/optimized_route?json=" + WalkService.percentEncode(body.toString()),
            headers = mapOf("User-Agent" to "Ferrite/0.1"),
            timeoutMs = 20_000,
        )
        if (result.status != 200) throw RuntimeException("경로 서버 응답 ${result.status}")
        return parseOrder(result.body)
    }

    suspend fun walkingOrDriving(points: List<LatLng>, walking: Boolean): DriveRoute {
        val body = request(points, if (walking) "pedestrian" else "auto", null)
        val result = httpRequest(
            url = "$ENDPOINT/route?json=" + WalkService.percentEncode(body.toString()),
            headers = mapOf("User-Agent" to "Ferrite/0.1"),
            timeoutMs = 20_000,
        )
        if (result.status != 200) throw RuntimeException("경로 서버 응답 ${result.status}")
        return parse(result.body)
    }

    private fun costingOf(vehicle: Vehicle) = when (vehicle) {
        Vehicle.CAR, Vehicle.LIGHT, Vehicle.EV -> "auto"
        Vehicle.TRUCK -> "truck"
        Vehicle.MOTORBIKE -> "motor_scooter"
        Vehicle.BICYCLE -> "bicycle"
    }

    private fun optionsJson(options: DriveOptions): JsonObject = buildJsonObject {
        val cost = buildJsonObject {
            if (options.avoidTolls) put("use_tolls", 0.0)
            if (options.vehicle == Vehicle.TRUCK) {
                put("height", options.truckHeightMeters)
                put("weight", options.truckWeightTons)
            }
            if (options.vehicle == Vehicle.MOTORBIKE) put("use_highways", 0.0)
        }
        put(costingOf(options.vehicle), cost)
    }

    private fun request(points: List<LatLng>, costing: String, costingOptions: JsonObject?) = buildJsonObject {
        putJsonArray("locations") {
            points.forEach { p ->
                add(
                    buildJsonObject {
                        put("lat", p.lat)
                        put("lon", p.lon)
                    },
                )
            }
        }
        put("costing", costing)
        if (costingOptions != null) put("costing_options", costingOptions)
        put("language", "ko-KR")
        putJsonObject("directions_options") {
            put("units", "kilometers")
            put("language", "ko-KR")
        }
    }

    fun parse(text: String): DriveRoute {
        val trip = json.parseToJsonElement(text).jsonObject["trip"]?.jsonObject ?: throw RuntimeException("경로 없음")
        val summary = trip.getValue("summary").jsonObject
        val legs = trip.getValue("legs").jsonArray.map { it.jsonObject }
        val points = legs.flatMap { WalkService.decodeShape(it.getValue("shape").jsonPrimitive.content) }
        var offset = 0
        val steps = ArrayList<WalkStep>()
        for (leg in legs) {
            val shape = WalkService.decodeShape(leg.getValue("shape").jsonPrimitive.content)
            for (element in leg.getValue("maneuvers").jsonArray) {
                val m = element.jsonObject
                val instruction = m["instruction"]?.jsonPrimitive?.content.orEmpty()
                val meters = (m["length"]?.jsonPrimitive?.double ?: 0.0) * 1000
                val kind = WalkService.kindOf(m.getValue("type").jsonPrimitive.int, instruction)
                val at = shape.getOrElse(m["begin_shape_index"]?.jsonPrimitive?.int ?: 0) { shape.first() }
                val label = if (meters >= 1000) "$instruction · ${(meters / 100).toInt() / 10.0}km" else if (meters >= 1) "$instruction · ${meters.toInt()}m" else instruction
                steps += WalkStep(kind, label, meters, at)
            }
            offset += shape.size
        }
        val route = WalkRoute(
            profile = WalkProfile.FASTEST,
            points = points,
            meters = summary.getValue("length").jsonPrimitive.double * 1000,
            seconds = summary.getValue("time").jsonPrimitive.double.toInt(),
            stairs = steps.count { it.kind == StepKind.STAIRS },
            crossings = 0,
            steps = steps,
        )
        return DriveRoute(
            route,
            hasToll = summary["has_toll"]?.jsonPrimitive?.boolean ?: false,
            hasHighway = summary["has_highway"]?.jsonPrimitive?.boolean ?: false,
            legs = legs.map { leg ->
                val s = leg.getValue("summary").jsonObject
                s.getValue("time").jsonPrimitive.double.toInt() to s.getValue("length").jsonPrimitive.double * 1000
            },
        )
    }

    /** Which input each stop of an optimized answer was. */
    fun parseOrder(text: String): List<Int> {
        val trip = json.parseToJsonElement(text).jsonObject["trip"]?.jsonObject ?: throw RuntimeException("경로 없음")
        return trip.getValue("locations").jsonArray.map { it.jsonObject.getValue("original_index").jsonPrimitive.int }
    }
}

/** A Seoul taxi fare: the base for the first 1.6 km, then a step every 131 m, more late at night. A guide only. */
object TaxiFare {
    private const val BASE = 4800
    private const val BASE_METERS = 1600.0
    private const val STEP = 100
    private const val STEP_METERS = 131.0

    fun estimate(meters: Double, hour: Int): Int {
        val extra = ((meters - BASE_METERS) / STEP_METERS).coerceAtLeast(0.0)
        val day = BASE + kotlin.math.ceil(extra).toInt() * STEP
        val rate = when (hour) {
            23, 0, 1 -> 1.4
            22, 2, 3 -> 1.2
            else -> 1.0
        }
        return ((day * rate / 100).toInt()) * 100
    }
}

/** How far a battery goes. A guide: real use changes with speed, weather and hills. */
data class BatteryPlan(val percentNow: Int, val capacityKwh: Double, val kwhPerKm: Double) {
    val rangeKm: Double get() = capacityKwh * percentNow / 100 / kwhPerKm

    fun percentAfter(meters: Double): Int =
        (percentNow - meters / 1000 * kwhPerKm / capacityKwh * 100).toInt()

    /** How far along a route, in metres, to charge while keeping a reserve. */
    fun chargeBeforeMeters(reservePercent: Int = 12): Double =
        ((percentNow - reservePercent).coerceAtLeast(0) / 100.0 * capacityKwh / kwhPerKm * 1000)
}
