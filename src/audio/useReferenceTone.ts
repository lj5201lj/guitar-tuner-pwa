import { useCallback, useEffect, useRef, useState } from 'react'
import type { GuitarString } from './tunings'

const FADE_SECONDS = 0.03
const OUTPUT_GAIN = 0.11
const HARMONICS = [
  { multiple: 1, gain: 1 },
  { multiple: 2, gain: 0.28 },
  { multiple: 3, gain: 0.12 },
] as const

type ToneNodes = {
  master: GainNode
  oscillators: OscillatorNode[]
  harmonicGains: GainNode[]
}

function getAudioContextClass() {
  return window.AudioContext ||
    (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
}

export function useReferenceTone() {
  const [activeString, setActiveString] = useState<GuitarString | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const contextRef = useRef<AudioContext | null>(null)
  const nodesRef = useRef<ToneNodes | null>(null)
  const activeStringRef = useRef<GuitarString | null>(null)

  const updateActiveString = useCallback((value: GuitarString | null) => {
    activeStringRef.current = value
    setActiveString(value)
  }, [])

  const stop = useCallback(() => {
    const context = contextRef.current
    const nodes = nodesRef.current
    updateActiveString(null)
    if (!context || !nodes) return

    const now = context.currentTime
    nodes.master.gain.cancelScheduledValues(now)
    nodes.master.gain.setValueAtTime(Math.max(nodes.master.gain.value, 0.0001), now)
    nodes.master.gain.exponentialRampToValueAtTime(0.0001, now + FADE_SECONDS)

    nodesRef.current = null
    window.setTimeout(() => {
      nodes.oscillators.forEach((oscillator) => {
        try { oscillator.stop() } catch { /* The node may already be stopped. */ }
        oscillator.disconnect()
      })
      nodes.harmonicGains.forEach((gain) => gain.disconnect())
      nodes.master.disconnect()
    }, (FADE_SECONDS + 0.02) * 1_000)
  }, [updateActiveString])

  const play = useCallback(async (guitarString: GuitarString) => {
    setErrorMessage('')
    try {
      const AudioContextClass = getAudioContextClass()
      if (!AudioContextClass) throw new Error('AudioContext is unavailable')

      let context = contextRef.current
      if (!context || context.state === 'closed') {
        context = new AudioContextClass({ latencyHint: 'interactive' })
        contextRef.current = context
      }
      if (context.state === 'suspended') await context.resume()

      const now = context.currentTime
      const existing = nodesRef.current

      if (existing) {
        existing.oscillators.forEach((oscillator, index) => {
          const nextFrequency = guitarString.frequency * HARMONICS[index].multiple
          oscillator.frequency.cancelScheduledValues(now)
          oscillator.frequency.setValueAtTime(Math.max(oscillator.frequency.value, 1), now)
          oscillator.frequency.exponentialRampToValueAtTime(nextFrequency, now + FADE_SECONDS)
        })
        existing.master.gain.cancelScheduledValues(now)
        existing.master.gain.setValueAtTime(Math.max(existing.master.gain.value, 0.0001), now)
        existing.master.gain.exponentialRampToValueAtTime(OUTPUT_GAIN, now + FADE_SECONDS)
      } else {
        const master = context.createGain()
        master.gain.setValueAtTime(0.0001, now)
        master.gain.exponentialRampToValueAtTime(OUTPUT_GAIN, now + FADE_SECONDS)
        master.connect(context.destination)

        const harmonicGains = HARMONICS.map((harmonic) => {
          const gain = context.createGain()
          gain.gain.value = harmonic.gain
          gain.connect(master)
          return gain
        })
        const oscillators = HARMONICS.map((harmonic, index) => {
          const oscillator = context.createOscillator()
          oscillator.type = 'sine'
          oscillator.frequency.setValueAtTime(guitarString.frequency * harmonic.multiple, now)
          oscillator.connect(harmonicGains[index])
          oscillator.start()
          return oscillator
        })
        nodesRef.current = { master, oscillators, harmonicGains }
      }

      updateActiveString(guitarString)
    } catch {
      stop()
      setErrorMessage('无法播放参考音，请再次轻触琴弦。')
    }
  }, [stop, updateActiveString])

  const toggle = useCallback((guitarString: GuitarString) => {
    const current = activeStringRef.current
    if (
      current?.stringNumber === guitarString.stringNumber &&
      Math.abs(current.frequency - guitarString.frequency) < 0.01
    ) {
      stop()
      return
    }
    void play(guitarString)
  }, [play, stop])

  useEffect(() => {
    const handleVisibility = () => {
      if (document.visibilityState === 'hidden') stop()
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [stop])

  useEffect(() => () => {
    const nodes = nodesRef.current
    nodes?.oscillators.forEach((oscillator) => {
      try { oscillator.stop() } catch { /* The node may already be stopped. */ }
    })
    nodesRef.current = null
    if (contextRef.current && contextRef.current.state !== 'closed') {
      void contextRef.current.close()
    }
    contextRef.current = null
  }, [])

  return {
    activeString,
    isPlaying: activeString !== null,
    errorMessage,
    play,
    stop,
    toggle,
  }
}
