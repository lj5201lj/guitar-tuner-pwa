package io.github.lj5201lj.xianzhun

enum class MicrophoneResult { NO_DATA, SYSTEM_MUTED, CLIPPING, NO_SIGNAL, TOO_QUIET, SOUND_ONLY, PITCH_OK }

/** Keeps scalar counters only: no PCM, files, identifiers, or uploads. */
class MicrophoneCheck {
    var frames = 0
        private set
    private var mutedFrames = 0
    private var clippedFrames = 0
    private var pitchFrames = 0
    private var signalFrames = 0
    private var nonzeroFrames = 0
    var peakRms = 0.0
        private set

    fun observe(rawRms: Double, clipped: Boolean, systemMuted: Boolean, frequency: Double?) {
        frames++
        if (systemMuted) mutedFrames++
        if (clipped) clippedFrames++
        if (frequency != null) pitchFrames++
        if (rawRms >= PitchTracker.MINIMUM_RMS) signalFrames++
        if (rawRms > 0.00001) nonzeroFrames++
        peakRms = maxOf(peakRms, rawRms)
    }

    fun result(): MicrophoneResult = when {
        frames == 0 -> MicrophoneResult.NO_DATA
        mutedFrames * 2 >= frames -> MicrophoneResult.SYSTEM_MUTED
        clippedFrames >= 3 -> MicrophoneResult.CLIPPING
        pitchFrames >= 2 -> MicrophoneResult.PITCH_OK
        nonzeroFrames == 0 -> MicrophoneResult.NO_SIGNAL
        signalFrames >= 2 -> MicrophoneResult.SOUND_ONLY
        else -> MicrophoneResult.TOO_QUIET
    }
}
