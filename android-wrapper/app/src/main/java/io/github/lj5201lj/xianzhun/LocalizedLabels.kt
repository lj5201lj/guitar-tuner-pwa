package io.github.lj5201lj.xianzhun

import android.content.Context

fun TuningPreset.localizedName(context: Context): String {
    val resource = when (id) {
        "standard" -> R.string.tuning_standard
        "drop-d" -> R.string.tuning_drop_d
        "d-standard" -> R.string.tuning_d_standard
        "open-g" -> R.string.tuning_open_g
        "dadgad" -> R.string.tuning_dadgad
        "custom" -> R.string.tuning_custom
        else -> return name
    }
    return context.getString(resource)
}

fun ClickSound.localizedLabel(context: Context): String = context.getString(when (this) {
    ClickSound.CLICK -> R.string.sound_click
    ClickSound.WOOD -> R.string.sound_wood
    ClickSound.BEEP -> R.string.sound_beep
})
