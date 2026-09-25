import assert from 'node:assert/strict'
import { estimatePitchYin, nearestGuitarString } from '../src/audio/pitch.ts'
import {
  BUILT_IN_TUNINGS,
  cloneAsCustom,
  createCustomTuning,
  loadCustomTuning,
  noteToFrequency,
  saveCustomTuning,
  STANDARD_TUNING,
} from '../src/audio/tunings.ts'

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

for (const expected of [61.74, 82.41, 110, 146.83, 196, 246.94, 329.63, 659.26]) {
  const result = estimatePitchYin(guitarLikeWave(expected), sampleRate)
  assert.ok(result, `Expected a pitch for ${expected} Hz`)
  assert.ok(Math.abs(result.frequency - expected) < 0.8, `${expected} Hz was detected as ${result.frequency}`)
  assert.ok(result.clarity > 0.8, `Expected strong clarity for ${expected} Hz`)
}

assert.ok(Math.abs(noteToFrequency('A', 4) - 440) < 0.001)
assert.ok(Math.abs(noteToFrequency('E', 2) - 82.4069) < 0.001)
assert.ok(Math.abs(noteToFrequency('D♭', 3) - noteToFrequency('C♯', 3)) < 0.001)
assert.deepEqual(BUILT_IN_TUNINGS.map((preset) => preset.id), [
  'standard', 'drop-d', 'd-standard', 'open-g', 'dadgad',
])
assert.equal(BUILT_IN_TUNINGS.find((preset) => preset.id === 'drop-d')?.strings[0].note, 'D')

const openG = BUILT_IN_TUNINGS.find((preset) => preset.id === 'open-g')!
assert.equal(nearestGuitarString(noteToFrequency('B', 3), openG.strings).stringNumber, 2)

const custom = createCustomTuning(cloneAsCustom(STANDARD_TUNING).strings.map((string) =>
  string.stringNumber === 6
    ? { ...string, note: 'B' as const, octave: 1, frequency: noteToFrequency('B', 1) }
    : string,
))
const memory = new Map<string, string>()
const storage = {
  getItem: (key: string) => memory.get(key) ?? null,
  setItem: (key: string, value: string) => { memory.set(key, value) },
}
saveCustomTuning(custom, storage)
const restored = loadCustomTuning(storage)
assert.equal(restored?.strings[0].note, 'B')
assert.equal(restored?.strings[0].octave, 1)

console.log('YIN, tuning preset, frequency, and custom storage verification passed.')
