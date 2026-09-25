import { useCallback, useEffect, useRef, useState } from 'react'
import { synthesizePluckedString } from './pluckedString'
import type { GuitarString } from './tunings'

const STOP_FADE_SECONDS = 0.025
const OUTPUT_GAIN = 0.58

type GuitarVoice = {
  source: AudioBufferSourceNode
  gain: GainNode
  nodes: AudioNode[]
}

function getAudioContextClass() {
  return window.AudioContext ||
    (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
}

function disconnectVoice(voice: GuitarVoice) {
  voice.source.disconnect()
  voice.nodes.forEach((node) => node.disconnect())
}

export function useReferenceTone() {
  const [activeString, setActiveString] = useState<GuitarString | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const contextRef = useRef<AudioContext | null>(null)
  const voiceRef = useRef<GuitarVoice | null>(null)

  const releaseVoice = useCallback((voice: GuitarVoice, context: AudioContext) => {
    const now = context.currentTime
    voice.gain.gain.cancelScheduledValues(now)
    voice.gain.gain.setValueAtTime(Math.max(voice.gain.gain.value, 0.0001), now)
    voice.gain.gain.exponentialRampToValueAtTime(0.0001, now + STOP_FADE_SECONDS)
    try { voice.source.stop(now + STOP_FADE_SECONDS + 0.005) } catch { /* Already ended. */ }
  }, [])

  const stop = useCallback(() => {
    const context = contextRef.current
    const voice = voiceRef.current
    voiceRef.current = null
    setActiveString(null)
    if (context && voice) releaseVoice(voice, context)
  }, [releaseVoice])

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

      const previousVoice = voiceRef.current
      if (previousVoice) releaseVoice(previousVoice, context)

      const samples = synthesizePluckedString(guitarString.frequency, context.sampleRate)
      const buffer = context.createBuffer(1, samples.length, context.sampleRate)
      buffer.getChannelData(0).set(samples)

      const source = context.createBufferSource()
      const highPass = context.createBiquadFilter()
      const bodyLow = context.createBiquadFilter()
      const bodyMid = context.createBiquadFilter()
      const lowPass = context.createBiquadFilter()
      const gain = context.createGain()

      source.buffer = buffer
      highPass.type = 'highpass'
      highPass.frequency.value = 48
      highPass.Q.value = 0.7
      bodyLow.type = 'peaking'
      bodyLow.frequency.value = 110
      bodyLow.Q.value = 1.05
      bodyLow.gain.value = 3.6
      bodyMid.type = 'peaking'
      bodyMid.frequency.value = 220
      bodyMid.Q.value = 1.4
      bodyMid.gain.value = 2.1
      lowPass.type = 'lowpass'
      lowPass.frequency.value = Math.min(5_800, 3_000 + guitarString.frequency * 4)
      lowPass.Q.value = 0.72
      gain.gain.value = OUTPUT_GAIN

      source.connect(highPass)
      highPass.connect(bodyLow)
      bodyLow.connect(bodyMid)
      bodyMid.connect(lowPass)
      lowPass.connect(gain)
      gain.connect(context.destination)

      const voice: GuitarVoice = {
        source,
        gain,
        nodes: [highPass, bodyLow, bodyMid, lowPass, gain],
      }
      voiceRef.current = voice
      source.onended = () => {
        disconnectVoice(voice)
        if (voiceRef.current === voice) {
          voiceRef.current = null
          setActiveString(null)
        }
      }

      setActiveString(guitarString)
      source.start()
    } catch {
      stop()
      setErrorMessage('无法播放吉他参考音，请再次轻触琴弦。')
    }
  }, [releaseVoice, stop])

  const toggle = useCallback((guitarString: GuitarString) => {
    void play(guitarString)
  }, [play])

  useEffect(() => {
    const handleVisibility = () => {
      if (document.visibilityState === 'hidden') stop()
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [stop])

  useEffect(() => () => {
    const voice = voiceRef.current
    if (voice) {
      try { voice.source.stop() } catch { /* Already ended. */ }
      disconnectVoice(voice)
    }
    voiceRef.current = null
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
