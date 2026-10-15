package io.github.lj5201lj.xianzhun

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class MarketReadinessTest {
    private fun signal(f: Double, rate: Int = 48_000, amplitude: Double = 0.06, decay: Boolean = false): FloatArray {
        val noise = Random(27)
        return FloatArray(if (rate >= 32_000) 8192 else 4096) { i ->
            val t = i.toDouble() / rate
            val env = if (decay) exp(-t * 8) else 1.0
            (amplitude * env * (sin(2 * PI * f * t) + 0.55 * sin(4 * PI * f * t + 0.2) +
                0.28 * sin(6 * PI * f * t + 0.4)) + amplitude * 0.015 * (noise.nextDouble() * 2 - 1)).toFloat()
        }
    }

    @Test fun detectsQuietDecayingSixStringsAcrossCaptureRates() {
        for (rate in listOf(48_000, 44_100, 22_050, 16_000)) {
            for (string in Tunings.standard.strings) {
                val tracker = PitchTracker()
                val result = tracker.analyze(signal(string.frequency, rate, 0.002, true), rate, 0)
                assertNotNull("Quiet ${string.note}${string.octave} at $rate", result.frequency)
                assertTrue(abs(TuningMath.centsFromTarget(result.frequency!!, string.frequency)) < 4)
            }
        }
    }

    @Test fun noiseAndSilenceDoNotBecomeNotes() {
        val random = Random(123)
        val tracker = PitchTracker()
        for (frame in 0..8) {
            assertNull(tracker.analyze(FloatArray(8192) { ((random.nextDouble() * 2 - 1) * 0.02).toFloat() }, 48_000, frame * 80L).frequency)
        }
        assertNull(tracker.analyze(FloatArray(8192), 48_000, 900).frequency)
        assertNull(tracker.analyze(signal(110.0, amplitude = 0.0001), 48_000, 980).frequency)
    }

    @Test fun dominantSecondHarmonicDoesNotHideTheFundamental() {
        for (frequency in listOf(82.4069, 110.0, 146.8324, 196.0, 246.9417, 329.6276)) {
            val samples = FloatArray(8192) { index ->
                val t = index / 48_000.0
                (0.015 * sin(2 * PI * frequency * t) + 0.09 * sin(4 * PI * frequency * t) +
                    0.022 * sin(6 * PI * frequency * t)).toFloat()
            }
            val estimate = YinPitchDetector.estimate(samples, 48_000)
            assertNotNull(estimate)
            assertTrue("$frequency became ${estimate!!.frequency}", abs(TuningMath.centsFromTarget(estimate.frequency, frequency)) < 4)
        }
    }

    @Test fun clearsHistoryAfterSilenceAndRequiresConsistentStringChanges() {
        val tracker = PitchTracker()
        assertNotNull(tracker.analyze(signal(82.4069), 48_000, 0).frequency)
        assertNull(tracker.analyze(signal(196.0), 48_000, 80).frequency)
        assertNull(tracker.analyze(signal(329.6276), 48_000, 160).frequency)
        val switched = tracker.analyze(signal(329.6276), 48_000, 240)
        assertEquals(329.6276, switched.frequency!!, 0.6)
        tracker.analyze(FloatArray(8192), 48_000, 700)
        assertEquals(110.0, tracker.analyze(signal(110.0), 48_000, 780).frequency!!, 0.2)
        tracker.reset()
        assertEquals(196.0, tracker.analyze(signal(196.0), 48_000, 860).frequency!!, 0.4)
    }

    @Test fun detectsDetunedNotesAndCustomBoundaries() {
        for (frequency in listOf(TuningMath.noteToFrequency("B", 1), TuningMath.noteToFrequency("E", 5), 110 * 2.0.pow(38.0 / 1200), 110 * 2.0.pow(-42.0 / 1200))) {
            val value = PitchTracker().analyze(signal(frequency), 48_000, 0).frequency
            assertNotNull(value)
            assertTrue(abs(TuningMath.centsFromTarget(value!!, frequency)) < 3)
        }
    }

    @Test fun harmonicGuardDoesNotTurnTrueHighNotesIntoSubharmonics() {
        val noise = Random(156)
        for (frequency in listOf(55.2, 61.7354, 82.4069, 110.0, 220.0, 329.6276, 440.0, 659.2551)) {
            val samples = FloatArray(8192) { i ->
                (0.08 * sin(2 * PI * frequency * i / 48_000.0) + 0.015 * (noise.nextDouble() * 2 - 1)).toFloat()
            }
            val estimate = YinPitchDetector.estimate(samples, 48_000)
            assertNotNull("Noisy pure note $frequency", estimate)
            assertTrue("True $frequency became ${estimate!!.frequency}", abs(TuningMath.centsFromTarget(estimate.frequency, frequency)) < 4)
        }
    }

    @Test fun detectedNoteIsNotForcedToTargetString() {
        assertEquals(DetectedNote("A", 4), TuningFeedback.detectedNote(440.0))
        assertEquals(DetectedNote("F", 2), TuningFeedback.detectedNote(TuningMath.noteToFrequency("F", 2)))
        assertEquals(DetectedNote("C♯", 3), TuningFeedback.detectedNote(TuningMath.noteToFrequency("D♭", 3)))
        assertEquals(DetectedNote("B", 1), TuningFeedback.detectedNote(TuningMath.noteToFrequency("B", 1)))
        assertFalse(TuningFeedback.outsideMeter(50.0))
        assertTrue(TuningFeedback.outsideMeter(-50.1))
        assertFalse(TuningFeedback.needsStringCheck(100.0))
        assertTrue(TuningFeedback.needsStringCheck(-101.0))
    }

    @Test fun confirmsOnceOnlyAfterContinuousStableFrames() {
        val confirmation = InTuneConfirmation()
        for (now in 0L..600L step 100) assertFalse(confirmation.update(2.0, "E2", now))
        assertTrue(confirmation.update(2.0, "E2", 700))
        assertFalse(confirmation.update(2.0, "E2", 800))
        assertFalse(confirmation.update(null, "E2", 900))
        assertFalse(confirmation.update(2.0, "E2", 1000))
        assertFalse(confirmation.update(2.0, "A2", 1100))
        assertFalse(confirmation.update(2.0, "A2", 2000))
        assertFalse(confirmation.update(8.0, "A2", 2100))
    }

    @Test fun microphoneSelfTestDistinguishesInputFailures() {
        assertEquals(MicrophoneResult.NO_DATA, MicrophoneCheck().result())
        fun checked(rms: Double, clipped: Boolean = false, muted: Boolean = false, pitch: Double? = null): MicrophoneResult {
            val check = MicrophoneCheck()
            repeat(8) { check.observe(rms, clipped, muted, pitch) }
            return check.result()
        }
        assertEquals(MicrophoneResult.NO_SIGNAL, checked(0.0))
        assertEquals(MicrophoneResult.TOO_QUIET, checked(0.0001))
        assertEquals(MicrophoneResult.SOUND_ONLY, checked(0.01))
        assertEquals(MicrophoneResult.PITCH_OK, checked(0.01, pitch = 110.0))
        assertEquals(MicrophoneResult.CLIPPING, checked(0.8, clipped = true))
        assertEquals(MicrophoneResult.SYSTEM_MUTED, checked(0.0, muted = true))
    }
}
