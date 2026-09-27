package io.github.lj5201lj.xianzhun

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

data class NoteChoice(val label: String, val semitone: Int)

data class GuitarString(
    val number: Int,
    val note: String,
    val octave: Int,
    val frequency: Double = TuningMath.noteToFrequency(note, octave),
)

data class TuningPreset(
    val id: String,
    val name: String,
    val strings: List<GuitarString>,
)

object TuningMath {
    val noteChoices = listOf(
        NoteChoice("C", 0), NoteChoice("C♯", 1), NoteChoice("D♭", 1),
        NoteChoice("D", 2), NoteChoice("D♯", 3), NoteChoice("E♭", 3),
        NoteChoice("E", 4), NoteChoice("F", 5), NoteChoice("F♯", 6),
        NoteChoice("G♭", 6), NoteChoice("G", 7), NoteChoice("G♯", 8),
        NoteChoice("A♭", 8), NoteChoice("A", 9), NoteChoice("A♯", 10),
        NoteChoice("B♭", 10), NoteChoice("B", 11),
    )

    private val semitones = noteChoices.associate { it.label to it.semitone }

    fun noteToFrequency(note: String, octave: Int): Double {
        val semitone = requireNotNull(semitones[note]) { "Unsupported note: $note" }
        val midi = (octave + 1) * 12 + semitone
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    fun centsFromTarget(frequency: Double, target: Double): Double =
        1200.0 * log2(frequency / target)

    fun nearestString(frequency: Double, strings: List<GuitarString>): GuitarString =
        strings.minBy { abs(centsFromTarget(frequency, it.frequency)) }

    fun isCustomPitchValid(note: String, octave: Int): Boolean {
        val frequency = noteToFrequency(note, octave)
        return frequency in (noteToFrequency("B", 1) - 0.01)..(noteToFrequency("E", 5) + 0.01)
    }
}

object Tunings {
    private fun preset(id: String, name: String, notes: List<Pair<String, Int>>): TuningPreset {
        val stringNumbers = listOf(6, 5, 4, 3, 2, 1)
        return TuningPreset(
            id = id,
            name = name,
            strings = notes.mapIndexed { index, (note, octave) ->
                GuitarString(stringNumbers[index], note, octave)
            },
        )
    }

    val standard = preset(
        "standard", "标准调弦",
        listOf("E" to 2, "A" to 2, "D" to 3, "G" to 3, "B" to 3, "E" to 4),
    )

    val builtIns = listOf(
        standard,
        preset(
            "drop-d", "Drop D",
            listOf("D" to 2, "A" to 2, "D" to 3, "G" to 3, "B" to 3, "E" to 4),
        ),
        preset(
            "d-standard", "D 标准",
            listOf("D" to 2, "G" to 2, "C" to 3, "F" to 3, "A" to 3, "D" to 4),
        ),
        preset(
            "open-g", "Open G",
            listOf("D" to 2, "G" to 2, "D" to 3, "G" to 3, "B" to 3, "D" to 4),
        ),
        preset(
            "dadgad", "DADGAD",
            listOf("D" to 2, "A" to 2, "D" to 3, "G" to 3, "A" to 3, "D" to 4),
        ),
    )

    fun loadCustom(preferences: SharedPreferences, fallback: TuningPreset = standard): TuningPreset {
        val strings = (6 downTo 1).map { number ->
            val base = fallback.strings.first { it.number == number }
            val note = preferences.getString("custom_note_$number", base.note) ?: base.note
            val octave = preferences.getInt("custom_octave_$number", base.octave)
            if (runCatching { TuningMath.isCustomPitchValid(note, octave) }.getOrDefault(false)) {
                GuitarString(number, note, octave)
            } else {
                base
            }
        }
        return TuningPreset("custom", "自定义", strings)
    }

    fun saveCustom(preferences: SharedPreferences, strings: List<GuitarString>) {
        preferences.edit().apply {
            putInt("custom_version", 1)
            strings.forEach { string ->
                putString("custom_note_${string.number}", string.note)
                putInt("custom_octave_${string.number}", string.octave)
            }
        }.apply()
    }
}
