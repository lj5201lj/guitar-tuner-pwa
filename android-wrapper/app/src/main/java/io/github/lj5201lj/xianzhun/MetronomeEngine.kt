package io.github.lj5201lj.xianzhun

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.Process
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Reused from the standalone metronome; schedules sound in PCM, independently of UI frames. */
class MetronomeEngine(
    private val audioManager: AudioManager,
    private val onBeatHeard: (BeatMark) -> Unit,
    private val onPlaybackError: () -> Unit,
) {
    private class Session {
        val active = AtomicBoolean(true)
        @Volatile var track: AudioTrack? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var requestedSettings = PlaybackSettings()
    @Volatile private var currentSession: Session? = null

    fun start(settings: PlaybackSettings) {
        stop()
        requestedSettings = settings
        val session = Session()
        currentSession = session
        Thread({ playSession(session) }, "metronome-audio").start()
    }

    fun update(settings: PlaybackSettings) {
        requestedSettings = settings
    }

    fun stop() {
        val old = currentSession ?: return
        currentSession = null
        old.active.set(false)
        try {
            old.track?.pause()
            old.track?.flush()
        } catch (_: IllegalStateException) {
            // The audio thread may already be closing this track.
        }
    }

    private fun playSession(session: Session) {
        var failed = false
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: 48_000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = max(minimum.coerceAtLeast(0), 4_096)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) error("AudioTrack initialization failed")
            session.track = track
            if (!session.active.get()) return

            val clock = BeatClock(sampleRate, requestedSettings)
            val pcm = ShortArray(256)
            val visualEvents = ArrayDeque<BeatMark>()
            var frame = 0L
            var hitStart = -1L
            var hitSettings = requestedSettings
            var hitAccent = Accent.NORMAL
            var noise = 0x12345678

            track.play()
            while (session.active.get()) {
                for (index in pcm.indices) {
                    val mark = clock.beatAt(frame, requestedSettings)
                    if (mark != null) {
                        hitStart = frame
                        hitSettings = mark.settings
                        hitAccent = mark.accent
                        visualEvents.addLast(mark)
                    }

                    noise = noise xor (noise shl 13)
                    noise = noise xor (noise ushr 17)
                    noise = noise xor (noise shl 5)
                    val elapsed = frame - hitStart
                    val value = if (elapsed >= 0) {
                        clickSample(elapsed, sampleRate, hitSettings.sound, hitAccent, noise)
                    } else 0.0
                    pcm[index] = (value * hitSettings.volume.coerceIn(0f, 1f) * Short.MAX_VALUE)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    frame++
                }

                var offset = 0
                while (offset < pcm.size && session.active.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) error("AudioTrack write failed: $written")
                    offset += written
                }
                val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                while (true) {
                    val event = visualEvents.peekFirst() ?: break
                    if (event.frame >= played) break
                    visualEvents.removeFirst()
                    mainHandler.post {
                        if (currentSession === session && session.active.get()) onBeatHeard(event)
                    }
                }
            }
        } catch (_: Exception) {
            failed = session.active.get()
        } finally {
            session.active.set(false)
            session.track = null
            try { track?.stop() } catch (_: IllegalStateException) { }
            track?.release()
            if (failed) {
                mainHandler.post {
                    if (currentSession === session) {
                        currentSession = null
                        onPlaybackError()
                    }
                }
            }
        }
    }

    private fun clickSample(
        elapsedFrames: Long,
        sampleRate: Int,
        sound: ClickSound,
        accent: Accent,
        noise: Int,
    ): Double {
        val time = elapsedFrames.toDouble() / sampleRate
        val duration = when (sound) {
            ClickSound.CLICK -> 0.025
            ClickSound.WOOD -> 0.055
            ClickSound.BEEP -> 0.050
        }
        if (time >= duration) return 0.0
        val strength = when (accent) {
            Accent.STRONG -> 0.95
            Accent.SECONDARY -> 0.75
            Accent.NORMAL -> 0.52
        }
        val pitch = if (accent == Accent.STRONG) 1.25 else 1.0
        val raw = when (sound) {
            ClickSound.CLICK -> {
                val white = (noise.toDouble() / Int.MAX_VALUE).coerceIn(-1.0, 1.0)
                (0.65 * white + 0.35 * sin(2 * PI * 1_500 * pitch * time)) * exp(-150 * time)
            }
            ClickSound.WOOD -> {
                (0.72 * sin(2 * PI * 650 * pitch * time) +
                    0.28 * sin(2 * PI * 1_050 * pitch * time)) * exp(-55 * time)
            }
            ClickSound.BEEP -> sin(2 * PI * 880 * pitch * time) * exp(-38 * time)
        }
        return raw * strength
    }
}

