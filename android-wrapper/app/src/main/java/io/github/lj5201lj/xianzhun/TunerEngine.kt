package io.github.lj5201lj.xianzhun

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioUpdate(
    val frequency: Double?,
    val rms: Double,
    val clarity: Double,
    val hasSignal: Boolean,
    val level: Double,
    val analysisGeneration: Long,
    val rawRms: Double = rms,
    val clipped: Boolean = false,
    val systemMuted: Boolean = false,
)

class TunerEngine(
    private val context: Context,
    private val onUpdate: (AudioUpdate) -> Unit,
    private val onError: (String) -> Unit,
) {
    private class Session(val record: AudioRecord) {
        val active = AtomicBoolean(true)
        var worker: Thread? = null
    }

    private val analysisGate = AudioAnalysisGate()
    @Volatile private var currentSession: Session? = null

    @SuppressLint("MissingPermission")
    fun start(): Result<String> {
        stop()
        return runCatching {
            val (record, sampleRate, sourceName) = createStartedAudioRecord()
            val session = Session(record)
            currentSession = session
            analysisGate.resume(SystemClock.elapsedRealtime())
            session.worker = thread(name = "xianzhun-pitch", start = true) {
                captureLoop(session, sampleRate)
            }
            "$sourceName · ${sampleRate / 1000.0} kHz"
        }.onFailure {
            stop()
        }
    }

    fun setPaused(value: Boolean, settleMs: Long = 0L) {
        if (value) analysisGate.pause()
        else analysisGate.resume(SystemClock.elapsedRealtime(), settleMs)
    }

    fun accepts(update: AudioUpdate): Boolean = currentSession?.active?.get() == true &&
        analysisGate.accepts(update.analysisGeneration, SystemClock.elapsedRealtime())

    fun stop() {
        analysisGate.pause()
        val session = currentSession ?: return
        currentSession = null
        session.active.set(false)
        runCatching { session.record.stop() }
        session.worker?.interrupt()
        // The capture thread releases its own recorder; a previous session cannot stop a new one.
    }

    @SuppressLint("MissingPermission")
    private fun createStartedAudioRecord(): RecorderConfiguration {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportsUnprocessed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to context.getString(R.string.source_unprocessed))
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to context.getString(R.string.source_voice_recognition))
            add(MediaRecorder.AudioSource.MIC to context.getString(R.string.source_standard))
        }
        val rates = intArrayOf(48_000, 44_100, 22_050, 16_000)

        for ((source, sourceName) in sources) {
            for (rate in rates) {
                val minimumBytes = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimumBytes <= 0) continue
                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(max(minimumBytes * 2, rate))
                        .build()
                }.getOrNull() ?: continue

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    val started = runCatching {
                        record.startRecording()
                        record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    }.getOrDefault(false)
                    if (started) return RecorderConfiguration(record, rate, sourceName)
                }
                runCatching { record.stop() }
                record.release()
            }
        }
        error(context.getString(R.string.recording_unavailable))
    }

    private fun captureLoop(session: Session, sampleRate: Int) {
        val record = session.record
        val windowSize = if (sampleRate >= 32_000) 8192 else 4096
        val readSize = 1024
        val analysisHop = max(1024, sampleRate / 15)
        val shorts = ShortArray(readSize)
        val ring = FloatArray(windowSize)
        val snapshot = FloatArray(windowSize)
        var ringIndex = 0
        var ringCount = 0
        var sinceAnalysis = 0
        var highPreviousInput = 0.0
        var highPreviousOutput = 0.0
        var lowPreviousOutput = 0.0
        val highRc = 1.0 / (2.0 * PI * 45.0)
        val lowRc = 1.0 / (2.0 * PI * 1_600.0)
        val timeStep = 1.0 / sampleRate
        val highAlpha = highRc / (highRc + timeStep)
        val lowAlpha = timeStep / (lowRc + timeStep)
        val tracker = PitchTracker()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var rawEnergy = 0.0
        var rawCount = 0
        var clippedCount = 0
        var previousGeneration: Long? = null

        fun resetAnalysis() {
            ring.fill(0f)
            ringIndex = 0
            ringCount = 0
            sinceAnalysis = 0
            tracker.reset()
            rawEnergy = 0.0
            rawCount = 0
            clippedCount = 0
            highPreviousInput = 0.0
            highPreviousOutput = 0.0
            lowPreviousOutput = 0.0
        }

        try {
            while (session.active.get()) {
                val beforeRead = analysisGate.token(SystemClock.elapsedRealtime())
                val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error(context.getString(R.string.microphone_read_failed, count))
                if (count == 0) continue

                val generation = analysisGate.token(SystemClock.elapsedRealtime())
                // Keep draining PCM while blocked, including buffers spanning the resume boundary.
                if (generation == null || generation != beforeRead) {
                    if (previousGeneration != null) resetAnalysis()
                    previousGeneration = null
                    continue
                }
                if (generation != previousGeneration) {
                    resetAnalysis()
                    previousGeneration = generation
                }

                for (index in 0 until count) {
                    val input = shorts[index] / 32768.0
                    rawEnergy += input * input
                    rawCount++
                    if (abs(input) >= 0.98) clippedCount++
                    val high = highAlpha * (highPreviousOutput + input - highPreviousInput)
                    highPreviousInput = input
                    highPreviousOutput = high
                    val low = lowPreviousOutput + lowAlpha * (high - lowPreviousOutput)
                    lowPreviousOutput = low
                    ring[ringIndex] = low.toFloat()
                    ringIndex = (ringIndex + 1) % windowSize
                    if (ringCount < windowSize) ringCount += 1
                }
                sinceAnalysis += count
                if (ringCount < windowSize || sinceAnalysis < analysisHop) continue
                sinceAnalysis = 0

                for (index in 0 until windowSize) {
                    snapshot[index] = ring[(ringIndex + index) % windowSize]
                }

                val rawRms = sqrt(rawEnergy / rawCount.coerceAtLeast(1))
                val clipped = clippedCount.toDouble() / rawCount.coerceAtLeast(1) >= 0.005
                rawEnergy = 0.0
                rawCount = 0
                clippedCount = 0
                val systemMuted = audioManager.isMicrophoneMute ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false))
                val pitch = if (systemMuted || clipped) {
                    tracker.reset()
                    TrackedPitch(null, 0.0, 0.0, rawRms >= PitchTracker.MINIMUM_RMS)
                } else tracker.analyze(snapshot, sampleRate, SystemClock.elapsedRealtime())
                // A logarithmic meter remains useful even for quiet but detectable strings.
                val level = ((20 * kotlin.math.log10(rawRms.coerceAtLeast(0.00001)) + 80) / 65).coerceIn(0.0, 1.0)
                onUpdate(
                    AudioUpdate(
                        frequency = pitch.frequency,
                        rms = pitch.rms,
                        clarity = pitch.clarity,
                        hasSignal = pitch.hasSignal,
                        level = level,
                        analysisGeneration = generation,
                        rawRms = rawRms,
                        clipped = clipped,
                        systemMuted = systemMuted,
                    ),
                )
            }
        } catch (error: Throwable) {
            if (session.active.get() && currentSession === session) {
                onError(error.message ?: context.getString(R.string.microphone_read_error))
            }
        } finally {
            session.active.set(false)
            runCatching { record.stop() }
            runCatching { record.release() }
            if (currentSession === session) currentSession = null
        }
    }

    private data class RecorderConfiguration(
        val record: AudioRecord,
        val sampleRate: Int,
        val sourceName: String,
    )
}
