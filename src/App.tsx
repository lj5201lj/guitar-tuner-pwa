import { useEffect, useMemo, useState } from 'react'
import { useReferenceTone } from './audio/useReferenceTone'
import { useTuner, type TunerMode } from './audio/useTuner'
import {
  BUILT_IN_TUNINGS,
  cloneAsCustom,
  createCustomTuning,
  isCustomPitchInRange,
  loadCustomTuning,
  NOTE_OPTIONS,
  noteToFrequency,
  saveCustomTuning,
  STANDARD_TUNING,
  type GuitarString,
  type GuitarStringNumber,
  type NoteName,
  type TuningPreset,
} from './audio/tunings'

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

function Meter({ cents, active, accurate }: { cents: number; active: boolean; accurate: boolean }) {
  const clampedCents = clamp(cents, -50, 50)
  const ticks = useMemo(() => Array.from({ length: 21 }, (_, index) => -50 + index * 5), [])

  const point = (value: number, radius: number) => {
    const angle = 200 + ((value + 50) / 100) * 140
    const radians = (angle * Math.PI) / 180
    return {
      x: 180 + radius * Math.cos(radians),
      y: 188 + radius * Math.sin(radians),
    }
  }

  return (
    <div className={`meter ${active ? 'is-active' : ''} ${accurate ? 'is-accurate' : ''}`}>
      <svg viewBox="0 0 360 225" role="img" aria-label={`调音偏差 ${active ? `${cents > 0 ? '+' : ''}${Math.round(cents)} cents` : '无读数'}`}>
        <path className="meter-track" d="M59.7 144.2 A128 128 0 0 1 300.3 144.2" />
        <path className="meter-zone" d="M164.4 61 A128 128 0 0 1 195.6 61" />
        {ticks.map((value) => {
          const inner = point(value, value % 25 === 0 ? 111 : 116)
          const outer = point(value, 128)
          return (
            <line
              className={value === 0 ? 'meter-tick center' : 'meter-tick'}
              key={value}
              x1={inner.x}
              y1={inner.y}
              x2={outer.x}
              y2={outer.y}
            />
          )
        })}
        <g
          className="needle"
          style={{ transform: `rotate(${(clampedCents / 50) * 70}deg)` }}
        >
          <line x1="180" y1="188" x2="180" y2="70" />
          <circle cx="180" cy="188" r="9" />
        </g>
        <text className="meter-label left" x="42" y="188">−50</text>
        <text className="meter-label center-label" x="180" y="39">0</text>
        <text className="meter-label right" x="318" y="188">+50</text>
      </svg>
      <div className="meter-readout" aria-live="polite">
        <span className="readout-value">{active ? `${cents >= 0 ? '+' : ''}${Math.round(cents)}` : '—'}</span>
        <span className="readout-unit">cents</span>
      </div>
    </div>
  )
}

function StringButton({ guitarString, active, sounding, onSelect }: {
  guitarString: GuitarString
  active: boolean
  sounding: boolean
  onSelect: () => void
}) {
  return (
    <button
      className={`string-button ${active ? 'active' : ''}`}
      type="button"
      aria-pressed={active}
      aria-label={sounding
        ? `重新拨响 ${guitarString.stringNumber} 弦 ${guitarString.note}${guitarString.octave} 吉他参考音`
        : `选择并拨响 ${guitarString.stringNumber} 弦 ${guitarString.note}${guitarString.octave} 吉他参考音`}
      onClick={onSelect}
    >
      <span className="string-number">{guitarString.stringNumber}</span>
      <span className="string-note">{guitarString.note}</span>
      <span className="string-octave">{guitarString.octave}</span>
      {sounding && <span className="string-sound" aria-hidden="true">♪</span>}
    </button>
  )
}

function CustomTuningEditor({
  initialTuning,
  onCancel,
  onSave,
}: {
  initialTuning: TuningPreset
  onCancel: () => void
  onSave: (tuning: TuningPreset) => void
}) {
  const [draft, setDraft] = useState<GuitarString[]>(() =>
    initialTuning.strings.map((guitarString) => ({ ...guitarString })),
  )
  const [errorMessage, setErrorMessage] = useState('')
  const octaves = [1, 2, 3, 4, 5]

  const updateString = (
    stringNumber: GuitarStringNumber,
    next: { note?: NoteName; octave?: number },
  ) => {
    setDraft((current) => current.map((guitarString) => {
      if (guitarString.stringNumber !== stringNumber) return guitarString
      const note = next.note ?? guitarString.note
      const octave = next.octave ?? guitarString.octave
      return { ...guitarString, note, octave, frequency: noteToFrequency(note, octave) }
    }))
    setErrorMessage('')
  }

  const save = () => {
    if (!draft.every(({ note, octave }) => isCustomPitchInRange(note, octave))) {
      setErrorMessage('每根弦的音高需在 B1 到 E5 之间。')
      return
    }
    try {
      const tuning = createCustomTuning(draft)
      saveCustomTuning(tuning)
      onSave(tuning)
    } catch {
      setErrorMessage('浏览器无法保存此方案，请检查隐私或存储设置。')
    }
  }

  return (
    <div className="editor-backdrop" role="presentation" onMouseDown={onCancel}>
      <section
        className="tuning-editor"
        role="dialog"
        aria-modal="true"
        aria-labelledby="custom-tuning-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="editor-heading">
          <div>
            <p className="eyebrow">SPECIAL TUNING</p>
            <h2 id="custom-tuning-title">自定义调弦</h2>
          </div>
          <button className="editor-close" type="button" onClick={onCancel} aria-label="关闭自定义调弦">×</button>
        </div>

        <p className="editor-hint">逐弦选择音名和八度，范围 B1–E5。重复音与非递增调弦均可使用。</p>

        <div className="editor-strings">
          {draft.map((guitarString) => (
            <div className="editor-string-row" key={guitarString.stringNumber}>
              <strong>{guitarString.stringNumber} 弦</strong>
              <label>
                <span className="sr-only">{guitarString.stringNumber} 弦音名</span>
                <select
                  value={guitarString.note}
                  onChange={(event) => updateString(
                    guitarString.stringNumber,
                    { note: event.target.value as NoteName },
                  )}
                >
                  {NOTE_OPTIONS.map((note) => (
                    <option
                      key={note}
                      value={note}
                      disabled={!isCustomPitchInRange(note, guitarString.octave)}
                    >
                      {note}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span className="sr-only">{guitarString.stringNumber} 弦八度</span>
                <select
                  value={guitarString.octave}
                  onChange={(event) => updateString(
                    guitarString.stringNumber,
                    { octave: Number(event.target.value) },
                  )}
                >
                  {octaves.map((octave) => (
                    <option
                      key={octave}
                      value={octave}
                      disabled={!isCustomPitchInRange(guitarString.note, octave)}
                    >
                      {octave}
                    </option>
                  ))}
                </select>
              </label>
              <span className="editor-frequency">{guitarString.frequency.toFixed(2)} Hz</span>
            </div>
          ))}
        </div>

        {errorMessage && <p className="editor-error" role="alert">{errorMessage}</p>}

        <div className="editor-actions">
          <button className="editor-cancel" type="button" onClick={onCancel}>取消</button>
          <button className="editor-save" type="button" onClick={save}>保存并使用</button>
        </div>
      </section>
    </div>
  )
}

export default function App() {
  const [mode, setMode] = useState<TunerMode>('auto')
  const [manualStringNumber, setManualStringNumber] = useState<GuitarStringNumber>(6)
  const [activeTuningId, setActiveTuningId] = useState<TuningPreset['id']>('standard')
  const [customTuning, setCustomTuning] = useState<TuningPreset | null>(() => loadCustomTuning())
  const [editorInitialTuning, setEditorInitialTuning] = useState<TuningPreset | null>(null)
  const [installPrompt, setInstallPrompt] = useState<InstallPromptEvent | null>(null)
  const [showInstallHelp, setShowInstallHelp] = useState(false)
  const tuningPresets = useMemo(
    () => customTuning ? [...BUILT_IN_TUNINGS, customTuning] : [...BUILT_IN_TUNINGS],
    [customTuning],
  )
  const activeTuning = tuningPresets.find((preset) => preset.id === activeTuningId) ?? STANDARD_TUNING
  const manualString = activeTuning.strings.find(
    (guitarString) => guitarString.stringNumber === manualStringNumber,
  ) ?? activeTuning.strings[0]
  const referenceTone = useReferenceTone()
  const referenceString = referenceTone.activeString
  const playReferenceTone = referenceTone.play
  const { engineState, reading, errorMessage, start, stop } = useTuner(
    mode,
    manualString,
    activeTuning.strings,
    referenceTone.isPlaying,
  )

  useEffect(() => {
    const capturePrompt = (event: Event) => {
      event.preventDefault()
      setInstallPrompt(event as InstallPromptEvent)
    }
    const installed = () => setInstallPrompt(null)
    window.addEventListener('beforeinstallprompt', capturePrompt)
    window.addEventListener('appinstalled', installed)
    return () => {
      window.removeEventListener('beforeinstallprompt', capturePrompt)
      window.removeEventListener('appinstalled', installed)
    }
  }, [])

  useEffect(() => {
    if (!referenceString) return
    const updatedString = activeTuning.strings.find(
      (guitarString) => guitarString.stringNumber === referenceString.stringNumber,
    )
    if (
      updatedString &&
      Math.abs(updatedString.frequency - referenceString.frequency) > 0.001
    ) {
      void playReferenceTone(updatedString)
    }
  }, [activeTuning, playReferenceTone, referenceString])

  const activeString = referenceTone.activeString
    ?? (reading.hasSignal && reading.target
    ? reading.target
    : mode === 'manual'
      ? manualString
      : null)
  const cents = reading.cents ?? 0
  const accurate = !referenceTone.isPlaying && reading.hasSignal && Math.abs(cents) <= 5
  const status = referenceTone.activeString
    ? `吉他参考音 ${referenceTone.activeString.note}${referenceTone.activeString.octave} 渐弱中`
    : !reading.hasSignal
    ? engineState === 'listening' ? '弹响一根琴弦' : '等待开始'
    : accurate ? '准确' : cents < 0 ? '偏低' : '偏高'

  const install = async () => {
    if (installPrompt) {
      await installPrompt.prompt()
      const choice = await installPrompt.userChoice
      if (choice.outcome === 'accepted') setInstallPrompt(null)
    } else {
      setShowInstallHelp((current) => !current)
    }
  }

  const openCustomEditor = () => {
    setEditorInitialTuning(customTuning ?? cloneAsCustom(activeTuning))
  }

  const saveCustom = (tuning: TuningPreset) => {
    setCustomTuning(tuning)
    setActiveTuningId('custom')
    setEditorInitialTuning(null)
  }

  return (
    <main className={`app-shell status-${referenceTone.isPlaying ? 'tone' : accurate ? 'accurate' : cents < -5 ? 'flat' : cents > 5 ? 'sharp' : 'idle'}`}>
      <header className="topbar">
        <div className="identity">
          <span className="brand-mark" aria-hidden="true">♯</span>
          <div>
            <p className="eyebrow">GUITAR TUNER</p>
            <h1>弦准</h1>
          </div>
        </div>
        <button className="install-button" type="button" onClick={install} aria-expanded={showInstallHelp}>
          <span aria-hidden="true">↓</span> 安装
        </button>
      </header>

      {showInstallHelp && (
        <aside className="install-help">
          iPhone：在 Safari 点“分享”→“添加到主屏幕”；Android：打开浏览器菜单并选择“安装应用”。
        </aside>
      )}

      <section className="tuner" aria-label="吉他调音器">
        <div className="mode-switch" role="group" aria-label="调音模式">
          <button className={mode === 'auto' ? 'selected' : ''} type="button" onClick={() => setMode('auto')}>自动</button>
          <button className={mode === 'manual' ? 'selected' : ''} type="button" onClick={() => setMode('manual')}>手动</button>
        </div>

        <div className={`note-display ${activeString ? '' : 'muted'}`}>
          <span className="note-name">{activeString?.note ?? '—'}</span>
          {activeString && <span className="note-octave">{activeString.octave}</span>}
          <span className="note-string">{activeString ? `${activeString.stringNumber} 弦` : '等待声音'}</span>
        </div>

        <Meter cents={cents} active={!referenceTone.isPlaying && reading.hasSignal} accurate={accurate} />

        <div className="status-line" aria-live="polite">
          <span className="status-dot" aria-hidden="true" />
          <strong>{status}</strong>
        </div>

        <div className="telemetry">
          <div>
            <span className="telemetry-label">当前频率</span>
            <strong>{reading.frequency ? reading.frequency.toFixed(2) : '—'} <small>Hz</small></strong>
          </div>
          <div>
            <span className="telemetry-label">目标频率</span>
            <strong>{activeString ? activeString.frequency.toFixed(2) : '—'} <small>Hz</small></strong>
          </div>
        </div>

        <div className="input-level" aria-label={`输入电平 ${Math.round(reading.level * 100)}%`}>
          <span>输入</span>
          <div className="level-track"><div className="level-fill" style={{ width: `${reading.level * 100}%` }} /></div>
        </div>

        {referenceTone.isPlaying && (
          <button className="reference-stop" type="button" onClick={referenceTone.stop}>
            <span aria-hidden="true">■</span> 停止参考音
          </button>
        )}

        <div className="engine-control">
          {engineState === 'listening' ? (
            <button className="stop-button" type="button" onClick={stop}>
              <span className="stop-icon" aria-hidden="true" /> 停止聆听
            </button>
          ) : (
            <button className="start-button" type="button" onClick={() => void start()} disabled={engineState === 'requesting'}>
              <span className="mic-icon" aria-hidden="true">●</span>
              {engineState === 'requesting' ? '正在请求权限…' : engineState === 'idle' ? '开启麦克风' : '重新尝试'}
            </button>
          )}
          {errorMessage && <p className="error-message" role="alert">{errorMessage}</p>}
          {referenceTone.errorMessage && <p className="error-message" role="alert">{referenceTone.errorMessage}</p>}
        </div>
      </section>

      <section className="strings" aria-label="六根吉他弦">
        <div className="tuning-toolbar">
          <label className="tuning-select">
            <span>调弦方案</span>
            <select
              value={activeTuning.id}
              onChange={(event) => setActiveTuningId(event.target.value as TuningPreset['id'])}
            >
              {tuningPresets.map((preset) => (
                <option key={preset.id} value={preset.id}>{preset.name}</option>
              ))}
            </select>
          </label>
          <button className="custom-tuning-button" type="button" onClick={openCustomEditor}>
            {customTuning ? '编辑自定义' : '新建自定义'}
          </button>
        </div>
        <div className="strings-heading">
          <span>{activeTuning.name}</span>
          <span>{referenceTone.isPlaying ? '再次点击重新拨弦' : mode === 'auto' ? '自动识别' : '点弦播放吉他音'}</span>
        </div>
        <div className="string-grid">
          {activeTuning.strings.map((guitarString) => (
            <StringButton
              key={guitarString.id}
              guitarString={guitarString}
              active={activeString?.id === guitarString.id}
              sounding={referenceTone.activeString?.stringNumber === guitarString.stringNumber}
              onSelect={() => {
                setManualStringNumber(guitarString.stringNumber)
                setMode('manual')
                referenceTone.toggle(guitarString)
              }}
            />
          ))}
        </div>
      </section>

      {editorInitialTuning && (
        <CustomTuningEditor
          initialTuning={editorInitialTuning}
          onCancel={() => setEditorInitialTuning(null)}
          onSave={saveCustom}
        />
      )}
    </main>
  )
}
