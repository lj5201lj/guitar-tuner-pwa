package io.github.lj5201lj.xianzhun

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PitchEstimate(
    val frequency: Double,
    val rms: Double,
    val clarity: Double,
)

/**
 * YIN pitch detection using the cumulative mean normalized difference curve.
 * It is intentionally independent from Android so the DSP can be unit tested.
 */
object YinPitchDetector {
    fun calculateRms(buffer: FloatArray): Double {
        if (buffer.isEmpty()) return 0.0
        var mean = 0.0
        for (sample in buffer) mean += sample
        mean /= buffer.size

        var sum = 0.0
        for (sample in buffer) {
            val centered = sample - mean
            sum += centered * centered
        }
        return sqrt(sum / buffer.size)
    }

    fun estimate(
        buffer: FloatArray,
        sampleRate: Int,
        minFrequency: Double = 55.0,
        maxFrequency: Double = 700.0,
        threshold: Double = 0.18,
        minimumRms: Double = 0.0022,
    ): PitchEstimate? {
        if (buffer.size < 1024 || sampleRate <= 0) return null

        val rms = calculateRms(buffer)
        if (rms < minimumRms) return null

        val minTau = max(2, (sampleRate / maxFrequency).toInt())
        val maxTau = min((sampleRate / minFrequency).toInt(), buffer.size / 2)
        val comparisonLength = min(buffer.size - maxTau, 4096)
        if (minTau >= maxTau || comparisonLength < maxTau) return null

        var mean = 0.0
        for (sample in buffer) mean += sample
        mean /= buffer.size

        val yin = DoubleArray(maxTau + 1)
        for (tau in 1..maxTau) {
            var difference = 0.0
            var index = 0
            while (index < comparisonLength) {
                val delta = (buffer[index] - mean) - (buffer[index + tau] - mean)
                difference += delta * delta
                index += 1
            }
            yin[tau] = difference
        }

        yin[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..maxTau) {
            runningSum += yin[tau]
            yin[tau] = if (runningSum == 0.0) 1.0 else yin[tau] * tau / runningSum
        }

        var candidate = -1
        var tau = minTau
        while (tau <= maxTau) {
            if (yin[tau] < threshold) {
                while (tau + 1 <= maxTau && yin[tau + 1] < yin[tau]) tau += 1
                candidate = tau
                break
            }
            tau += 1
        }

        if (candidate < 0) {
            var bestValue = Double.POSITIVE_INFINITY
            for (index in minTau..maxTau) {
                if (yin[index] < bestValue) {
                    bestValue = yin[index]
                    candidate = index
                }
            }
            if (candidate < 0 || bestValue > 0.32) return null
        }

        val left = if (candidate > 1) yin[candidate - 1] else yin[candidate]
        val center = yin[candidate]
        val right = if (candidate < maxTau) yin[candidate + 1] else yin[candidate]
        val denominator = 2.0 * (2.0 * center - right - left)
        val adjustment = if (kotlin.math.abs(denominator) > 1e-10) {
            ((right - left) / denominator).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val frequency = sampleRate / (candidate + adjustment)
        if (!frequency.isFinite() || frequency !in minFrequency..maxFrequency) return null

        return PitchEstimate(
            frequency = frequency,
            rms = rms,
            clarity = (1.0 - center).coerceIn(0.0, 1.0),
        )
    }
}
