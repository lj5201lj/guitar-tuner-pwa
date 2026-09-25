import { noteToFrequency, type NoteName } from './tunings.ts'

export type GuitarSample = {
  id: string
  note: NoteName
  octave: number
  frequency: number
  url: string
}

function sample(note: NoteName, octave: number): GuitarSample {
  const fileNote = note.replace('♯', 's').replace('♭', 'b')
  return {
    id: `${note}${octave}`,
    note,
    octave,
    frequency: noteToFrequency(note, octave),
    url: `/audio/guitar-acoustic/${fileNote}${octave}.mp3`,
  }
}

export const GUITAR_SAMPLES: readonly GuitarSample[] = [
  sample('D', 2),
  sample('E', 2),
  sample('G', 2),
  sample('A', 2),
  sample('C', 3),
  sample('D', 3),
  sample('F', 3),
  sample('G', 3),
  sample('A', 3),
  sample('B', 3),
  sample('D', 4),
  sample('E', 4),
  sample('A', 4),
  sample('D', 5),
] as const

export function selectNearestGuitarSample(frequency: number): GuitarSample {
  return GUITAR_SAMPLES.reduce((closest, current) =>
    Math.abs(Math.log2(frequency / current.frequency)) <
    Math.abs(Math.log2(frequency / closest.frequency))
      ? current
      : closest,
  )
}

export function playbackRateForSample(frequency: number, guitarSample: GuitarSample): number {
  return frequency / guitarSample.frequency
}
