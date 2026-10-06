package io.github.lj5201lj.xianzhun

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MetronomePanel(
    context: Context,
    private val beforeStart: () -> Boolean,
    private val onPlayingChanged: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val primary = Color.rgb(239, 255, 157)
    private val surface = Color.rgb(27, 30, 25)
    private val muted = Color.rgb(159, 165, 151)
    private val white = Color.rgb(244, 246, 238)
    private val prefs = context.getSharedPreferences("xianzhun_metronome_v1", Context.MODE_PRIVATE)
    private var settings = PlaybackSettings(
        bpm = prefs.getInt("bpm", 120).coerceIn(30, 300),
        meter = Meter.entries.firstOrNull { it.name == prefs.getString("meter", "FOUR_FOUR") } ?: Meter.FOUR_FOUR,
        sound = ClickSound.entries.firstOrNull { it.name == prefs.getString("sound", "CLICK") } ?: ClickSound.CLICK,
        volume = prefs.getFloat("volume", 0.75f).coerceIn(0f, 1f),
    )
    private val tapTempo = TapTempo()
    var playing = false
        private set
    private val beatLights = mutableListOf<TextView>()
    private val bpmText = label("", 64f, true)
    private val tempoCaption = label("", 12f)
    private val message = label("点击开始，跟着节拍练习", 13f)
    private val beatRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val bpmSlider = SeekBar(context).apply {
        max = 270
        progress = settings.bpm - 30
        contentDescription = "速度，30 到 300 BPM"
    }
    private val startButton = button("开始节拍", emphasized = true)
    private val engine = MetronomeEngine(
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        onBeatHeard = { mark ->
            if (playing && mark.settings.meter == settings.meter) {
                renderBeat(mark.pulse)
                message.text = "第 ${mark.pulse + 1} 拍 / ${settings.meter.pulses}"
            }
        },
        onPlaybackError = {
            stop()
            message.text = "音频输出不可用，请重试"
        },
    )

    init {
        orientation = VERTICAL
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
        }
        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))

        content.addView(label("节拍器", 30f, true))
        content.addView(label("稳定节奏，逐拍练习", 13f), params(top = 4, bottom = 18))

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(20), dp(14), dp(20))
            background = rounded(surface)
        }
        card.addView(bpmText)
        card.addView(tempoCaption)
        card.addView(beatRow, params(top = 20, bottom = 18, height = 52))
        card.addView(message)
        content.addView(card)

        content.addView(bpmSlider, params(height = 48, top = 14))
        val adjustRow = LinearLayout(context)
        listOf("−5" to -5, "−1" to -1, "+1" to 1, "+5" to 5).forEachIndexed { index, (title, amount) ->
            adjustRow.addView(button(title).apply {
                contentDescription = if (amount < 0) "速度降低 ${-amount} BPM" else "速度增加 $amount BPM"
                setOnClickListener { setBpm(settings.bpm + amount) }
            }, LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(6) })
        }
        content.addView(adjustRow)
        content.addView(button("点按测速").apply {
            setOnClickListener {
                val bpm = tapTempo.tap(SystemClock.elapsedRealtime())
                if (bpm != null) setBpm(bpm) else message.text = "继续按节奏点击测速"
            }
        }, params(height = 48, top = 10))

        content.addView(label("拍号", 14f, true), params(top = 22, bottom = 8))
        content.addView(spinner(Meter.entries.map { it.label }, settings.meter.ordinal) { index ->
            val meter = Meter.entries[index]
            if (settings.meter != meter) {
                settings = settings.copy(meter = meter)
                rebuildBeatLights()
                changed()
            }
        }, params(height = 48))
        content.addView(label("音色", 14f, true), params(top = 16, bottom = 8))
        content.addView(spinner(ClickSound.entries.map { it.label }, settings.sound.ordinal) { index ->
            settings = settings.copy(sound = ClickSound.entries[index])
            changed()
        }, params(height = 48))
        val volumeLabel = label("音量 ${(settings.volume * 100).toInt()}%", 14f, true)
        content.addView(volumeLabel, params(top = 16, bottom = 5))
        content.addView(SeekBar(context).apply {
            max = 100
            progress = (settings.volume * 100).toInt()
            contentDescription = "节拍音量"
            setOnSeekBarChangeListener(sliderListener { value ->
                settings = settings.copy(volume = value / 100f)
                volumeLabel.text = "音量 $value%"
                changed()
            })
        }, params(height = 48))
        content.addView(label("6/8：每小节 3+3 分组，BPM 按附点四分音符计。\n切换页面或退到后台会停止播放。", 12f), params(top = 10))

        // The transport stays visible even when settings are scrolled on a small screen.
        val transport = LinearLayout(context).apply { setPadding(dp(18), dp(8), dp(18), dp(12)) }
        transport.addView(startButton, LayoutParams(-1, dp(54)))
        addView(transport)
        startButton.setOnClickListener {
            if (playing) stop() else if (beforeStart()) {
                playing = true
                startButton.text = "停止节拍"
                message.text = "正在播放"
                onPlayingChanged(true)
                engine.start(settings)
            } else message.text = "暂时无法播放，请稍后重试"
        }
        bpmSlider.setOnSeekBarChangeListener(sliderListener { setBpm(it + 30) })
        rebuildBeatLights()
        renderTempo()
    }

    fun stop() {
        engine.stop()
        val wasPlaying = playing
        playing = false
        startButton.text = "开始节拍"
        message.text = "已停止"
        renderBeat(-1)
        if (wasPlaying) onPlayingChanged(false)
    }

    private fun setBpm(value: Int) {
        val bpm = value.coerceIn(30, 300)
        if (bpm == settings.bpm) return
        settings = settings.copy(bpm = bpm)
        if (bpmSlider.progress != bpm - 30) bpmSlider.progress = bpm - 30
        changed()
    }

    private fun changed() {
        prefs.edit().putInt("bpm", settings.bpm).putString("meter", settings.meter.name)
            .putString("sound", settings.sound.name).putFloat("volume", settings.volume).apply()
        if (playing) engine.update(settings)
        renderTempo()
    }

    private fun renderTempo() {
        bpmText.text = settings.bpm.toString()
        tempoCaption.text = if (settings.meter == Meter.SIX_EIGHT) "BPM · 附点四分音符" else "BPM · 四分音符"
    }

    private fun rebuildBeatLights() {
        beatRow.removeAllViews()
        beatLights.clear()
        repeat(settings.meter.pulses) { pulse ->
            val light = label((pulse + 1).toString(), 20f, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "第 ${pulse + 1} 拍，${if (settings.meter.accentAt(pulse) != Accent.NORMAL) "重音" else "普通拍"}"
            }
            beatLights.add(light)
            beatRow.addView(light, LayoutParams(0, -1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        renderBeat(-1)
    }

    private fun renderBeat(active: Int) {
        beatLights.forEachIndexed { index, light ->
            val accent = settings.meter.accentAt(index) != Accent.NORMAL
            light.setTextColor(if (index == active) Color.rgb(20, 23, 18) else if (accent) primary else muted)
            light.background = rounded(if (index == active) primary else Color.rgb(38, 42, 34))
        }
    }

    private fun spinner(labels: List<String>, selected: Int, onSelected: (Int) -> Unit): Spinner = Spinner(context).apply {
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setPadding(dp(12), 0, dp(8), 0)
                }
        }
        background = rounded(surface)
        setSelection(selected)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun label(title: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = title
        textSize = size
        setTextColor(if (bold) white else muted)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(title: String, emphasized: Boolean = false) = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 16f
        setTextColor(if (emphasized) Color.rgb(20, 23, 18) else white)
        background = rounded(if (emphasized) primary else surface)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun sliderListener(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) change(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun params(height: Int = -2, top: Int = 0, bottom: Int = 0) = LayoutParams(-1, if (height < 0) height else dp(height)).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
