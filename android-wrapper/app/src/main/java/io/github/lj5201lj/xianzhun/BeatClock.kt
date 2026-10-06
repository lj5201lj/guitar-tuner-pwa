package io.github.lj5201lj.xianzhun

import java.util.ArrayDeque
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class Accent { STRONG, SECONDARY, NORMAL }

enum class Meter(val label: String, val pulses: Int, val subdivisionsPerTempoBeat: Int) {
    TWO_FOUR("2/4", 2, 1),
    THREE_FOUR("3/4", 3, 1),
    FOUR_FOUR("4/4", 4, 1),
    SIX_EIGHT("6/8", 6, 3);

    fun accentAt(pulse: Int): Accent = when {
        pulse == 0 -> Accent.STRONG
        this == SIX_EIGHT && pulse == 3 -> Accent.SECONDARY
        else -> Accent.NORMAL
    }
}

enum class ClickSound(val label: String) {
    CLICK("清脆"), WOOD("木鱼"), BEEP("电子")
}

data class PlaybackSettings(
    val bpm: Int = 120,
    val meter: Meter = Meter.FOUR_FOUR,
    val sound: ClickSound = ClickSound.CLICK,
    val volume: Float = 0.75f,
)

data class BeatMark(val frame: Long, val pulse: Int, val accent: Accent, val settings: PlaybackSettings)

/** Schedules beats by sample number, avoiding wall-clock timer drift. */
class BeatClock(private val sampleRate: Int, initialSettings: PlaybackSettings) {
    private var activeSettings = initialSettings
    private var nextExactFrame = 0.0
    private var pulse = 0

    fun nextBeatFrame(): Long = nextExactFrame.roundToLong()

    /** A changed setting takes effect on the next generated click. */
    fun beatAt(frame: Long, requestedSettings: PlaybackSettings): BeatMark? {
        if (frame < nextBeatFrame()) return null
        if (requestedSettings.meter != activeSettings.meter) pulse = 0
        activeSettings = requestedSettings

        val mark = BeatMark(frame, pulse, activeSettings.meter.accentAt(pulse), activeSettings)
        pulse = (pulse + 1) % activeSettings.meter.pulses
        nextExactFrame += sampleRate * 60.0 /
            (activeSettings.bpm * activeSettings.meter.subdivisionsPerTempoBeat)
        return mark
    }
}

/** Uses the median of recent intervals to reject an occasional uneven tap. */
class TapTempo {
    private val times = ArrayDeque<Long>()

    fun tap(nowMs: Long): Int? {
        val previous = times.peekLast()
        if (previous != null) {
            val interval = nowMs - previous
            if (interval < 200) return null
            if (interval > 2_000) times.clear()
        }
        times.addLast(nowMs)
        while (times.size > 6) times.removeFirst()
        if (times.size < 2) return null

        val intervals = times.toList().zipWithNext { a, b -> b - a }.sorted()
        val middle = intervals.size / 2
        val median = if (intervals.size % 2 == 0) {
            (intervals[middle - 1] + intervals[middle]) / 2.0
        } else intervals[middle].toDouble()
        return (60_000.0 / median).roundToInt().coerceIn(30, 300)
    }
}

