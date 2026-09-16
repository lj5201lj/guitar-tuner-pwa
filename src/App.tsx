import { useEffect, useMemo, useState } from 'react'
import { GUITAR_STRINGS, type GuitarString } from './audio/pitch'
import { useTuner, type TunerMode } from './audio/useTuner'

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

function StringButton({ guitarString, active, onSelect }: {
  guitarString: GuitarString
  active: boolean
  onSelect: () => void
}) {
  return (
    <button
      className={`string-button ${active ? 'active' : ''}`}
      type="button"
      aria-pressed={active}
      aria-label={`选择 ${guitarString.stringNumber} 弦 ${guitarString.note}${guitarString.octave}`}
      onClick={onSelect}
    >
      <span className="string-number">{guitarString.stringNumber}</span>
      <span className="string-note">{guitarString.note}</span>
      <span className="string-octave">{guitarString.octave}</span>
    </button>
  )
}

export default function App() {
  const [mode, setMode] = useState<TunerMode>('auto')
  const [manualString, setManualString] = useState<GuitarString>(GUITAR_STRINGS[0])
  const [installPrompt, setInstallPrompt] = useState<InstallPromptEvent | null>(null)
  const [showInstallHelp, setShowInstallHelp] = useState(false)
  const { engineState, reading, errorMessage, start, stop } = useTuner(mode, manualString)

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

  const activeString = reading.hasSignal && reading.target
    ? reading.target
    : mode === 'manual'
      ? manualString
      : null
  const cents = reading.cents ?? 0
  const accurate = reading.hasSignal && Math.abs(cents) <= 5
  const status = !reading.hasSignal
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

  return (
    <main className={`app-shell status-${accurate ? 'accurate' : cents < -5 ? 'flat' : cents > 5 ? 'sharp' : 'idle'}`}>
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

        <div className={`note-display ${reading.hasSignal ? '' : 'muted'}`}>
          <span className="note-name">{activeString?.note ?? '—'}</span>
          {activeString && <span className="note-octave">{activeString.octave}</span>}
          <span className="note-string">{activeString ? `${activeString.stringNumber} 弦` : '等待声音'}</span>
        </div>

        <Meter cents={cents} active={reading.hasSignal} accurate={accurate} />

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
        </div>
      </section>

      <section className="strings" aria-label="六根吉他弦">
        <div className="strings-heading">
          <span>标准调弦</span>
          <span>{mode === 'auto' ? '自动识别' : '点击选择琴弦'}</span>
        </div>
        <div className="string-grid">
          {GUITAR_STRINGS.map((guitarString) => (
            <StringButton
              key={guitarString.id}
              guitarString={guitarString}
              active={activeString?.id === guitarString.id}
              onSelect={() => {
                setManualString(guitarString)
                setMode('manual')
              }}
            />
          ))}
        </div>
      </section>
    </main>
  )
}
