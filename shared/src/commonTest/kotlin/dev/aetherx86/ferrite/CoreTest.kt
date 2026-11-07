package dev.aetherx86.ferrite

import dev.aetherx86.ferrite.audio.BoardSound
import dev.aetherx86.ferrite.audio.SoundSynth
import dev.aetherx86.ferrite.core.LatLng
import dev.aetherx86.ferrite.core.bearingDegrees
import dev.aetherx86.ferrite.core.distanceMeters
import dev.aetherx86.ferrite.core.epochOfKst
import dev.aetherx86.ferrite.core.initialOf
import dev.aetherx86.ferrite.core.kstOf
import dev.aetherx86.ferrite.core.searchScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun searchFindsNamesByInitialSounds() {
        assertEquals('ㅅ', initialOf('삼'))
        assertEquals(0, searchScore("삼각지", "삼각지"))
        assertNotNull(searchScore("삼각지", "ㅅㄱㅈ"))
        assertNotNull(searchScore("삼각지", "삼ㄱ"))
        assertNotNull(searchScore("구로디지털단지", "디지털"))
        assertNull(searchScore("삼각지", "ㅅㅅ"))
        assertNull(searchScore("삼각지", ""))
    }

    @Test
    fun exactBeatsPrefixBeatsMiddle() {
        val exact = searchScore("강남", "강남")!!
        val prefix = searchScore("강남구청", "강남")!!
        val middle = searchScore("신강남", "강남")!!
        assertTrue(exact < prefix && prefix < middle)
    }

    @Test
    fun koreanTimeRoundTripsAndCrossesMidnight() {
        val at = epochOfKst(2026, 10, 31, 17, 58, 55)
        val time = kstOf(at)
        assertEquals(listOf(2026, 10, 31, 17, 58, 55), listOf(time.year, time.month, time.day, time.hour, time.minute, time.second))
        assertEquals("토", time.weekdayName)
        val late = kstOf(epochOfKst(2026, 11, 2, 0, 30, 0))
        // Half past midnight on Monday still belongs to Sunday's service.
        assertEquals(0, late.weekday)
        assertEquals(6, late.serviceWeekday)
        assertEquals(1470, late.serviceMinute)
    }

    @Test
    fun leapDaysAndYearEndsAreRight() {
        val leap = kstOf(epochOfKst(2028, 2, 29, 12, 0, 0))
        assertEquals(2 to 29, leap.month to leap.day)
        val next = kstOf(epochOfKst(2026, 12, 31, 23, 59, 59) + 1000)
        assertEquals(listOf(2027, 1, 1), listOf(next.year, next.month, next.day))
    }

    @Test
    fun distancesAndBearingsMatchKnownValues() {
        val seoul = LatLng(37.5547, 126.9707)
        val busan = LatLng(35.1152, 129.0422)
        val km = distanceMeters(seoul, busan) / 1000
        assertTrue(km in 315.0..335.0, "$km")
        assertTrue(bearingDegrees(seoul, LatLng(38.0, 126.9707)) < 1.0)
        assertTrue(kotlin.math.abs(bearingDegrees(seoul, LatLng(37.5547, 127.5)) - 90) < 1.0)
    }

    @Test
    fun soundsAreAudibleAndNeverClip() {
        for (sound in BoardSound.entries) {
            val samples = SoundSynth.render(sound)
            assertTrue(samples.size > SoundSynth.SAMPLE_RATE, sound.name)
            val peak = samples.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(peak in 3000..31000, "$sound peaks at $peak")
        }
    }

    @Test
    fun wavHeaderDescribesTheSamples() {
        val samples = ShortArray(100) { it.toShort() }
        val wav = SoundSynth.toWav(samples)
        assertEquals(244, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
    }
}
