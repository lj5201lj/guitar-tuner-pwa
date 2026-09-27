package io.github.lj5201lj.xianzhun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

class YinPitchDetectorTest {
    private val sampleRate = 48_000

    @Test
    fun detectsAllStandardGuitarStringsWithHarmonicsAndNoise() {
        val targets = listOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)
        for (frequency in targets) {
            val signal = guitarLikeSignal(frequency, amplitude = 0.16)
            val estimate = YinPitchDetector.estimate(signal, sampleRate)
            assertNotNull(estimate)
            val cents = 1200.0 * ln(estimate!!.frequency / frequency) / ln(2.0)
            assertTrue("$frequency Hz error was $cents cents", abs(cents) < 3.0)
            assertTrue("$frequency Hz clarity was ${estimate.clarity}", estimate.clarity > 0.7)
        }
    }

    @Test
    fun rejectsSignalBelowNoiseGate() {
        val quiet = guitarLikeSignal(110.0, amplitude = 0.0008)
        assertNull(YinPitchDetector.estimate(quiet, sampleRate))
    }

    @Test
    fun tuningMathUsesConcertPitchAndNearestString() {
        assertEquals(440.0, TuningMath.noteToFrequency("A", 4), 0.0001)
        assertEquals(82.4069, TuningMath.noteToFrequency("E", 2), 0.001)
        assertEquals(6, TuningMath.nearestString(83.0, Tunings.standard.strings).number)
        assertEquals(1, TuningMath.nearestString(331.0, Tunings.standard.strings).number)
    }

    @Test
    fun customRangeIncludesB1AndE5() {
        assertTrue(TuningMath.isCustomPitchValid("B", 1))
        assertTrue(TuningMath.isCustomPitchValid("E", 5))
        assertTrue(!TuningMath.isCustomPitchValid("A♯", 1))
        assertTrue(!TuningMath.isCustomPitchValid("F", 5))
    }

    private fun guitarLikeSignal(frequency: Double, amplitude: Double): FloatArray {
        val random = Random(20260927L + frequency.toLong())
        return FloatArray(8192) { index ->
            val time = index.toDouble() / sampleRate
            val envelope = 0.86 + 0.14 * (1.0 - index.toDouble() / 8192)
            val harmonicSignal =
                sin(2.0 * PI * frequency * time) +
                    0.42 * sin(2.0 * PI * frequency * 2.0 * time + 0.3) +
                    0.22 * sin(2.0 * PI * frequency * 3.0 * time + 0.6)
            val noise = (random.nextDouble() * 2.0 - 1.0) * amplitude * 0.025
            (harmonicSignal * amplitude * envelope + noise).toFloat()
        }
    }
}
