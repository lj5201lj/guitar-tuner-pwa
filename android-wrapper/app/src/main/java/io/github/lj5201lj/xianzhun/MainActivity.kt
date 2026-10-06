package io.github.lj5201lj.xianzhun

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private val background = Color.rgb(13, 15, 12)
    private val surface = Color.rgb(27, 30, 25)
    private val surfaceRaised = Color.rgb(38, 42, 34)
    private val primary = Color.rgb(239, 255, 157)
    private val gold = Color.rgb(212, 181, 111)
    private val textPrimary = Color.rgb(244, 246, 238)
    private val textMuted = Color.rgb(159, 165, 151)
    private val errorColor = Color.rgb(255, 135, 120)

    private lateinit var noteText: TextView
    private lateinit var octaveText: TextView
    private lateinit var frequencyText: TextView
    private lateinit var centsText: TextView
    private lateinit var statusText: TextView
    private lateinit var microphoneButton: Button
    private lateinit var autoButton: Button
    private lateinit var manualButton: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var signalLevel: ProgressBar
    private lateinit var meter: TunerMeterView
    private lateinit var engineInfoText: TextView

    private val stringButtons = linkedMapOf<Int, Button>()
    private val preferences by lazy { getSharedPreferences("xianzhun_native_v1", MODE_PRIVATE) }
    private lateinit var customPreset: TuningPreset
    private var activePreset: TuningPreset = Tunings.standard
    private var selectedStringNumber = 6
    private var autoMode = true
    private var listening = false
    private var referencePlaying = false
    private var referenceSettling = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRecovery: Runnable? = null
    private var tunerPageSelected = true
    private lateinit var tunerPage: View
    private lateinit var metronomePanel: MetronomePanel
    private lateinit var tunerTab: Button
    private lateinit var metronomeTab: Button
    private lateinit var playbackFocus: PlaybackFocus
    private var lastValidAt = 0L
    private var changingPresetProgrammatically = false

    private lateinit var tunerEngine: TunerEngine
    private lateinit var referenceTonePlayer: ReferenceTonePlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 18, 15)
        window.navigationBarColor = background

        customPreset = Tunings.loadCustom(preferences)
        activePreset = allPresets().firstOrNull {
            it.id == preferences.getString("active_tuning", "standard")
        } ?: Tunings.standard
        selectedStringNumber = preferences.getInt("selected_string", 6).coerceIn(1, 6)
        autoMode = preferences.getBoolean("auto_mode", true)

        tunerEngine = TunerEngine(
            context = this,
            onUpdate = { update -> runOnUiThread { renderAudioUpdate(update) } },
            onError = { message -> runOnUiThread { showEngineError(message) } },
        )
        referenceTonePlayer = ReferenceTonePlayer(this)
        playbackFocus = PlaybackFocus(getSystemService(Context.AUDIO_SERVICE) as AudioManager) {
            metronomePanel.stop()
            if (referencePlaying || referenceSettling) stopReferenceTone(resumeDetection = true)
        }

        setContentView(buildInterface())
        refreshPresetSpinner()
        updateModeUi()
        updateStringButtons()
        clearReading("点击“开始调音”，然后逐根拨弦")
    }

    private fun buildInterface(): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val tabs = LinearLayout(this).apply { setPadding(dp(18), dp(8), dp(18), dp(6)) }
        tunerTab = Button(this).apply {
            text = "调音器"
            isAllCaps = false
            setOnClickListener { selectPage(tuner = true) }
        }
        metronomeTab = Button(this).apply {
            text = "节拍器"
            isAllCaps = false
            setOnClickListener { selectPage(tuner = false) }
        }
        tabs.addView(tunerTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(metronomeTab, marginParams(width = 0, height = 46, weight = 1f, left = 8))
        shell.addView(tabs)
        val pages = FrameLayout(this)
        tunerPage = buildTunerInterface()
        metronomePanel = MetronomePanel(this,
            beforeStart = {
                if (referencePlaying || referenceSettling) stopReferenceTone(resumeDetection = false)
                if (listening) stopListening()
                playbackFocus.acquire()
            },
            onPlayingChanged = { playing ->
                if (!playing) playbackFocus.release()
                updateKeepScreenOn()
            },
        ).apply { visibility = View.GONE }
        pages.addView(tunerPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(metronomePanel, FrameLayout.LayoutParams(-1, -1))
        shell.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        styleTab(tunerTab, true)
        styleTab(metronomeTab, false)
        return shell
    }

    private fun selectPage(tuner: Boolean) {
        if (tuner == tunerPageSelected) return
        if (referencePlaying || referenceSettling) stopReferenceTone(resumeDetection = false)
        if (listening) stopListening()
        metronomePanel.stop()
        tunerPageSelected = tuner
        tunerPage.visibility = if (tuner) View.VISIBLE else View.GONE
        metronomePanel.visibility = if (tuner) View.GONE else View.VISIBLE
        styleTab(tunerTab, tuner)
        styleTab(metronomeTab, !tuner)
        updateKeepScreenOn()
    }

    private fun styleTab(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.rgb(20, 23, 18) else textMuted)
        button.background = rounded(if (selected) primary else surface, 13)
        button.isSelected = selected
    }

    private fun updateKeepScreenOn() {
        if (listening || referencePlaying || metronomePanel.playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildTunerInterface(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(10))
        }
        panel.addView(transport)

        val tuningRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetSpinner = Spinner(this).apply {
            background = rounded(surfaceRaised, 13)
            setPadding(dp(12), 0, dp(8), 0)
            onItemSelectedListener = SimpleItemSelectedListener { position ->
                if (changingPresetProgrammatically) return@SimpleItemSelectedListener
                val preset = allPresets().getOrNull(position) ?: return@SimpleItemSelectedListener
                val wasPlaying = referencePlaying
                if (wasPlaying) stopReferenceTone(resumeDetection = false)
                activePreset = preset
                preferences.edit().putString("active_tuning", preset.id).apply()
                updateStringButtons()
                if (wasPlaying) playReferenceTone(selectedString())
                if (!listening && !referencePlaying) clearReading("已选择 ${preset.name}")
            }
        }
        tuningRow.addView(presetSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        tuningRow.addView(Button(this).apply {
            text = "编辑自定义"
            isAllCaps = false
            setTextColor(gold)
            textSize = 13f
            background = rounded(surface, 13, gold)
            setOnClickListener { showCustomTuningEditor() }
        }, marginParams(width = 112, height = 48, left = 9))
        root.addView(tuningRow)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        autoButton = Button(this).apply {
            text = "自动识别"
            isAllCaps = false
            setOnClickListener {
                autoMode = true
                preferences.edit().putBoolean("auto_mode", true).apply()
                updateModeUi()
                if (!listening) clearReading("自动模式会判断最接近的琴弦")
            }
        }
        manualButton = Button(this).apply {
            text = "手动选弦"
            isAllCaps = false
            setOnClickListener {
                autoMode = false
                preferences.edit().putBoolean("auto_mode", false).apply()
                updateModeUi()
                if (!listening) clearReading("手动模式：当前 ${selectedString().displayName()}")
            }
        }
        modeRow.addView(autoButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        modeRow.addView(manualButton, marginParams(width = 0, height = 46, weight = 1f, left = 8))
        root.addView(modeRow, marginParams(top = 10))

        val readingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = rounded(surface, 20)
        }
        val noteRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        noteText = TextView(this).apply {
            text = "—"
            setTextColor(textPrimary)
            textSize = 50f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        octaveText = TextView(this).apply {
            text = ""
            setTextColor(gold)
            textSize = 25f
            setPadding(dp(2), dp(26), 0, 0)
        }
        noteRow.addView(noteText)
        noteRow.addView(octaveText)
        readingCard.addView(noteRow, ViewGroup.LayoutParams.MATCH_PARENT, dp(60))

        frequencyText = TextView(this).apply {
            text = "— Hz"
            setTextColor(textMuted)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        readingCard.addView(frequencyText)

        meter = TunerMeterView(this)
        readingCard.addView(meter, ViewGroup.LayoutParams.MATCH_PARENT, dp(120))

        centsText = TextView(this).apply {
            text = "— cents"
            setTextColor(textPrimary)
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        readingCard.addView(centsText)
        statusText = TextView(this).apply {
            setTextColor(textMuted)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(3))
        }
        readingCard.addView(statusText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        signalLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 69, 61))
        }
        readingCard.addView(signalLevel, marginParams(width = -1, height = 8, top = 10, left = 10, right = 10))
        engineInfoText = TextView(this).apply {
            text = "等待启动原生麦克风"
            setTextColor(textMuted)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        readingCard.addView(engineInfoText)
        root.addView(readingCard, marginParams(top = 12))

        microphoneButton = Button(this).apply {
            text = "开始调音"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 23, 18))
            background = rounded(primary, 15)
            setOnClickListener {
                if (listening) stopListening() else requestMicrophoneAndStart()
            }
        }
        transport.addView(microphoneButton, marginParams(width = -1, height = 50))

        transport.addView(TextView(this).apply {
            text = "点选琴弦试听 1 秒参考音，结束后自动恢复识别"
            setTextColor(textMuted)
            textSize = 12f
            gravity = Gravity.CENTER
        }, marginParams(top = 8, bottom = 6))

        val stringsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (number in 6 downTo 1) {
            val button = Button(this).apply {
                isAllCaps = false
                textSize = 13f
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    selectedStringNumber = number
                    autoMode = false
                    preferences.edit()
                        .putInt("selected_string", number)
                        .putBoolean("auto_mode", false)
                        .apply()
                    updateModeUi()
                    updateStringButtons()
                    playReferenceTone(selectedString())
                }
            }
            stringButtons[number] = button
            stringsRow.addView(button, marginParams(width = 0, height = 58, weight = 1f, left = if (number == 6) 0 else 4))
        }
        transport.addView(stringsRow)
        return panel
    }

    private fun requestMicrophoneAndStart() {
        if (!tunerPageSelected) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (!tunerPageSelected || isFinishing || isDestroyed) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            clearReading("没有麦克风权限，无法识别琴弦")
            statusText.setTextColor(errorColor)
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle("需要麦克风权限")
                    .setMessage("请到系统设置 → 应用 → 弦准 → 权限，允许使用麦克风。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun startListening() {
        if (!tunerPageSelected) return
        statusText.setTextColor(textMuted)
        statusText.text = "正在启动麦克风…"
        val result = tunerEngine.start()
        if (result.isSuccess) {
            listening = true
            lastValidAt = 0L
            microphoneButton.text = "停止调音"
            microphoneButton.setTextColor(textPrimary)
            microphoneButton.background = rounded(surfaceRaised, 15, gold)
            engineInfoText.text = result.getOrNull()
            if (referencePlaying || referenceSettling) {
                tunerEngine.setPaused(true)
                statusText.text = "参考音结束后自动开始识别"
            } else statusText.text = "正在听，请单独拨响一根弦"
            updateKeepScreenOn()
        } else {
            showEngineError(result.exceptionOrNull()?.message ?: "无法启动麦克风")
        }
    }

    private fun stopListening() {
        tunerEngine.stop()
        listening = false
        lastValidAt = 0L
        signalLevel.progress = 0
        microphoneButton.text = "开始调音"
        microphoneButton.setTextColor(Color.rgb(20, 23, 18))
        microphoneButton.background = rounded(primary, 15)
        engineInfoText.text = "麦克风已停止"
        updateKeepScreenOn()
        clearReading("麦克风已停止")
    }

    private fun showEngineError(message: String) {
        tunerEngine.stop()
        listening = false
        microphoneButton.text = "重试调音"
        microphoneButton.setTextColor(Color.rgb(20, 23, 18))
        microphoneButton.background = rounded(primary, 15)
        engineInfoText.text = "录音启动失败"
        clearReading(message)
        statusText.setTextColor(errorColor)
        updateKeepScreenOn()
    }

    private fun renderAudioUpdate(update: AudioUpdate) {
        if (!listening || !tunerPageSelected || referencePlaying || referenceSettling || !tunerEngine.accepts(update)) return
        signalLevel.progress = (update.level * 1000).toInt()
        val frequency = update.frequency
        if (frequency == null) {
            if (SystemClock.elapsedRealtime() - lastValidAt < READING_HOLD_MS) return
            clearReading(if (update.hasSignal) {
                "已收到声音，正在稳定音高…"
            } else {
                "输入较轻，请靠近麦克风并单独拨一根弦"
            })
            return
        }

        lastValidAt = SystemClock.elapsedRealtime()
        val target = if (autoMode) {
            TuningMath.nearestString(frequency, activePreset.strings)
        } else {
            selectedString()
        }
        val cents = TuningMath.centsFromTarget(frequency, target.frequency)
        val accurate = abs(cents) <= 5.0
        noteText.text = target.note
        octaveText.text = target.octave.toString()
        frequencyText.text = String.format(Locale.US, "%.2f Hz  ·  目标 %.2f Hz", frequency, target.frequency)
        centsText.text = String.format(Locale.US, "%+.1f cents", cents)
        centsText.setTextColor(if (accurate) primary else textPrimary)
        statusText.setTextColor(if (accurate) primary else textMuted)
        statusText.text = when {
            accurate -> "准确"
            cents < 0 -> "偏低 · 请稍微拧紧琴弦"
            else -> "偏高 · 请稍微放松琴弦"
        }
        meter.setReading(cents, active = true, isAccurate = accurate)

        if (autoMode && selectedStringNumber != target.number) {
            selectedStringNumber = target.number
            updateStringButtons()
        }
    }

    private fun clearReading(message: String) {
        fun TextView.show(value: String) { if (text.toString() != value) text = value }
        noteText.show("—")
        octaveText.show("")
        frequencyText.show("— Hz")
        centsText.show("— cents")
        centsText.setTextColor(textPrimary)
        statusText.setTextColor(textMuted)
        statusText.show(message)
        meter.setReading(0.0, active = false, isAccurate = false)
    }

    private fun playReferenceTone(string: GuitarString) {
        if (!tunerPageSelected) return
        if (!playbackFocus.acquire()) {
            Toast.makeText(this, "暂时无法播放参考音，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        cancelReferenceRecovery()
        referenceTonePlayer.stop(notify = false)
        referencePlaying = true
        referenceSettling = false
        tunerEngine.setPaused(true)
        signalLevel.progress = 0
        lastValidAt = 0L
        updateKeepScreenOn()
        updateStringButtons()
        clearReading("参考音 ${string.displayName()} · 1 秒后自动结束")
        noteText.text = string.note
        octaveText.text = string.octave.toString()
        frequencyText.text = String.format(Locale.US, "参考音 %.2f Hz", string.frequency)
        referenceTonePlayer.play(string) {
            runOnUiThread { finishReferenceTone() }
        }.onFailure {
            finishReferenceTone()
            Toast.makeText(this, it.message ?: "参考音播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopReferenceTone(resumeDetection: Boolean) {
        cancelReferenceRecovery()
        referenceTonePlayer.stop(notify = false)
        referencePlaying = false
        referenceSettling = false
        playbackFocus.release()
        updateStringButtons()
        // Even interrupted output can leave an acoustic tail or queued capture buffers.
        if (resumeDetection && listening) tunerEngine.setPaused(false, REFERENCE_SETTLE_MS)
        else tunerEngine.setPaused(true)
        lastValidAt = 0L
        updateKeepScreenOn()
        if (resumeDetection && listening) clearReading("参考音已停止，继续听取麦克风")
    }

    private fun finishReferenceTone() {
        if (!referencePlaying) return
        referencePlaying = false
        referenceSettling = true
        playbackFocus.release()
        updateStringButtons()
        lastValidAt = 0L
        signalLevel.progress = 0
        clearReading(if (listening) "参考音已结束，等待余音消散…" else "参考音已结束")
        updateKeepScreenOn()
        val recovery = Runnable {
            pendingRecovery = null
            referenceSettling = false
            tunerEngine.setPaused(false, CAPTURE_DRAIN_MS)
            if (listening) clearReading("正在听，请单独拨响一根弦")
            else clearReading("参考音已结束，点击“开始调音”即可识别")
        }
        pendingRecovery = recovery
        handler.postDelayed(recovery, REFERENCE_SETTLE_MS)
    }

    private fun cancelReferenceRecovery() {
        pendingRecovery?.let { handler.removeCallbacks(it) }
        pendingRecovery = null
    }

    private fun selectedString(): GuitarString =
        activePreset.strings.first { it.number == selectedStringNumber }

    private fun GuitarString.displayName(): String = "$note$octave（$number 弦）"

    private fun updateModeUi() {
        fun style(button: Button, active: Boolean) {
            button.setTextColor(if (active) Color.rgb(20, 23, 18) else textMuted)
            button.background = rounded(if (active) primary else surface, 13, if (active) null else surfaceRaised)
        }
        style(autoButton, autoMode)
        style(manualButton, !autoMode)
    }

    private fun updateStringButtons() {
        for ((number, button) in stringButtons) {
            val string = activePreset.strings.first { it.number == number }
            val active = number == selectedStringNumber
            button.text = buildString {
                append(number)
                append("\n")
                append(string.note)
                append(string.octave)
                if (active && referencePlaying) append(" ♪")
            }
            button.setTextColor(if (active) Color.rgb(20, 23, 18) else textPrimary)
            button.background = rounded(if (active) primary else surface, 12, if (active) null else surfaceRaised)
            button.contentDescription = "${number} 弦 ${string.note}${string.octave}，试听 1 秒吉他参考音"
        }
    }

    private fun allPresets(): List<TuningPreset> = Tunings.builtIns + customPreset

    private fun refreshPresetSpinner() {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            allPresets().map { it.name },
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(textPrimary)
                    textSize = 15f
                    setPadding(dp(12), 0, dp(8), 0)
                }
            }
        }
        changingPresetProgrammatically = true
        presetSpinner.adapter = adapter
        presetSpinner.setSelection(allPresets().indexOfFirst { it.id == activePreset.id }.coerceAtLeast(0))
        changingPresetProgrammatically = false
    }

    private fun showCustomTuningEditor() {
        val seed = if (activePreset.id == "custom") customPreset else activePreset
        val drafts = seed.strings.associateBy { it.number }.toMutableMap()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        for (number in 6 downTo 1) {
            val current = requireNotNull(drafts[number])
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "$number 弦"
                setTextColor(Color.DKGRAY)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            val noteSpinner = Spinner(this)
            noteSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                TuningMath.noteChoices.map { it.label },
            )
            noteSpinner.setSelection(TuningMath.noteChoices.indexOfFirst { it.label == current.note }.coerceAtLeast(0))
            row.addView(noteSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))

            val octaveSpinner = Spinner(this)
            octaveSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(1, 2, 3, 4, 5),
            )
            octaveSpinner.setSelection((current.octave - 1).coerceIn(0, 4))
            row.addView(octaveSpinner, LinearLayout.LayoutParams(dp(64), dp(48)))

            val frequency = TextView(this).apply {
                setTextColor(Color.DKGRAY)
                textSize = 12f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                text = String.format(Locale.US, "%.2f Hz", current.frequency)
            }
            row.addView(frequency, LinearLayout.LayoutParams(dp(82), dp(48)))

            fun updateDraft() {
                val note = TuningMath.noteChoices[noteSpinner.selectedItemPosition].label
                val octave = octaveSpinner.selectedItem as Int
                val next = GuitarString(number, note, octave)
                drafts[number] = next
                frequency.text = String.format(Locale.US, "%.2f Hz", next.frequency)
                frequency.setTextColor(if (TuningMath.isCustomPitchValid(note, octave)) Color.DKGRAY else Color.RED)
            }
            noteSpinner.onItemSelectedListener = SimpleItemSelectedListener { updateDraft() }
            octaveSpinner.onItemSelectedListener = SimpleItemSelectedListener { updateDraft() }
            container.addView(row)
        }

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义调弦（B1–E5）")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并使用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val strings = (6 downTo 1).map { requireNotNull(drafts[it]) }
                if (!strings.all { TuningMath.isCustomPitchValid(it.note, it.octave) }) {
                    Toast.makeText(this, "每根弦必须在 B1 到 E5 之间", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val wasPlaying = referencePlaying
                if (wasPlaying) stopReferenceTone(resumeDetection = false)
                Tunings.saveCustom(preferences, strings)
                customPreset = TuningPreset("custom", "自定义", strings)
                activePreset = customPreset
                preferences.edit().putString("active_tuning", "custom").apply()
                refreshPresetSpinner()
                updateStringButtons()
                if (wasPlaying) playReferenceTone(selectedString())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun rounded(fillColor: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun marginParams(
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        if (width < 0) width else dp(width),
        if (height < 0) height else dp(height),
        weight,
    ).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        if (listening) stopListening()
        if (referencePlaying || referenceSettling) stopReferenceTone(resumeDetection = false)
        metronomePanel.stop()
        playbackFocus.release()
        super.onStop()
    }

    override fun onDestroy() {
        tunerEngine.stop()
        referenceTonePlayer.release()
        cancelReferenceRecovery()
        metronomePanel.stop()
        playbackFocus.release()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val READING_HOLD_MS = 1_800L
        private const val REFERENCE_SETTLE_MS = 400L
        private const val CAPTURE_DRAIN_MS = 80L
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long,
    ) = onSelected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
