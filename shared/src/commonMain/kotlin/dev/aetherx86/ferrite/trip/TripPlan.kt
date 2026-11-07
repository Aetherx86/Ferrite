package dev.aetherx86.ferrite.trip

import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TripStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val stayMinutes: Int = 60,
    val memo: String = "",
    val cost: Int = 0,
) {
    val position: LatLng get() = LatLng(lat, lon)
}

@Serializable
data class TripDay(val stops: List<TripStop> = emptyList(), val startMinute: Int = 9 * 60)

@Serializable
data class TripPlan(val days: List<TripDay> = listOf(TripDay()), val walking: Boolean = false)

/** One stop in time, with the travel that gets you there. */
data class TimedStop(val stop: TripStop, val arriveMinute: Int, val leaveMinute: Int, val travelMinutes: Int)

/** Works out a day's timeline from the travel times between stops. */
fun timeline(day: TripDay, legSeconds: List<Int>): List<TimedStop> {
    var clock = day.startMinute
    return day.stops.mapIndexed { index, stop ->
        val travel = if (index == 0) 0 else ((legSeconds.getOrElse(index - 1) { 0 } + 30) / 60)
        clock += travel
        val arrive = clock
        clock += stop.stayMinutes
        TimedStop(stop, arrive, clock, travel)
    }
}

/** Puts stops in the order an optimizer returned, ignoring anything out of range. */
fun reorder(stops: List<TripStop>, order: List<Int>): List<TripStop> {
    val picked = order.mapNotNull { stops.getOrNull(it) }
    return if (picked.size == stops.size) picked else stops
}

class TripStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): TripPlan = store.getString(KEY)?.let { runCatching { json.decodeFromString(TripPlan.serializer(), it) }.getOrNull() } ?: TripPlan()

    fun save(plan: TripPlan) = store.putString(KEY, json.encodeToString(TripPlan.serializer(), plan))

    private companion object {
        const val KEY = "trip"
    }
}
