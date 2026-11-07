package dev.aetherx86.ferrite.live

import dev.aetherx86.ferrite.core.KstTime
import dev.aetherx86.ferrite.data.CongestionTable
import kotlin.math.abs
import kotlin.math.exp

enum class LoadLevel(val label: String) { ROOMY("여유"), MODERATE("보통"), CROWDED("혼잡") }

data class CarLoad(val car: Int, val percent: Int) {
    val level: LoadLevel
        get() = when {
            percent < ROOMY_BELOW -> LoadLevel.ROOMY
            percent < CROWDED_FROM -> LoadLevel.MODERATE
            else -> LoadLevel.CROWDED
        }

    companion object {
        const val ROOMY_BELOW = 60
        const val CROWDED_FROM = 100
    }
}

/**
 * Estimates how full each car of a train is. No public source reports this per car.
 *
 * The overall level is the operator's published average for this station, direction and half hour, where one
 * exists. How that splits along the train follows a simple shape: cars near the stairs fill up, and the ends of a
 * train stay emptier. Screens say this is an estimate.
 */
object CarLoads {
    fun estimate(
        table: CongestionTable?,
        lineId: String,
        cars: Int,
        stationId: Int,
        up: Boolean?,
        time: KstTime,
        trainSeed: Int,
    ): List<CarLoad> {
        val average = averageLoad(table, lineId, stationId, up, time)
        // The busiest part of a train depends on where the stairs are, which varies by station.
        val center = 0.3f + 0.4f * (positiveHash(stationId * 31 + (if (up == true) 7 else 0)) % 100) / 100f
        val raw = FloatArray(cars) { i ->
            val position = if (cars == 1) 0.5f else i.toFloat() / (cars - 1)
            val distance = position - center
            val noise = (positiveHash(trainSeed * 131 + i * 17) % 100 - 50) / 50f * 0.16f
            0.55f + 0.9f * exp(-(distance * distance) / 0.09f) + noise
        }
        val mean = raw.sum() / cars
        return List(cars) { i ->
            CarLoad(car = i + 1, percent = (average * raw[i] / mean).toInt().coerceIn(2, 170))
        }
    }

    private fun averageLoad(table: CongestionTable?, lineId: String, stationId: Int, up: Boolean?, time: KstTime): Float {
        val published = up?.let { table?.load(lineId, stationId, it, time.serviceWeekday, time.serviceMinute) }
        if (published != null) return published.toFloat()
        val both = listOfNotNull(
            table?.load(lineId, stationId, true, time.serviceWeekday, time.serviceMinute),
            table?.load(lineId, stationId, false, time.serviceWeekday, time.serviceMinute),
        )
        if (both.isNotEmpty()) return both.average().toFloat()
        return typicalLoad(time)
    }

    /** A rough daily shape for lines with no published figures. */
    private fun typicalLoad(time: KstTime): Float {
        val hour = time.serviceMinute / 60f
        val morning = exp(-((hour - 8.2f) * (hour - 8.2f)) / 1.1f)
        val evening = exp(-((hour - 18.3f) * (hour - 18.3f)) / 1.8f)
        val weight = if (time.serviceWeekday >= 5) 0.45f else 1f
        return 18f + 55f * weight * maxOf(morning, evening) + 12f * exp(-((hour - 14f) * (hour - 14f)) / 16f)
    }

    private fun positiveHash(value: Int): Int {
        var x = value * -1640531527
        x = x xor (x ushr 15)
        x *= -1028477379
        return abs(x xor (x ushr 13))
    }
}
