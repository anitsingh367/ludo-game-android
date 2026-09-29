package com.example.ludoduel.ui.game

import androidx.compose.runtime.staticCompositionLocalOf

/** Game sounds. Each plays a short original clip (see ASSETS.md). */
enum class Sfx { ROLL, HOP, CAPTURE, HOME, SIX, WIN, LOSE, CLICK, YOUR_TURN }

/** Light vibrations for the moments that matter: roll, capture, home, and confirming a picked token. */
enum class Buzz { ROLL, CAPTURE, HOME, PICK }

/** Sound and vibration output used by the animations. Implementations respect the mute and vibration settings. */
interface GameFx {
    fun play(sfx: Sfx)
    fun buzz(kind: Buzz)
}

/** No sound, no vibration (previews, and before the real output is provided). */
object SilentFx : GameFx {
    override fun play(sfx: Sfx) = Unit
    override fun buzz(kind: Buzz) = Unit
}

/** The app-wide sound and vibration output (buttons use it for their click). */
val LocalGameFx = staticCompositionLocalOf<GameFx> { SilentFx }
