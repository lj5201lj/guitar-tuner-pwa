package io.github.lj5201lj.xianzhun

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

class ReferenceTonePlayer(private val context: Context) {
    private data class Sample(val frequency: Double, val resource: Int)

    private val samples = listOf(
        Sample(TuningMath.noteToFrequency("D", 2), R.raw.guitar_d2),
        Sample(TuningMath.noteToFrequency("E", 2), R.raw.guitar_e2),
        Sample(TuningMath.noteToFrequency("G", 2), R.raw.guitar_g2),
        Sample(TuningMath.noteToFrequency("A", 2), R.raw.guitar_a2),
        Sample(TuningMath.noteToFrequency("C", 3), R.raw.guitar_c3),
        Sample(TuningMath.noteToFrequency("D", 3), R.raw.guitar_d3),
        Sample(TuningMath.noteToFrequency("F", 3), R.raw.guitar_f3),
        Sample(TuningMath.noteToFrequency("G", 3), R.raw.guitar_g3),
        Sample(TuningMath.noteToFrequency("A", 3), R.raw.guitar_a3),
        Sample(TuningMath.noteToFrequency("B", 3), R.raw.guitar_b3),
        Sample(TuningMath.noteToFrequency("D", 4), R.raw.guitar_d4),
        Sample(TuningMath.noteToFrequency("E", 4), R.raw.guitar_e4),
        Sample(TuningMath.noteToFrequency("A", 4), R.raw.guitar_a4),
        Sample(TuningMath.noteToFrequency("D", 5), R.raw.guitar_d5),
    )

    private var player: MediaPlayer? = null
    private var completion: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun play(string: GuitarString, onFinished: () -> Unit): Result<Unit> = runCatching {
        stop(notify = false)
        completion = onFinished
        val sample = samples.minBy { abs(1200.0 * kotlin.math.log2(string.frequency / it.frequency)) }
        val next = requireNotNull(MediaPlayer.create(context, sample.resource)) {
            "无法载入吉他参考音。"
        }
        player = next
        next.setVolume(0.92f, 0.92f)
        val pitchRatio = (string.frequency / sample.frequency).toFloat().coerceIn(0.5f, 2.0f)
        next.setOnCompletionListener {
            if (player === next) stop(notify = true)
        }
        next.setOnErrorListener { _, _, _ ->
            if (player === next) stop(notify = true)
            true
        }
        // MediaPlayer may start while changing PlaybackParams; start the one-second clock after it.
        next.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitchRatio)
        next.start()
        // A short fade within the final 60 ms avoids a hard cut at the one-second limit.
        for (step in 1..6) {
            handler.postDelayed({
                if (player === next) {
                    val volume = 0.92f * (1f - step / 6f)
                    runCatching { next.setVolume(volume, volume) }
                }
            }, PLAY_DURATION_MS - 60L + step * 10L)
        }
        handler.postDelayed({
            if (player === next) stop(notify = true)
        }, PLAY_DURATION_MS)
        Unit
    }.onFailure {
        stop(notify = false)
    }

    fun stop(notify: Boolean = true) {
        val callback = completion
        completion = null
        handler.removeCallbacksAndMessages(null)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (notify) callback?.invoke()
    }

    fun release() = stop(notify = false)

    companion object {
        const val PLAY_DURATION_MS = 1_000L
    }
}
