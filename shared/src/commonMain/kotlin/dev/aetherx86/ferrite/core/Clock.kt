package dev.aetherx86.ferrite.core

import kotlin.time.Clock

/** Everything on a Korean timetable runs on this offset, which has no daylight saving. */
private const val KST_OFFSET_MS = 9 * 3_600_000L
private const val DAY_MS = 86_400_000L

private val WEEKDAYS = arrayOf("월", "화", "수", "목", "금", "토", "일")

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A wall clock reading in Korea. */
data class KstTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    /** Monday is 0. */
    val weekday: Int,
) {
    val weekdayName: String get() = WEEKDAYS[weekday]
    val minuteOfDay: Int get() = hour * 60 + minute

    /** Trains after midnight belong to the service day that started before it. */
    val serviceMinute: Int get() = if (hour < 3) minuteOfDay + 1440 else minuteOfDay
    val serviceWeekday: Int get() = if (hour < 3) (weekday + 6) % 7 else weekday
}

fun kstOf(epochMillis: Long): KstTime {
    val local = epochMillis + KST_OFFSET_MS
    val days = floorDiv(local, DAY_MS)
    val rem = local - days * DAY_MS
    val (y, m, d) = civilFromDays(days)
    val secondOfDay = (rem / 1000).toInt()
    return KstTime(
        year = y,
        month = m,
        day = d,
        hour = secondOfDay / 3600,
        minute = secondOfDay / 60 % 60,
        second = secondOfDay % 60,
        weekday = (((days + 3) % 7 + 7) % 7).toInt(),
    )
}

/** Epoch milliseconds for a wall clock reading in Korea. */
fun epochOfKst(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    daysFromCivil(year, month, day) * DAY_MS + (hour * 3600L + minute * 60L + second) * 1000L - KST_OFFSET_MS

private fun floorDiv(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0 && q * b != a) q--
    return q
}

// Date arithmetic on the proleptic Gregorian calendar, by era.
private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return Triple(y, m, d)
}

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = (if (m <= 2) y0 - 1 else y0).toLong()
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

fun two(n: Int): String = if (n < 10) "0$n" else n.toString()
