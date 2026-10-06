package io.github.lj5201lj.xianzhun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalysisGateTest {
    @Test fun playbackAndAcousticTailRejectCapturedAndQueuedReadings() {
        val gate = AudioAnalysisGate()
        val oldReading = gate.token(0)!!
        gate.pause()
        assertNull(gate.token(500))
        assertFalse(gate.accepts(oldReading, 500))
        gate.resume(nowMs = 1_000, settleMs = 400)
        assertNull(gate.token(1_399))
        assertFalse(gate.accepts(oldReading, 1_500))
        val freshReading = gate.token(1_400)!!
        assertTrue(gate.accepts(freshReading, 1_500))
    }

    @Test fun rapidReplayInvalidatesPreviousRecoveryAndPendingAnalysis() {
        val gate = AudioAnalysisGate()
        gate.pause()
        gate.resume(1_000, 400)
        gate.pause()
        assertNull(gate.token(1_500))
        gate.resume(2_000, 400)
        val capturedBeforeSwitch = gate.token(2_400)!!
        gate.pause()
        gate.resume(2_400)
        assertFalse(gate.accepts(capturedBeforeSwitch, 2_400))
        assertNotNull(gate.token(2_400))
    }
}
