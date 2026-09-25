import { useCallback, useEffect, useRef, useState } from 'react'
import {
  GUITAR_SAMPLES,
  playbackRateForSample,
  selectNearestGuitarSample,
  type GuitarSample,
} from './guitarSamples'
import type { GuitarString } from './tunings'

const STOP_FADE_SECONDS = 0.025
const OUTPUT_GAIN = 0.88
const sampleRequests = new Map<string, Promise<ArrayBuffer>>()

type GuitarVoice = {
  source: AudioBufferSourceNode
  gain: GainNode
  disposed: boolean
}

function getAudioContextClass() {
  return window.AudioContext ||
    (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
}

function requestSample(url: string): Promise<ArrayBuffer> {
  const existing = sampleRequests.get(url)
  if (existing) return existing

  const request = fetch(url)
    .then((response) => {
      if (!response.ok) throw new Error(`Unable to load sample: ${response.status}`)
      return response.arrayBuffer()
    })
    .catch((error) => {
      sampleRequests.delete(url)
      throw error
    })
  sampleRequests.set(url, request)
  return request
}

function disconnectVoice(voice: GuitarVoice) {
  if (voice.disposed) return
  voice.disposed = true
  voice.source.disconnect()
  voice.gain.disconnect()
}

export function useReferenceTone() {
  const [activeString, setActiveString] = useState<GuitarString | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const contextRef = useRef<AudioContext | null>(null)
  const voiceRef = useRef<GuitarVoice | null>(null)
  const decodedSamplesRef = useRef(new Map<string, AudioBuffer>())
  const requestIdRef = useRef(0)
  const mountedRef = useRef(true)

  const releaseVoice = useCallback((voice: GuitarVoice, context: AudioContext) => {
    const now = context.currentTime
    voice.gain.gain.cancelScheduledValues(now)
    voice.gain.gain.setValueAtTime(Math.max(voice.gain.gain.value, 0.0001), now)
    voice.gain.gain.exponentialRampToValueAtTime(0.0001, now + STOP_FADE_SECONDS)
    try { voice.source.stop(now + STOP_FADE_SECONDS + 0.005) } catch { /* Already ended. */ }
  }, [])

  const stop = useCallback(() => {
    requestIdRef.current += 1
    const context = contextRef.current
    const voice = voiceRef.current
    voiceRef.current = null
    setActiveString(null)
    if (context && voice) releaseVoice(voice, context)
  }, [releaseVoice])

  const decodeSample = useCallback(async (
    context: AudioContext,
    guitarSample: GuitarSample,
  ): Promise<AudioBuffer> => {
    const cached = decodedSamplesRef.current.get(guitarSample.id)
    if (cached) return cached
    const encoded = await requestSample(guitarSample.url)
    const decoded = await context.decodeAudioData(encoded.slice(0))
    decodedSamplesRef.current.set(guitarSample.id, decoded)
    return decoded
  }, [])

  const play = useCallback(async (guitarString: GuitarString) => {
    const requestId = requestIdRef.current + 1
    requestIdRef.current = requestId
    setErrorMessage('')
    setActiveString(guitarString)

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
      voiceRef.current = null
      if (previousVoice) releaseVoice(previousVoice, context)

      const guitarSample = selectNearestGuitarSample(guitarString.frequency)
      const buffer = await decodeSample(context, guitarSample)
      if (!mountedRef.current || requestIdRef.current !== requestId) return

      const source = context.createBufferSource()
      const gain = context.createGain()
      source.buffer = buffer
      source.playbackRate.value = playbackRateForSample(guitarString.frequency, guitarSample)
      gain.gain.value = OUTPUT_GAIN
      source.connect(gain)
      gain.connect(context.destination)

      const voice: GuitarVoice = { source, gain, disposed: false }
      voiceRef.current = voice
      source.onended = () => {
        disconnectVoice(voice)
        if (voiceRef.current === voice) {
          voiceRef.current = null
          setActiveString(null)
        }
      }
      source.start()
    } catch {
      if (requestIdRef.current !== requestId) return
      stop()
      setErrorMessage('真实吉他音频载入失败，请刷新页面后重试。')
    }
  }, [decodeSample, releaseVoice, stop])

  const toggle = useCallback((guitarString: GuitarString) => {
    void play(guitarString)
  }, [play])

  useEffect(() => {
    GUITAR_SAMPLES.forEach((guitarSample) => {
      void requestSample(guitarSample.url).catch(() => undefined)
    })
  }, [])

  useEffect(() => {
    const handleVisibility = () => {
      if (document.visibilityState === 'hidden') stop()
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [stop])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      requestIdRef.current += 1
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
    }
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
