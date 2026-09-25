import { useCallback, useEffect, useRef, useState } from 'react'
import {
  calculateRms,
  centsFromTarget,
  estimatePitchYin,
  nearestGuitarString,
  type GuitarString,
} from './pitch'

export type TunerMode = 'auto' | 'manual'
export type EngineState = 'idle' | 'requesting' | 'listening' | 'denied' | 'unsupported' | 'error'

export type TunerReading = {
  frequency: number | null
  cents: number | null
  target: GuitarString | null
  clarity: number
  level: number
  hasSignal: boolean
}

const EMPTY_READING: TunerReading = {
  frequency: null,
  cents: null,
  target: null,
  clarity: 0,
  level: 0,
  hasSignal: false,
}

const ANALYSIS_RESET_MS = 500
const READING_HOLD_MS = 2_000
const QUIET_RENDER_INTERVAL_MS = 120

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

const median = (values: number[]) => {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.floor(sorted.length / 2)]
}

export function useTuner(mode: TunerMode, manualString: GuitarString) {
  const [engineState, setEngineState] = useState<EngineState>('idle')
  const [reading, setReading] = useState<TunerReading>(EMPTY_READING)
  const [errorMessage, setErrorMessage] = useState('')

  const modeRef = useRef(mode)
  const manualStringRef = useRef(manualString)
  const streamRef = useRef<MediaStream | null>(null)
  const contextRef = useRef<AudioContext | null>(null)
  const frameRef = useRef<number | null>(null)

  useEffect(() => {
    modeRef.current = mode
    manualStringRef.current = manualString
  }, [manualString, mode])

  const releaseAudio = useCallback(() => {
    if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
    frameRef.current = null
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    if (contextRef.current && contextRef.current.state !== 'closed') {
      void contextRef.current.close()
    }
    contextRef.current = null
  }, [])

  const stop = useCallback(() => {
    releaseAudio()
    setEngineState('idle')
    setReading(EMPTY_READING)
    setErrorMessage('')
  }, [releaseAudio])

  const start = useCallback(async () => {
    releaseAudio()
    setErrorMessage('')
    setReading(EMPTY_READING)

    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setEngineState('unsupported')
      setErrorMessage('麦克风需要 HTTPS 安全连接；电脑本机可使用 localhost。')
      return
    }

    setEngineState('requesting')

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: false,
          noiseSuppression: false,
          autoGainControl: false,
          channelCount: 1,
          sampleRate: { ideal: 48_000 },
        },
        video: false,
      })

      const AudioContextClass = window.AudioContext ||
        (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext

      if (!AudioContextClass) throw new Error('AudioContext is unavailable')

      const context = new AudioContextClass({ latencyHint: 'interactive' })
      await context.resume()

      const source = context.createMediaStreamSource(stream)
      const highPass = context.createBiquadFilter()
      const lowPass = context.createBiquadFilter()
      const analyser = context.createAnalyser()

      highPass.type = 'highpass'
      highPass.frequency.value = 65
      highPass.Q.value = 0.72
      lowPass.type = 'lowpass'
      lowPass.frequency.value = 1_200
      lowPass.Q.value = 0.72
      analyser.fftSize = 4096
      analyser.smoothingTimeConstant = 0

      source.connect(highPass)
      highPass.connect(lowPass)
      lowPass.connect(analyser)

      streamRef.current = stream
      contextRef.current = context
      setEngineState('listening')

      const samples = new Float32Array(analyser.fftSize)
      const pitchHistory: number[] = []
      let smoothedPitch: number | null = null
      let noiseFloor = 0.003
      let pendingJumpFrames = 0
      let lastAnalysisAt = 0
      let lastValidAt = 0
      let lastQuietRenderAt = 0

      const handleMissingPitch = (now: number, level: number) => {
        const timeSinceValidPitch = now - lastValidAt

        if (
          timeSinceValidPitch > ANALYSIS_RESET_MS &&
          (pitchHistory.length > 0 || smoothedPitch !== null)
        ) {
          pitchHistory.length = 0
          smoothedPitch = null
          pendingJumpFrames = 0
        }

        if (now - lastQuietRenderAt <= QUIET_RENDER_INTERVAL_MS) return
        lastQuietRenderAt = now

        if (timeSinceValidPitch > READING_HOLD_MS) {
          setReading({ ...EMPTY_READING, level })
          return
        }

        setReading((current) => current.hasSignal ? { ...current, level } : { ...EMPTY_READING, level })
      }

      const analyse = (now: number) => {
        frameRef.current = requestAnimationFrame(analyse)
        if (now - lastAnalysisAt < 65) return
        lastAnalysisAt = now

        analyser.getFloatTimeDomainData(samples)
        const rms = calculateRms(samples)
        const level = clamp((rms - 0.003) / 0.075, 0, 1)
        const gate = Math.max(0.008, noiseFloor * 2.6)

        if (rms < gate) {
          noiseFloor = clamp(noiseFloor * 0.96 + rms * 0.04, 0.002, 0.012)
          handleMissingPitch(now, level)
          return
        }

        const estimate = estimatePitchYin(samples, context.sampleRate)
        if (!estimate || estimate.clarity < 0.74) {
          noiseFloor = clamp(noiseFloor * 0.98 + Math.min(rms, 0.012) * 0.02, 0.002, 0.012)
          handleMissingPitch(now, level)
          return
        }

        if (smoothedPitch !== null) {
          const jump = Math.abs(centsFromTarget(estimate.frequency, smoothedPitch))
          if (jump > 110) {
            pendingJumpFrames += 1
            if (pendingJumpFrames < 2) return
            pitchHistory.length = 0
            smoothedPitch = null
          } else {
            pendingJumpFrames = 0
          }
        }

        pitchHistory.push(estimate.frequency)
        if (pitchHistory.length > 5) pitchHistory.shift()
        const stablePitch = median(pitchHistory)
        smoothedPitch = smoothedPitch === null
          ? stablePitch
          : smoothedPitch * 0.72 + stablePitch * 0.28

        const target = modeRef.current === 'auto'
          ? nearestGuitarString(smoothedPitch)
          : manualStringRef.current

        lastValidAt = now
        setReading({
          frequency: smoothedPitch,
          cents: centsFromTarget(smoothedPitch, target.frequency),
          target,
          clarity: estimate.clarity,
          level,
          hasSignal: true,
        })
      }

      frameRef.current = requestAnimationFrame(analyse)
    } catch (error) {
      releaseAudio()
      const name = error instanceof DOMException ? error.name : ''
      if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
        setEngineState('denied')
        setErrorMessage('麦克风权限被拒绝，请在浏览器网站设置中允许后重试。')
      } else {
        setEngineState('error')
        setErrorMessage('无法启动麦克风，请确认没有被其他应用占用后重试。')
      }
    }
  }, [releaseAudio])

  useEffect(() => releaseAudio, [releaseAudio])

  useEffect(() => {
    const resume = () => {
      if (document.visibilityState === 'visible' && contextRef.current?.state === 'suspended') {
        void contextRef.current.resume()
      }
    }
    document.addEventListener('visibilitychange', resume)
    return () => document.removeEventListener('visibilitychange', resume)
  }, [])

  return { engineState, reading, errorMessage, start, stop }
}
