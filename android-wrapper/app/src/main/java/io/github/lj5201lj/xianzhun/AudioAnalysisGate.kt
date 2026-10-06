package io.github.lj5201lj.xianzhun

/** A generation rejects both captured buffers and queued UI readings from before playback. */
class AudioAnalysisGate {
    private data class State(val generation: Long, val paused: Boolean, val blockedUntilMs: Long)

    @Volatile private var state = State(0L, false, 0L)

    @Synchronized fun pause() {
        state = State(state.generation + 1, true, Long.MAX_VALUE)
    }

    @Synchronized fun resume(nowMs: Long, settleMs: Long = 0L) {
        state = State(state.generation + 1, false, nowMs + settleMs.coerceAtLeast(0L))
    }

    fun token(nowMs: Long): Long? {
        val current = state
        return if (current.paused || nowMs < current.blockedUntilMs) null else current.generation
    }

    fun accepts(generation: Long, nowMs: Long): Boolean = token(nowMs) == generation
}
