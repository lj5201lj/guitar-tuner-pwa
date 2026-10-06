package io.github.lj5201lj.xianzhun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToLong

class BeatClockTest {
    @Test fun fractionalIntervalsDoNotAccumulateRoundingDrift() {
        val settings = PlaybackSettings(bpm = 137)
        val clock = BeatClock(44_100, settings)
        repeat(10_000) { index ->
            val expected = (index * 44_100 * 60.0 / 137).roundToLong()
            assertEquals(expected, clock.nextBeatFrame())
            clock.beatAt(clock.nextBeatFrame(), settings)
        }
    }

    @Test fun quarterNoteIntervalsAreSampleAccurate() {
        for ((bpm, expected) in listOf(30 to 96_000L, 120 to 24_000L, 300 to 9_600L)) {
            val settings = PlaybackSettings(bpm = bpm)
            val clock = BeatClock(48_000, settings)
            assertEquals(Accent.STRONG, clock.beatAt(0, settings)?.accent)
            assertEquals(expected, clock.nextBeatFrame())
            assertNull(clock.beatAt(expected - 1, settings))
            assertEquals(1, clock.beatAt(expected, settings)?.pulse)
        }
    }

    @Test fun sixEightGroupsThreePlusThree() {
        val settings = PlaybackSettings(bpm = 120, meter = Meter.SIX_EIGHT)
        val clock = BeatClock(48_000, settings)
        val accents = (0..6).map {
            val frame = clock.nextBeatFrame()
            val mark = clock.beatAt(frame, settings)!!
            assertEquals(it * 8_000L, frame)
            mark.accent
        }
        assertEquals(
            listOf(Accent.STRONG, Accent.NORMAL, Accent.NORMAL, Accent.SECONDARY,
                Accent.NORMAL, Accent.NORMAL, Accent.STRONG), accents
        )
    }

    @Test fun simpleMetersRepeatTheirFirstBeatAccent() {
        for (meter in listOf(Meter.TWO_FOUR, Meter.THREE_FOUR, Meter.FOUR_FOUR)) {
            val settings = PlaybackSettings(bpm = 120, meter = meter)
            val clock = BeatClock(48_000, settings)
            repeat(meter.pulses * 2 + 1) { click ->
                val mark = clock.beatAt(clock.nextBeatFrame(), settings)!!
                val expectedPulse = click % meter.pulses
                assertEquals(expectedPulse, mark.pulse)
                assertEquals(
                    if (expectedPulse == 0) Accent.STRONG else Accent.NORMAL,
                    mark.accent,
                )
                assertEquals(click * 24_000L, mark.frame)
            }
        }
    }

    @Test fun changesApplyOnNextClickAndMeterStartsNewBar() {
        val original = PlaybackSettings(bpm = 120, meter = Meter.FOUR_FOUR)
        val changed = PlaybackSettings(bpm = 300, meter = Meter.THREE_FOUR)
        val clock = BeatClock(48_000, original)
        clock.beatAt(0, original)
        assertNull(clock.beatAt(9_600, changed))
        val next = clock.beatAt(24_000, changed)!!
        assertEquals(0, next.pulse)
        assertEquals(Accent.STRONG, next.accent)
        assertEquals(33_600L, clock.nextBeatFrame())
    }

    @Test fun tapTempoRejectsBounceAndResetsAfterPause() {
        val taps = TapTempo()
        assertNull(taps.tap(0))
        assertNull(taps.tap(100))
        assertEquals(120, taps.tap(500))
        assertEquals(120, taps.tap(1_000))
        assertNull(taps.tap(4_000))
        assertEquals(60, taps.tap(5_000))
    }
}

