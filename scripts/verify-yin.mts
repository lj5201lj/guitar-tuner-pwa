import assert from 'node:assert/strict'
import { estimatePitchYin } from '../src/audio/pitch.ts'

const sampleRate = 48_000
const size = 4096

function guitarLikeWave(frequency: number, amplitude = 0.18): Float32Array {
  const buffer = new Float32Array(size)
  for (let i = 0; i < size; i += 1) {
    const t = i / sampleRate
    buffer[i] = amplitude * (
      Math.sin(2 * Math.PI * frequency * t) +
      0.35 * Math.sin(4 * Math.PI * frequency * t + 0.2) +
      0.15 * Math.sin(6 * Math.PI * frequency * t + 0.6)
    )
  }
  return buffer
}

for (const expected of [82.41, 110, 146.83, 196, 246.94, 329.63]) {
  const result = estimatePitchYin(guitarLikeWave(expected), sampleRate)
  assert.ok(result, `Expected a pitch for ${expected} Hz`)
  assert.ok(Math.abs(result.frequency - expected) < 0.8, `${expected} Hz was detected as ${result.frequency}`)
  assert.ok(result.clarity > 0.8, `Expected strong clarity for ${expected} Hz`)
}

console.log('YIN verification passed for all six standard guitar strings.')
