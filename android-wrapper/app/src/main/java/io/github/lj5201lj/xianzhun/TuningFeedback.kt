package io.github.lj5201lj.xianzhun

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

data class DetectedNote(val name: String, val octave: Int)

object TuningFeedback {
    private val notes = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun detectedNote(frequency: Double): DetectedNote {
        require(frequency.isFinite() && frequency > 0)
        val midi = (69 + 12 * ln(frequency / 440.0) / ln(2.0)).roundToInt()
        return DetectedNote(notes[(midi % 12 + 12) % 12], kotlin.math.floor(midi / 12.0).toInt() - 1)
    }

    fun outsideMeter(cents: Double) = abs(cents) > 50.0
    fun needsStringCheck(cents: Double) = abs(cents) > 100.0
}

/** A single haptic confirmation per continuous stable in-tune period. Never confirms held readings. */
class InTuneConfirmation {
    private var startedAt: Long? = null
    private var lastFrameAt: Long? = null
    private var targetKey: String? = null
    private var confirmed = false

    fun reset() {
        startedAt = null
        lastFrameAt = null
        targetKey = null
        confirmed = false
    }

    fun update(cents: Double?, key: String, nowMs: Long): Boolean {
        if (cents == null || abs(cents) > 5 || targetKey != key ||
            lastFrameAt?.let { nowMs - it > 250 } == true) {
            reset()
        }
        if (cents == null || abs(cents) > 5) return false
        targetKey = key
        lastFrameAt = nowMs
        val start = startedAt ?: nowMs.also { startedAt = it }
        if (!confirmed && nowMs - start >= 650) {
            confirmed = true
            return true
        }
        return false
    }
}
