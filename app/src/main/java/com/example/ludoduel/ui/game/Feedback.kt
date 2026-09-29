package com.example.ludoduel.ui.game

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Short tones and haptics for roll, capture and home. Uses built-in tones, so no sound files. */
class Feedback(private val view: View) {
    // ToneGenerator can fail on devices without an audio output; then we only vibrate.
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    fun roll() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun capture() {
        tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun home() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun release() {
        tones?.release()
    }
}

@Composable
fun rememberFeedback(): Feedback {
    val view = LocalView.current
    val feedback = remember(view) { Feedback(view) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    return feedback
}
