package dev.omakasekim.ferrite.route

import dev.omakasekim.ferrite.core.kstOf
import dev.omakasekim.ferrite.live.ServiceEstimator
import kotlin.math.roundToInt

/** How much time to allow for walking between platforms. */
enum class TransferPace(val label: String, val walkFactor: Double, val bufferSeconds: Int) {
    NORMAL("보통 걸음", 1.0, 0),

    /** For a slower walk, a pram, luggage or anyone who would rather not hurry: walks take half as long again, plus a minute. */
    RELAXED("여유 있게 환승", 1.5, 60),
}

/** What a rail trip search should assume. */
data class RouteOptions(
    /**
     * When the trip starts, in epoch milliseconds. With it, each wait is worked out from how often the next line runs
     * at the moment you reach its platform. Without it, every wait is a flat average.
     */
    val departAtMs: Long? = null,
    val pace: TransferPace = TransferPace.NORMAL,
    /** The most changes a trip may have. More than four is never a sensible way across one city's network. */
    val maxTransfers: Int = 4,
)

/** The cost of changing trains: the walk between platforms, and the wait for the next train. */
object TransferModel {
    /** The flat wait used when the time of travel is not known, and the one the planner has always used. */
    const val AVERAGE_WAIT_SECONDS = 180

    /** The measured walk, slowed down for the chosen pace. */
    fun walkSeconds(measuredSeconds: Int, pace: TransferPace): Int =
        (measuredSeconds * pace.walkFactor).roundToInt() + pace.bufferSeconds

    /**
     * The expected wait for a train on [lineId], arriving on the platform at [atMs] with no timetable in hand: half
     * the line's usual interval at that hour and day, which is the average wait for trains that come at even
     * intervals. Outside the hours trains run this still gives the off-peak figure, and [inService] tells the two apart.
     */
    fun waitSeconds(lineId: String, atMs: Long?): Int {
        if (atMs == null) return AVERAGE_WAIT_SECONDS
        val time = kstOf(atMs)
        return ServiceEstimator.intervalMinutes(lineId, time.serviceWeekday, time.serviceMinute) * 60 / 2
    }

    /** Whether trains usually run at this moment. */
    fun inService(atMs: Long): Boolean =
        kstOf(atMs).serviceMinute in ServiceEstimator.FIRST_MINUTE..ServiceEstimator.LAST_MINUTE
}
