package dev.aetherx86.ferrite.live

import dev.aetherx86.ferrite.core.KstTime
import dev.aetherx86.ferrite.core.kstOf

/**
 * Works out when trains should come, from how often a line usually runs.
 *
 * This is what a board falls back on with no live report. It is labelled as an estimate on screen, because the
 * numbers come from typical service intervals, and a real train could be anywhere.
 */
object ServiceEstimator {
    private const val FIRST_MINUTE = 5 * 60 + 30
    private const val LAST_MINUTE = 24 * 60 + 30

    /** Peak, off-peak and weekend intervals in minutes. */
    private val INTERVALS = mapOf(
        "S1" to Triple(4, 8, 9), "S2" to Triple(3, 6, 6), "S3" to Triple(4, 7, 8), "S4" to Triple(3, 6, 7),
        "S5" to Triple(4, 7, 8), "S6" to Triple(5, 8, 9), "S7" to Triple(4, 7, 8), "S8" to Triple(5, 8, 9),
        "S9" to Triple(4, 7, 8), "GJ" to Triple(8, 15, 15), "SB" to Triple(4, 10, 12), "SN" to Triple(4, 7, 8),
        "GC" to Triple(12, 20, 25), "AR" to Triple(6, 10, 12), "WS" to Triple(12, 20, 20), "GG" to Triple(10, 18, 20),
        "GA" to Triple(10, 15, 17), "I1" to Triple(4, 8, 9), "I2" to Triple(4, 7, 8), "UI" to Triple(3, 6, 7),
        "SL" to Triple(4, 7, 8), "GP" to Triple(3, 6, 7), "EV" to Triple(4, 8, 9), "UJ" to Triple(4, 7, 8),
        "GO" to Triple(60, 90, 90), "B1" to Triple(4, 7, 8), "B2" to Triple(5, 8, 9), "B3" to Triple(6, 8, 9),
        "B4" to Triple(5, 8, 9), "BG" to Triple(5, 9, 10), "DH" to Triple(15, 25, 25), "D1" to Triple(5, 8, 9),
        "D2" to Triple(5, 8, 9), "D3" to Triple(5, 8, 9), "DK" to Triple(25, 40, 40), "DJ" to Triple(5, 8, 10),
        "GW" to Triple(5, 9, 10),
    )

    fun intervalMinutes(lineId: String, weekday: Int, minute: Int): Int {
        val (peak, offPeak, weekend) = INTERVALS[lineId] ?: Triple(6, 10, 12)
        if (weekday >= 5) return weekend
        val rush = minute in 7 * 60..9 * 60 || minute in 18 * 60..20 * 60
        return if (rush) peak else offPeak
    }

    /**
     * The next few trains at a platform.
     *
     * A train leaves the start of the line at each interval and takes [BoardPath.secondsFromOrigin] to get here,
     * so neighbouring stations agree with each other about where a train is.
     */
    fun next(path: BoardPath, nowMs: Long, count: Int = 3): ArrivalFeed {
        val now = kstOf(nowMs)
        val startOfDay = nowMs - serviceSeconds(now) * 1000L
        val trains = ArrayList<Approach>()
        var departure = FIRST_MINUTE
        // Trains that already left the far end of the line can still be on the way here.
        val travelMinutes = path.secondsFromOrigin / 60.0
        while (departure <= LAST_MINUTE && trains.size < count) {
            val arrivalSeconds = departure * 60L + path.secondsFromOrigin
            val arrivalAt = startOfDay + arrivalSeconds * 1000L
            if (arrivalAt + BoardEngine.DWELL_MS > nowMs) {
                trains += Approach(
                    trainNumber = null,
                    destination = path.terminus.name,
                    arrivalAtMs = arrivalAt,
                )
            }
            departure += intervalMinutes(path.line.id, now.serviceWeekday, (departure + travelMinutes).toInt())
        }
        // The last train of the night has gone, so the next one is the first of tomorrow.
        if (trains.isEmpty()) {
            trains += Approach(
                trainNumber = null,
                destination = path.terminus.name,
                arrivalAtMs = startOfDay + (1440 + FIRST_MINUTE) * 60_000L + path.secondsFromOrigin * 1000L,
            )
        }
        return ArrivalFeed(FeedSource.ESTIMATE, trains, nowMs)
    }

    private fun serviceSeconds(time: KstTime): Long =
        time.serviceMinute * 60L + time.second
}
