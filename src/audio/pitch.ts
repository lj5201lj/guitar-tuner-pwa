import { GUITAR_STRINGS, type GuitarString } from './tunings.ts'

export { GUITAR_STRINGS } from './tunings.ts'
export type { GuitarString } from './tunings.ts'

export type PitchEstimate = {
  frequency: number
  rms: number
  clarity: number
}

export function calculateRms(buffer: Float32Array): number {
  let sum = 0
  let mean = 0

  for (let i = 0; i < buffer.length; i += 1) mean += buffer[i]
  mean /= buffer.length

  for (let i = 0; i < buffer.length; i += 1) {
    const centered = buffer[i] - mean
    sum += centered * centered
  }

  return Math.sqrt(sum / buffer.length)
}

/**
 * YIN pitch detection using the cumulative mean normalized difference function.
 * A fixed comparison window avoids the short-lag bias caused by changing the
 * sample count for each tau. Returns null when no confident period is found.
 */
export function estimatePitchYin(
  buffer: Float32Array,
  sampleRate: number,
  minFrequency = 55,
  maxFrequency = 700,
  threshold = 0.15,
): PitchEstimate | null {
  if (buffer.length < 1024 || sampleRate <= 0) return null

  const rms = calculateRms(buffer)
  const minTau = Math.max(2, Math.floor(sampleRate / maxFrequency))
  const maxTau = Math.min(
    Math.floor(sampleRate / minFrequency),
    Math.floor(buffer.length / 2),
  )
  const comparisonLength = buffer.length - maxTau
  if (minTau >= maxTau || comparisonLength < maxTau) return null

  let mean = 0
  for (let i = 0; i < buffer.length; i += 1) mean += buffer[i]
  mean /= buffer.length

  const yin = new Float32Array(maxTau + 1)
  for (let tau = 1; tau <= maxTau; tau += 1) {
    let difference = 0
    for (let i = 0; i < comparisonLength; i += 1) {
      const delta = (buffer[i] - mean) - (buffer[i + tau] - mean)
      difference += delta * delta
    }
    yin[tau] = difference
  }

  yin[0] = 1
  let runningSum = 0
  for (let tau = 1; tau <= maxTau; tau += 1) {
    runningSum += yin[tau]
    yin[tau] = runningSum === 0 ? 1 : (yin[tau] * tau) / runningSum
  }

  let candidate = -1
  for (let tau = minTau; tau <= maxTau; tau += 1) {
    if (yin[tau] < threshold) {
      while (tau + 1 <= maxTau && yin[tau + 1] < yin[tau]) tau += 1
      candidate = tau
      break
    }
  }

  if (candidate < 0) {
    let bestValue = Number.POSITIVE_INFINITY
    for (let tau = minTau; tau <= maxTau; tau += 1) {
      if (yin[tau] < bestValue) {
        bestValue = yin[tau]
        candidate = tau
      }
    }
    if (candidate < 0 || bestValue > 0.28) return null
  }

  const left = candidate > 1 ? yin[candidate - 1] : yin[candidate]
  const center = yin[candidate]
  const right = candidate < maxTau ? yin[candidate + 1] : yin[candidate]
  const denominator = 2 * (2 * center - right - left)
  const adjustment = Math.abs(denominator) > 1e-10 ? (right - left) / denominator : 0
  const refinedTau = candidate + Math.max(-1, Math.min(1, adjustment))
  const frequency = sampleRate / refinedTau

  if (!Number.isFinite(frequency) || frequency < minFrequency || frequency > maxFrequency) {
    return null
  }

  return {
    frequency,
    rms,
    clarity: Math.max(0, Math.min(1, 1 - center)),
  }
}

export function centsFromTarget(frequency: number, targetFrequency: number): number {
  return 1200 * Math.log2(frequency / targetFrequency)
}

export function nearestGuitarString(
  frequency: number,
  strings: readonly GuitarString[] = GUITAR_STRINGS,
): GuitarString {
  return strings.reduce((closest, current) =>
    Math.abs(centsFromTarget(frequency, current.frequency)) <
    Math.abs(centsFromTarget(frequency, closest.frequency))
      ? current
      : closest,
  )
}
