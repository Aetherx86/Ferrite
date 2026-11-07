package dev.aetherx86.ferrite.live

import dev.aetherx86.ferrite.data.Line

/**
 * A scripted run of trains, labelled on screen, for seeing the whole board without waiting on a platform.
 *
 * Long waits between trains are compressed, so a full cycle takes about a minute.
 */
class DemoFeed(private val line: Line, private val destination: String, startMs: Long) {
    private val origin = startMs

    private val lineDigit = line.short.toIntOrNull() ?: 7

    /** Each cycle shows a train arrive, then a few seconds of waiting for the next one. */
    fun at(nowMs: Long): ArrivalFeed {
        val elapsed = nowMs - origin
        val cycle = elapsed / CYCLE_MS
        val inCycle = elapsed % CYCLE_MS
        val arrival = nowMs - inCycle + FIRST_ARRIVAL_MS
        // After the train leaves, the next one reads as a few minutes away, and that wait is what gets skipped.
        val current = Approach(number(cycle), destination, arrival)
        val next = Approach(number(cycle + 1), destination, arrival + HEADWAY_MS)
        return ArrivalFeed(FeedSource.DEMO, listOf(current, next), nowMs)
    }

    private fun number(cycle: Long): String {
        val seed = ((cycle * 2654435761L) ushr 7).toInt() and 0x7FFFFFFF
        return "$lineDigit${(seed % 900 + 100)}"
    }

    companion object {
        const val FIRST_ARRIVAL_MS = 16_000L
        const val HEADWAY_MS = 240_000L
        val CYCLE_MS: Long = FIRST_ARRIVAL_MS + BoardEngine.DWELL_MS + 8_000L
    }
}
