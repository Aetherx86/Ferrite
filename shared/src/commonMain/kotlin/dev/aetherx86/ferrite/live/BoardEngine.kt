package dev.aetherx86.ferrite.live

import kotlin.math.ceil

/** What a platform is doing about the next train. */
enum class Phase {
    /** Nothing is close yet. */
    WAITING,

    /** The train has left the station before this one. */
    PREVIOUS_DEPARTED,
    APPROACHING,
    ENTERING,

    /** Standing here with its doors open. */
    ARRIVED,

    /** Doors closing, about to go. */
    CLOSING,
}

/** A train on the strip. 0 is here, -1 the station before, +1 the station after, and in between is on the way. */
data class TrainMark(
    val trainNumber: String?,
    val position: Float,
    val moving: Boolean,
    /** The train the board is about. */
    val primary: Boolean,
)

/** One moment of a board. A pure function of the feed and the time, so it can be tested without a screen. */
data class BoardFrame(
    val phase: Phase,
    /** The train the big numbers are about. */
    val focus: Approach?,
    /** The one after it. */
    val following: Approach?,
    val secondsToFocus: Int,
    /** A train standing at the platform, including one just leaving. */
    val atPlatform: Approach?,
    val platformPhase: Phase?,
    /** How far the arrival color has spread, from 0 to 1. */
    val flood: Float,
    val trains: List<TrainMark>,
    /** The train behind has just become the one to watch. */
    val justSwitched: Boolean,
)

class BoardEngine(private val path: BoardPath) {
    fun frame(feed: ArrivalFeed, nowMs: Long): BoardFrame {
        val trains = feed.trains.sortedBy { it.arrivalAtMs }
        val atPlatform = trains.firstOrNull { nowMs >= it.arrivalAtMs && nowMs < it.arrivalAtMs + DWELL_MS }
        val leaving = trains.lastOrNull { nowMs >= it.arrivalAtMs + DWELL_MS && nowMs < it.arrivalAtMs + DWELL_MS + FLOOD_MS }
        val upcoming = trains.filter { it.arrivalAtMs > nowMs }
        val focus = atPlatform ?: upcoming.firstOrNull()
        val following = if (atPlatform != null) upcoming.firstOrNull() else upcoming.getOrNull(1)

        val floodSource = atPlatform ?: leaving
        val flood = when {
            atPlatform != null -> ((nowMs - atPlatform.arrivalAtMs) / FLOOD_MS.toFloat()).coerceIn(0f, 1f)
            leaving != null -> (1f - (nowMs - leaving.arrivalAtMs - DWELL_MS) / FLOOD_MS.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        val platformPhase = floodSource?.let {
            if (nowMs - it.arrivalAtMs >= DWELL_MS - CLOSING_MS) Phase.CLOSING else Phase.ARRIVED
        }

        val seconds = focus?.let { ceil((it.arrivalAtMs - nowMs) / 1000.0).toInt().coerceAtLeast(0) } ?: 0
        val phase = when {
            atPlatform != null -> platformPhase ?: Phase.ARRIVED
            focus == null -> Phase.WAITING
            seconds <= ENTERING_SECONDS -> Phase.ENTERING
            seconds <= APPROACHING_SECONDS -> Phase.APPROACHING
            seconds <= runSecondsIntoHere() -> Phase.PREVIOUS_DEPARTED
            else -> Phase.WAITING
        }

        val justSwitched = leaving != null && atPlatform == null
        val marks = trains.mapNotNull { mark(it, nowMs, primary = it === focus || it === atPlatform) }
        return BoardFrame(
            phase = phase,
            focus = focus,
            following = following,
            secondsToFocus = seconds,
            atPlatform = floodSource,
            platformPhase = platformPhase,
            flood = flood,
            trains = marks,
            justSwitched = justSwitched,
        )
    }

    private fun runSecondsIntoHere(): Int = runSeconds(path.upstreamSeconds.firstOrNull())

    /** A hop's time includes standing at a station, and the board treats that part separately. */
    private fun runSeconds(hopSeconds: Int?): Int = ((hopSeconds ?: DEFAULT_RUN_SECONDS) - DWELL_UPSTREAM_SECONDS).coerceAtLeast(MIN_RUN_SECONDS)

    private fun mark(train: Approach, nowMs: Long, primary: Boolean): TrainMark? {
        val elapsed = (nowMs - train.arrivalAtMs) / 1000f
        val dwell = DWELL_MS / 1000f
        return when {
            elapsed >= 0 && elapsed < dwell -> TrainMark(train.trainNumber, 0f, moving = false, primary)
            elapsed >= dwell -> forward(train, elapsed - dwell, primary)
            else -> backward(train, -elapsed, primary)
        }
    }

    private fun backward(train: Approach, secondsAway: Float, primary: Boolean): TrainMark? {
        var left = secondsAway
        var stationsBack = 0
        while (true) {
            val run = runSeconds(path.upstreamSeconds.getOrNull(stationsBack)).toFloat()
            if (left <= run) {
                val fraction = ease(1f - left / run)
                return TrainMark(train.trainNumber, -(stationsBack + 1) + fraction, moving = true, primary)
                    .takeIf { it.position >= -VISIBLE_BEHIND }
            }
            left -= run
            stationsBack++
            if (left <= DWELL_UPSTREAM_SECONDS) {
                return TrainMark(train.trainNumber, -stationsBack.toFloat(), moving = false, primary)
                    .takeIf { it.position >= -VISIBLE_BEHIND }
            }
            left -= DWELL_UPSTREAM_SECONDS
            if (stationsBack >= path.upstream.size + 1) return null
        }
    }

    private fun forward(train: Approach, secondsGone: Float, primary: Boolean): TrainMark? {
        if (path.next == null) return null
        val run = runSeconds(path.downstreamSeconds.firstOrNull()).toFloat()
        // It only takes a moment to clear the strip, so the train leaves faster than it would really run.
        val position = ease((secondsGone / (run * LEAVING_SPEEDUP)).coerceAtMost(1f)) * VISIBLE_AHEAD
        return if (secondsGone >= run * LEAVING_SPEEDUP) null else TrainMark(train.trainNumber, position, moving = true, primary)
    }

    private fun ease(t: Float): Float = t * t * (3 - 2 * t)

    companion object {
        /** A train stands here this long, closing its doors in the last part. */
        const val DWELL_MS = 20_000L
        const val CLOSING_MS = 7_000L
        const val FLOOD_MS = 700L
        const val APPROACHING_SECONDS = 13
        const val ENTERING_SECONDS = 5
        private const val DEFAULT_RUN_SECONDS = 120
        private const val DWELL_UPSTREAM_SECONDS = 20
        private const val MIN_RUN_SECONDS = 30
        private const val VISIBLE_BEHIND = 3.6f
        private const val VISIBLE_AHEAD = 1.6f
        private const val LEAVING_SPEEDUP = 0.28f
    }
}

/** The big number and its unit. Above a minute it reads in minutes, as platform boards do, and then in hours. */
fun countdownParts(seconds: Int): Pair<String, String> = when {
    seconds < 60 -> seconds.toString() to "초"
    seconds < 5400 -> ((seconds + 29) / 60).toString() to "분"
    else -> ((seconds + 1800) / 3600).toString() to "시간"
}
