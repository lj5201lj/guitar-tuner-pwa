export type GuitarStringNumber = 1 | 2 | 3 | 4 | 5 | 6

export type NoteName =
  | 'C' | 'C♯' | 'D♭' | 'D' | 'D♯' | 'E♭' | 'E' | 'F'
  | 'F♯' | 'G♭' | 'G' | 'G♯' | 'A♭' | 'A' | 'A♯' | 'B♭' | 'B'

export type GuitarString = {
  id: GuitarStringNumber
  stringNumber: GuitarStringNumber
  note: NoteName
  octave: number
  frequency: number
}

export type TuningPreset = {
  id: 'standard' | 'drop-d' | 'd-standard' | 'open-g' | 'dadgad' | 'custom'
  name: string
  builtIn: boolean
  strings: readonly GuitarString[]
}

export const NOTE_OPTIONS: readonly NoteName[] = [
  'C', 'C♯', 'D♭', 'D', 'D♯', 'E♭', 'E', 'F',
  'F♯', 'G♭', 'G', 'G♯', 'A♭', 'A', 'A♯', 'B♭', 'B',
] as const

const NOTE_SEMITONES: Record<NoteName, number> = {
  C: 0,
  'C♯': 1,
  'D♭': 1,
  D: 2,
  'D♯': 3,
  'E♭': 3,
  E: 4,
  F: 5,
  'F♯': 6,
  'G♭': 6,
  G: 7,
  'G♯': 8,
  'A♭': 8,
  A: 9,
  'A♯': 10,
  'B♭': 10,
  B: 11,
}

export const CUSTOM_TUNING_STORAGE_KEY = 'xianzhun.custom-tuning.v1'
export const CUSTOM_MIN_FREQUENCY = 61.74 // B1
export const CUSTOM_MAX_FREQUENCY = 659.26 // E5

export function noteToFrequency(note: NoteName, octave: number): number {
  const midi = (octave + 1) * 12 + NOTE_SEMITONES[note]
  return 440 * 2 ** ((midi - 69) / 12)
}

export function isCustomPitchInRange(note: NoteName, octave: number): boolean {
  const frequency = noteToFrequency(note, octave)
  return frequency >= CUSTOM_MIN_FREQUENCY - 0.01 && frequency <= CUSTOM_MAX_FREQUENCY + 0.01
}

function makeString(stringNumber: GuitarStringNumber, note: NoteName, octave: number): GuitarString {
  return {
    id: stringNumber,
    stringNumber,
    note,
    octave,
    frequency: noteToFrequency(note, octave),
  }
}

function makePreset(
  id: TuningPreset['id'],
  name: string,
  notes: readonly [NoteName, number][],
  builtIn = true,
): TuningPreset {
  const stringNumbers: readonly GuitarStringNumber[] = [6, 5, 4, 3, 2, 1]
  return {
    id,
    name,
    builtIn,
    strings: notes.map(([note, octave], index) => makeString(stringNumbers[index], note, octave)),
  }
}

export const BUILT_IN_TUNINGS: readonly TuningPreset[] = [
  makePreset('standard', '标准调弦', [['E', 2], ['A', 2], ['D', 3], ['G', 3], ['B', 3], ['E', 4]]),
  makePreset('drop-d', 'Drop D', [['D', 2], ['A', 2], ['D', 3], ['G', 3], ['B', 3], ['E', 4]]),
  makePreset('d-standard', 'D 标准', [['D', 2], ['G', 2], ['C', 3], ['F', 3], ['A', 3], ['D', 4]]),
  makePreset('open-g', 'Open G', [['D', 2], ['G', 2], ['D', 3], ['G', 3], ['B', 3], ['D', 4]]),
  makePreset('dadgad', 'DADGAD', [['D', 2], ['A', 2], ['D', 3], ['G', 3], ['A', 3], ['D', 4]]),
] as const

export const STANDARD_TUNING = BUILT_IN_TUNINGS[0]
export const GUITAR_STRINGS = STANDARD_TUNING.strings

export function createCustomTuning(strings: readonly GuitarString[]): TuningPreset {
  return {
    id: 'custom',
    name: '自定义',
    builtIn: false,
    strings: strings.map(({ stringNumber, note, octave }) => makeString(stringNumber, note, octave)),
  }
}

export function cloneAsCustom(preset: TuningPreset): TuningPreset {
  return createCustomTuning(preset.strings)
}

type StoredCustomTuning = {
  version: 1
  strings: Array<Pick<GuitarString, 'stringNumber' | 'note' | 'octave'>>
}

function isStoredString(value: unknown): value is StoredCustomTuning['strings'][number] {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<StoredCustomTuning['strings'][number]>
  return (
    Number.isInteger(candidate.stringNumber) &&
    candidate.stringNumber !== undefined &&
    candidate.stringNumber >= 1 && candidate.stringNumber <= 6 &&
    typeof candidate.note === 'string' &&
    NOTE_OPTIONS.includes(candidate.note as NoteName) &&
    Number.isInteger(candidate.octave) &&
    candidate.octave !== undefined &&
    isCustomPitchInRange(candidate.note as NoteName, candidate.octave)
  )
}

export function loadCustomTuning(storage: Pick<Storage, 'getItem'> = localStorage): TuningPreset | null {
  try {
    const raw = storage.getItem(CUSTOM_TUNING_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredCustomTuning>
    if (parsed.version !== 1 || !Array.isArray(parsed.strings) || parsed.strings.length !== 6) return null
    if (!parsed.strings.every(isStoredString)) return null
    const sorted = [...parsed.strings].sort((a, b) => b.stringNumber - a.stringNumber)
    if (new Set(sorted.map((item) => item.stringNumber)).size !== 6) return null
    return createCustomTuning(sorted.map((item) => makeString(item.stringNumber, item.note, item.octave)))
  } catch {
    return null
  }
}

export function saveCustomTuning(
  tuning: TuningPreset,
  storage: Pick<Storage, 'setItem'> = localStorage,
): void {
  const stored: StoredCustomTuning = {
    version: 1,
    strings: tuning.strings.map(({ stringNumber, note, octave }) => ({ stringNumber, note, octave })),
  }
  storage.setItem(CUSTOM_TUNING_STORAGE_KEY, JSON.stringify(stored))
}
