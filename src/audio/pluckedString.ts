const MIN_DURATION_SECONDS = 3.1
const MAX_DURATION_SECONDS = 5.4

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

export function pluckedStringDuration(frequency: number): number {
  return clamp(5.85 - frequency / 135, MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
}

/**
 * Generates a guitar-like pluck with a Karplus–Strong feedback string.
 * The pick-position comb shapes the harmonics while the feedback loss creates
 * a natural decay. Body resonances are applied by Web Audio filters at playback.
 */
export function synthesizePluckedString(
  frequency: number,
  sampleRate: number,
  random: () => number = Math.random,
): Float32Array {
  if (!Number.isFinite(frequency) || frequency <= 0) throw new RangeError('Invalid frequency')
  if (!Number.isFinite(sampleRate) || sampleRate < 8_000) throw new RangeError('Invalid sample rate')

  const duration = pluckedStringDuration(frequency)
  const output = new Float32Array(Math.ceil(duration * sampleRate))
  const delayLength = Math.max(2, Math.round(sampleRate / frequency - 0.5))
  const excitation = new Float32Array(delayLength)
  const pickOffset = Math.max(1, Math.round(delayLength * 0.22))
  let previousNoise = 0

  for (let index = 0; index < delayLength; index += 1) {
    const whiteNoise = random() * 2 - 1
    excitation[index] = whiteNoise * 0.72 + previousNoise * 0.28
    previousNoise = whiteNoise
  }

  const delay = new Float32Array(delayLength)
  for (let index = 0; index < delayLength; index += 1) {
    const shifted = excitation[(index + pickOffset) % delayLength]
    delay[index] = excitation[index] - shifted * 0.42
  }

  const feedback = Math.exp(Math.log(0.001) / (duration * frequency))
  const attackSamples = Math.max(1, Math.round(sampleRate * 0.004))
  const releaseSamples = Math.max(1, Math.round(sampleRate * 0.08))
  let delayIndex = 0
  let peak = 0

  for (let index = 0; index < output.length; index += 1) {
    const current = delay[delayIndex]
    const next = delay[(delayIndex + 1) % delayLength]
    const filtered = (current + next) * 0.5
    delay[delayIndex] = filtered * feedback
    delayIndex = (delayIndex + 1) % delayLength

    const attack = Math.min(1, index / attackSamples)
    const remaining = output.length - index
    const release = Math.min(1, remaining / releaseSamples)
    const sample = Math.tanh(current * 1.35) * attack * release
    output[index] = sample
    peak = Math.max(peak, Math.abs(sample))
  }

  const normalization = peak > 0 ? 0.72 / peak : 1
  for (let index = 0; index < output.length; index += 1) output[index] *= normalization
  return output
}
