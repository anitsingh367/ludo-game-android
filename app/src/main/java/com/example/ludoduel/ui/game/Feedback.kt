package com.example.ludoduel.ui.game

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** TEMPORARY (redesign in progress): built-in tones, replaced by the game's own sounds. */
class ToneFx(private val view: View) : GameFx {
    var soundOn = true
    var vibrationOn = true

    // ToneGenerator can fail on devices without an audio output; then we only vibrate.
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    override fun play(sfx: Sfx) {
        if (!soundOn) return
        when (sfx) {
            Sfx.ROLL -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            Sfx.CAPTURE -> tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
            Sfx.HOME -> tones?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
            else -> Unit
        }
    }

    override fun buzz(kind: Buzz) {
        if (!vibrationOn) return
        view.performHapticFeedback(if (kind == Buzz.ROLL) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.LONG_PRESS)
    }

    fun release() {
        tones?.release()
    }
}

@Composable
fun rememberGameFx(soundOn: Boolean, vibrationOn: Boolean): GameFx {
    val view = LocalView.current
    val fx = remember(view) { ToneFx(view) }
    DisposableEffect(fx) { onDispose { fx.release() } }
    fx.soundOn = soundOn
    fx.vibrationOn = vibrationOn
    return fx
}
