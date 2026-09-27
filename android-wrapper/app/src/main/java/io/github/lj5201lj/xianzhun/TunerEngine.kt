package io.github.lj5201lj.xianzhun

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

data class AudioUpdate(
    val frequency: Double?,
    val rms: Double,
    val clarity: Double,
    val hasSignal: Boolean,
    val level: Double,
)

class TunerEngine(
    private val context: Context,
    private val onUpdate: (AudioUpdate) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Result<String> {
        stop()
        return runCatching {
            val (record, sampleRate, sourceName) = createAudioRecord()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "系统未能启动录音，请关闭其他录音应用后重试。"
            }
            audioRecord = record
            running.set(true)
            paused.set(false)
            worker = thread(name = "xianzhun-pitch", start = true) {
                captureLoop(record, sampleRate)
            }
            "$sourceName · ${sampleRate / 1000.0} kHz"
        }.onFailure {
            stop()
        }
    }

    fun setPaused(value: Boolean) {
        paused.set(value)
    }

    fun stop() {
        running.set(false)
        paused.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        worker?.interrupt()
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): RecorderConfiguration {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportsUnprocessed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "未经处理的麦克风")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "低处理麦克风")
            add(MediaRecorder.AudioSource.MIC to "标准麦克风")
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
                    return RecorderConfiguration(record, rate, sourceName)
                }
                record.release()
            }
        }
        error("这台设备没有可用的麦克风录音配置。")
    }

    private fun captureLoop(record: AudioRecord, sampleRate: Int) {
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
        val pitchHistory = ArrayDeque<Double>()
        var smoothedPitch: Double? = null
        var pendingJumpFrames = 0
        var noiseFloor = 0.0012
        var wasPaused = false

        fun resetAnalysis() {
            ring.fill(0f)
            ringIndex = 0
            ringCount = 0
            sinceAnalysis = 0
            pitchHistory.clear()
            smoothedPitch = null
            pendingJumpFrames = 0
        }

        try {
            while (running.get()) {
                val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("麦克风读取失败（$count）。")
                if (count == 0) continue

                if (paused.get()) {
                    if (!wasPaused) resetAnalysis()
                    wasPaused = true
                    continue
                }
                if (wasPaused) {
                    resetAnalysis()
                    wasPaused = false
                }

                for (index in 0 until count) {
                    val input = shorts[index] / 32768.0
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

                val rms = YinPitchDetector.calculateRms(snapshot)
                val gate = max(0.0024, noiseFloor * 2.15)
                val level = ((rms - 0.0015) / 0.055).coerceIn(0.0, 1.0)
                if (rms < gate) {
                    noiseFloor = (noiseFloor * 0.94 + rms * 0.06).coerceIn(0.0007, 0.012)
                    onUpdate(AudioUpdate(null, rms, 0.0, false, level))
                    continue
                }

                val estimate = YinPitchDetector.estimate(
                    snapshot,
                    sampleRate,
                    minFrequency = 55.0,
                    maxFrequency = 700.0,
                    threshold = 0.18,
                    minimumRms = gate,
                )
                if (estimate == null || estimate.clarity < 0.62) {
                    if (rms < noiseFloor * 1.7) {
                        noiseFloor = (noiseFloor * 0.98 + rms * 0.02).coerceIn(0.0007, 0.012)
                    }
                    onUpdate(AudioUpdate(null, rms, estimate?.clarity ?: 0.0, true, level))
                    continue
                }

                val previous = smoothedPitch
                if (previous != null) {
                    val jump = abs(TuningMath.centsFromTarget(estimate.frequency, previous))
                    if (jump > 150.0) {
                        pendingJumpFrames += 1
                        if (pendingJumpFrames < 2) continue
                        pitchHistory.clear()
                        smoothedPitch = null
                    } else {
                        pendingJumpFrames = 0
                    }
                }

                pitchHistory.addLast(estimate.frequency)
                while (pitchHistory.size > 5) pitchHistory.removeFirst()
                val stable = pitchHistory.sorted()[pitchHistory.size / 2]
                smoothedPitch = smoothedPitch?.let { it * 0.68 + stable * 0.32 } ?: stable
                onUpdate(
                    AudioUpdate(
                        frequency = smoothedPitch,
                        rms = rms,
                        clarity = estimate.clarity,
                        hasSignal = true,
                        level = level,
                    ),
                )
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error.message ?: "麦克风读取发生错误。")
        } finally {
            running.set(false)
            runCatching { record.stop() }
            runCatching { record.release() }
            if (audioRecord === record) audioRecord = null
        }
    }

    private data class RecorderConfiguration(
        val record: AudioRecord,
        val sampleRate: Int,
        val sourceName: String,
    )
}
