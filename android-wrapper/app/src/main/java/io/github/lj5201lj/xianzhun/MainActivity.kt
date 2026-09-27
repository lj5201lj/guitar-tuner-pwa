package io.github.lj5201lj.xianzhun

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var stopToneButton: Button
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

        setContentView(buildInterface())
        refreshPresetSpinner()
        updateModeUi()
        updateStringButtons()
        clearReading("点击“开启麦克风”，然后逐根拨弦")
    }

    private fun buildInterface(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "弦准"
            setTextColor(textPrimary)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        root.addView(TextView(this).apply {
            text = "原生吉他调音器 · YIN 实时检测"
            setTextColor(textMuted)
            textSize = 13f
        }, marginParams(top = 2, bottom = 14))

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
            setPadding(dp(14), dp(14), dp(14), dp(10))
            background = rounded(surface, 20)
        }
        val noteRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        noteText = TextView(this).apply {
            text = "—"
            setTextColor(textPrimary)
            textSize = 70f
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
        readingCard.addView(noteRow, ViewGroup.LayoutParams.MATCH_PARENT, dp(86))

        frequencyText = TextView(this).apply {
            text = "— Hz"
            setTextColor(textMuted)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        readingCard.addView(frequencyText)

        meter = TunerMeterView(this)
        readingCard.addView(meter, ViewGroup.LayoutParams.MATCH_PARENT, dp(190))

        centsText = TextView(this).apply {
            text = "— cents"
            setTextColor(textPrimary)
            textSize = 27f
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
            text = "开启麦克风"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 23, 18))
            background = rounded(primary, 15)
            setOnClickListener {
                if (listening) stopListening() else requestMicrophoneAndStart()
            }
        }
        root.addView(microphoneButton, marginParams(width = -1, height = 54, top = 12))

        root.addView(TextView(this).apply {
            text = "点选琴弦可播放真实吉他参考音，并切换到手动模式"
            setTextColor(textMuted)
            textSize = 12f
            gravity = Gravity.CENTER
        }, marginParams(top = 14, bottom = 8))

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
            stringsRow.addView(button, marginParams(width = 0, height = 64, weight = 1f, left = if (number == 6) 0 else 4))
        }
        root.addView(stringsRow)

        stopToneButton = Button(this).apply {
            text = "停止参考音"
            isAllCaps = false
            visibility = View.GONE
            setTextColor(gold)
            background = rounded(surface, 12, gold)
            setOnClickListener { stopReferenceTone(resumeDetection = true) }
        }
        root.addView(stopToneButton, marginParams(width = -1, height = 46, top = 8))

        root.addView(TextView(this).apply {
            text = "建议：靠近手机底部麦克风，单独拨一根弦，拨弦后停顿约 1 秒。"
            setTextColor(textMuted)
            textSize = 12f
            gravity = Gravity.CENTER
        }, marginParams(top = 12))

        return scroll
    }

    private fun requestMicrophoneAndStart() {
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
        if (referencePlaying) stopReferenceTone(resumeDetection = false)
        statusText.setTextColor(textMuted)
        statusText.text = "正在启动麦克风…"
        val result = tunerEngine.start()
        if (result.isSuccess) {
            listening = true
            lastValidAt = 0L
            microphoneButton.text = "停止麦克风"
            microphoneButton.setTextColor(textPrimary)
            microphoneButton.background = rounded(surfaceRaised, 15, gold)
            engineInfoText.text = result.getOrNull()
            statusText.text = "正在听，请单独拨响一根弦"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            showEngineError(result.exceptionOrNull()?.message ?: "无法启动麦克风")
        }
    }

    private fun stopListening() {
        tunerEngine.stop()
        listening = false
        lastValidAt = 0L
        signalLevel.progress = 0
        microphoneButton.text = "开启麦克风"
        microphoneButton.setTextColor(Color.rgb(20, 23, 18))
        microphoneButton.background = rounded(primary, 15)
        engineInfoText.text = "麦克风已停止"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clearReading("麦克风已停止")
    }

    private fun showEngineError(message: String) {
        tunerEngine.stop()
        listening = false
        microphoneButton.text = "重试麦克风"
        microphoneButton.setTextColor(Color.rgb(20, 23, 18))
        microphoneButton.background = rounded(primary, 15)
        engineInfoText.text = "录音启动失败"
        clearReading(message)
        statusText.setTextColor(errorColor)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderAudioUpdate(update: AudioUpdate) {
        if (!listening || referencePlaying) return
        signalLevel.progress = (update.level * 1000).toInt()
        val frequency = update.frequency
        if (frequency == null) {
            if (SystemClock.elapsedRealtime() - lastValidAt < READING_HOLD_MS) return
            noteText.text = "—"
            octaveText.text = ""
            frequencyText.text = "— Hz"
            centsText.text = "— cents"
            meter.setReading(0.0, active = false, isAccurate = false)
            statusText.setTextColor(textMuted)
            statusText.text = if (update.hasSignal) {
                "已收到声音，正在稳定音高…"
            } else {
                "输入较轻，请靠近麦克风并单独拨一根弦"
            }
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
        noteText.text = "—"
        octaveText.text = ""
        frequencyText.text = "— Hz"
        centsText.text = "— cents"
        centsText.setTextColor(textPrimary)
        statusText.setTextColor(textMuted)
        statusText.text = message
        meter.setReading(0.0, active = false, isAccurate = false)
    }

    private fun playReferenceTone(string: GuitarString) {
        referenceTonePlayer.stop(notify = false)
        referencePlaying = true
        tunerEngine.setPaused(true)
        stopToneButton.visibility = View.VISIBLE
        updateStringButtons()
        clearReading("参考音 ${string.displayName()} 播放中；识别已暂时暂停")
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
        referenceTonePlayer.stop(notify = false)
        referencePlaying = false
        stopToneButton.visibility = View.GONE
        updateStringButtons()
        tunerEngine.setPaused(false)
        lastValidAt = 0L
        if (resumeDetection && listening) clearReading("参考音已停止，继续听取麦克风")
    }

    private fun finishReferenceTone() {
        if (!referencePlaying) return
        referencePlaying = false
        stopToneButton.visibility = View.GONE
        updateStringButtons()
        tunerEngine.setPaused(false)
        lastValidAt = 0L
        if (listening) clearReading("参考音已结束，继续听取麦克风")
        else clearReading("参考音已结束")
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
            button.contentDescription = "${number} 弦 ${string.note}${string.octave}，播放吉他参考音"
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
        if (referencePlaying) stopReferenceTone(resumeDetection = false)
        super.onStop()
    }

    override fun onDestroy() {
        tunerEngine.stop()
        referenceTonePlayer.release()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val READING_HOLD_MS = 1_800L
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
