import assert from 'node:assert/strict'
import { estimatePitchYin, nearestGuitarString } from '../src/audio/pitch.ts'
import { pluckedStringDuration, synthesizePluckedString } from '../src/audio/pluckedString.ts'
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

let randomSeed = 0x12345678
const seededRandom = () => {
  randomSeed = (randomSeed * 1664525 + 1013904223) >>> 0
  return randomSeed / 0x1_0000_0000
}
const pluck = synthesizePluckedString(82.41, sampleRate, seededRandom)
const windowRms = (samples: Float32Array, start: number, length: number) => {
  let sum = 0
  for (let index = start; index < start + length; index += 1) sum += samples[index] ** 2
  return Math.sqrt(sum / length)
}
const quarterSecond = Math.round(sampleRate * 0.25)
const earlyEnergy = windowRms(pluck, quarterSecond, quarterSecond)
const lateEnergy = windowRms(pluck, pluck.length - quarterSecond, quarterSecond)
const settledPluck = pluck.slice(Math.round(sampleRate * 0.08), Math.round(sampleRate * 0.08) + size)
const synthesizedPitch = estimatePitchYin(settledPluck, sampleRate)
assert.ok(pluck.every(Number.isFinite), 'Plucked string samples must be finite')
assert.ok(Math.max(...pluck.subarray(0, sampleRate)) <= 0.721, 'Pluck must remain normalized')
assert.ok(earlyEnergy > lateEnergy * 8, 'Plucked string must decay substantially')
assert.ok(pluckedStringDuration(82.41) > pluckedStringDuration(329.63), 'Low strings should ring longer')
assert.ok(synthesizedPitch, 'Plucked reference must have a detectable pitch')
assert.ok(Math.abs(synthesizedPitch.frequency - 82.41) < 0.8, 'Plucked reference must stay in tune')

console.log('YIN, tuning preset, custom storage, and plucked-string verification passed.')
