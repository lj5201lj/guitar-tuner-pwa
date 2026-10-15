package io.github.lj5201lj.xianzhun

import kotlin.math.abs
import kotlin.math.max

data class TrackedPitch(val frequency: Double?, val rms: Double, val clarity: Double, val hasSignal: Boolean)

/** Shared by the recorder and signal tests. Quiet periodic notes are not discarded before YIN. */
class PitchTracker {
    private val history = ArrayDeque<Double>()
    private var smoothed: Double? = null
    private var pendingJump: Double? = null
    private var lastPitchAt = Long.MIN_VALUE
    private var noiseFloor = 0.0003

    fun reset() {
        history.clear()
        smoothed = null
        pendingJump = null
        lastPitchAt = Long.MIN_VALUE
        noiseFloor = 0.0003
    }

    fun analyze(samples: FloatArray, sampleRate: Int, nowMs: Long): TrackedPitch {
        val rms = YinPitchDetector.calculateRms(samples)
        val gate = max(MINIMUM_RMS, noiseFloor * 2.15)
        val estimate = YinPitchDetector.estimate(samples, sampleRate, minimumRms = MINIMUM_RMS)
        // Lower-level notes need stronger periodicity; noise alone must not open the gate.
        val reliable = estimate != null && estimate.clarity >= if (rms < gate) 0.92 else 0.72
        if (!reliable) {
            noiseFloor = (noiseFloor * 0.96 + rms * 0.04).coerceIn(0.00015, 0.012)
            if (lastPitchAt != Long.MIN_VALUE && nowMs - lastPitchAt > 350) {
                history.clear()
                smoothed = null
                pendingJump = null
            }
            return TrackedPitch(null, rms, estimate?.clarity ?: 0.0, rms >= gate)
        }
        val frequency = requireNotNull(estimate).frequency
        val previous = smoothed
        if (previous != null && abs(TuningMath.centsFromTarget(frequency, previous)) > 150) {
            val pending = pendingJump
            // Require two mutually consistent frames, not merely any two large jumps.
            if (pending == null || abs(TuningMath.centsFromTarget(frequency, pending)) > 60) {
                pendingJump = frequency
                return TrackedPitch(null, rms, estimate.clarity, true)
            }
            history.clear()
            smoothed = null
        }
        pendingJump = null
        history.addLast(frequency)
        while (history.size > 5) history.removeFirst()
        val median = history.sorted()[history.size / 2]
        smoothed = smoothed?.let { it * 0.68 + median * 0.32 } ?: median
        lastPitchAt = nowMs
        return TrackedPitch(smoothed, rms, estimate.clarity, true)
    }

    companion object { const val MINIMUM_RMS = 0.00065 }
}
