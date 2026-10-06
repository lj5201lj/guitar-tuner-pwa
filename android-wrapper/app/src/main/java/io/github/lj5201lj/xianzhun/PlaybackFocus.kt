package io.github.lj5201lj.xianzhun

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Shared by the two mutually exclusive output tools, including Android 6/7 support. */
class PlaybackFocus(private val manager: AudioManager, private val onLoss: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var acquired = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) handler.post { if (acquired) onLoss() }
    }

    @Suppress("DEPRECATION")
    fun acquire(): Boolean {
        if (acquired) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener, handler)
                .build()
            request = next
            manager.requestAudioFocus(next)
        } else {
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        acquired = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return acquired
    }

    @Suppress("DEPRECATION")
    fun release() {
        acquired = false
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { manager.abandonAudioFocusRequest(it) }
        } else manager.abandonAudioFocus(listener)
        request = null
    }
}
